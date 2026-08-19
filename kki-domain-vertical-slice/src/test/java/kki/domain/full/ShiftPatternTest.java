package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Le calendrier à deux résolutions, sur les trois choses qui peuvent mal tourner.
 *
 * <ul>
 * <li><b>Le partage</b> — l'opérateur demande d'éviter les explosions de ressources. Un motif
 * porte cent quatre-vingt-deux enregistrements journaliers ; si chaque ressource en détenait une
 * copie, mille neuf cents ressources coûteraient des dizaines de mégaoctets pour une information
 * identique. Vérifié par IDENTITÉ d'instance et non par taille : une copie profonde accidentelle
 * passerait n'importe quelle assertion sur des mégaoctets — vingt-deux mégaoctets n'ont l'air de
 * rien — mais échoue sur {@code ==}.</li>
 * <li><b>La couture</b> entre résolution fine et résolution grossière. C'est le seul endroit
 * nouveau où les deux conversions inverses peuvent se contredire.</li>
 * <li><b>La variété des horaires</b> — machines par secteur, personnel par profil, quatre ou huit
 * heures, prises de poste échelonnées. Un générateur qui les écraserait toutes sur un même
 * horaire rendrait vrais les autres tests sans rien modéliser.</li>
 * </ul>
 */
class ShiftPatternTest {

    private static final long HOUR = 3600L;
    private static final long DAY = 86_400L;

    @BeforeEach
    void resetDomainParameters() {
        FullDataGenerator.reset();
    }

    @Test
    void allResourcesOfOneSectorShareTheVerySamePatternInstance() {
        JobShopSolution solution = FullDataGenerator.generate(200, 5L);

        Map<ShiftPattern> machinePatterns = Map.identity();
        for (Machine machine : solution.getMachineList()) {
            machinePatterns.add(machine.getCalendar().getPattern());
        }
        Map<ShiftPattern> setterPatterns = Map.identity();
        for (Setter setter : solution.getSetterList()) {
            setterPatterns.add(setter.getCalendar().getPattern());
        }

        assertTrue(machinePatterns.size() <= FullDataGenerator.machineSectorCount,
                solution.getMachineList().size() + " machines ne doivent pas porter plus de "
                        + FullDataGenerator.machineSectorCount + " motifs DISTINCTS, vues "
                        + machinePatterns.size() + " instances");
        assertTrue(setterPatterns.size() <= FullDataGenerator.setterProfileCount,
                solution.getSetterList().size() + " metteurs pour au plus "
                        + FullDataGenerator.setterProfileCount + " profils, vues "
                        + setterPatterns.size() + " instances");
        assertTrue(machinePatterns.size() > 1, "un seul motif pour tout le parc : les secteurs"
                + " n'ont alors aucune existence");
    }

    @Test
    void addingBlackoutsKeepsTheSharedPatternByReference() {
        // Le point sur lequel tout le partage repose. Le générateur crée un calendrier par
        // ressource pour y inscrire sa maintenance ; si withBlackouts recopiait le motif, chaque
        // ressource porterait sa propre table journalière et le partage serait perdu en silence.
        ShiftPattern shared = ShiftPattern.regular(182, 0b001_1111, 6 * HOUR, 8 * HOUR, new int[0]);
        WorkCalendar base = new WorkCalendar(shared, new long[0]);
        WorkCalendar withOutage = base.withBlackouts(new long[] { 10 * DAY, 12 * DAY });

        assertSame(shared, base.getPattern(), "le motif fourni doit être celui référencé");
        assertSame(shared, withOutage.getPattern(),
                "withBlackouts doit traverser le motif par RÉFÉRENCE, jamais le recopier");
    }

    @Test
    void theTwoConversionsAgreeAcrossTheFineToCoarseSeam() {
        // La couture est le seul endroit nouveau où les deux inverses peuvent se contredire.
        // L'aller-retour est l'instrument qui a déjà trouvé le point fixe non convergent et la
        // classe d'équivalence de quatre jours ; on le fait ici enjamber le jour 182.
        int fineDays = 182;
        ShiftPattern pattern = ShiftPattern.regular(fineDays, 0b001_1111, 6 * HOUR, 8 * HOUR,
                new int[] { 40, 41, 100 });
        WorkCalendar calendar = new WorkCalendar(pattern, new long[0]);

        for (long day = fineDays - 6; day <= fineDays + 6; day++) {
            for (long hour : new long[] { 0, 7, 12, 20 }) {
                long start = day * DAY + hour * HOUR;
                for (long work : new long[] { 2 * HOUR, 8 * HOUR, 40 * HOUR, 200 * HOUR }) {
                    long end = calendar.occupancyEnd(start, work);
                    assertEquals(calendar.workedSecondsBefore(start) + work,
                            calendar.workedSecondsBefore(end),
                            "le temps ouvert consommé doit être exactement celui demandé, depuis t="
                                    + start + " pour " + work + " s");
                    long back = calendar.occupancyStart(end, work);
                    assertEquals(calendar.workedSecondsBefore(start),
                            calendar.workedSecondsBefore(back),
                            "aller-retour incohérent en enjambant la couture, depuis t=" + start);
                }
            }
        }
    }

    @Test
    void theFineZoneCarriesPerDayStructureAndTheCoarseZoneDoesNot() {
        // Ce qui distingue les deux résolutions : la zone fine porte des fermetures DATÉES —
        // fériés, ponts — que la zone grossière ne reproduit pas. Sans cette différence, les deux
        // zones seraient la même chose et la structure ne servirait à rien.
        int fineDays = 60;
        ShiftPattern pattern = ShiftPattern.regular(fineDays, 0b001_1111, 8 * HOUR, 8 * HOUR,
                new int[] { 10 });
        assertTrue(!pattern.isOpenAt(10 * DAY + 10 * HOUR),
                "le jour 10 est férié : fermé malgré un jour de semaine ouvré");
        assertTrue(pattern.isOpenAt(11 * DAY + 10 * HOUR), "le lendemain est ouvert");
        // Le même jour de semaine, au-delà de la couture, n'hérite PAS de la fermeture datée.
        long sameWeekdayLater = (10 + 70) * DAY + 10 * HOUR;
        assertTrue(sameWeekdayLater / DAY >= fineDays, "le montage doit viser la zone grossière");
        assertTrue(pattern.isOpenAt(sameWeekdayLater),
                "au-delà de la couture le motif est simplifié : plus de fériés datés");
    }

    @Test
    void theGeneratorProducesGenuinelyDifferentSchedules() {
        // « Certaines machines vingt-quatre heures sur vingt-quatre, d'autres huit heures » ; et
        // pour le personnel « certains plus tôt, d'autres plus tard, quatre ou huit heures ».
        JobShopSolution solution = FullDataGenerator.generate(300, 9L);

        Set<Long> weeklyMachineHours = new HashSet<>();
        for (Machine machine : solution.getMachineList()) {
            weeklyMachineHours.add(machine.getCalendar().getPattern().workedBefore(7 * DAY) / HOUR);
        }
        assertTrue(weeklyMachineHours.contains(168L),
                "des machines doivent tourner 24/7, semaines vues : " + weeklyMachineHours);
        assertTrue(weeklyMachineHours.size() >= 2,
                "toutes les machines ne peuvent pas avoir le même horaire : " + weeklyMachineHours);

        Set<Long> dailySetterHours = new HashSet<>();
        Set<Long> setterStarts = new HashSet<>();
        for (Setter setter : solution.getSetterList()) {
            ShiftPattern pattern = setter.getCalendar().getPattern();
            dailySetterHours.add(pattern.workedBefore(DAY) / HOUR);
            for (long hour = 0; hour < 24; hour++) {
                if (pattern.isOpenAt(hour * HOUR)) {
                    setterStarts.add(hour);
                    break;
                }
            }
        }
        assertTrue(dailySetterHours.contains(8L), "huit heures est le cas courant : " + dailySetterHours);
        assertTrue(dailySetterHours.contains(4L), "des demi-journées existent : " + dailySetterHours);
        assertTrue(dailySetterHours.stream().noneMatch(hours -> hours > 8L),
                "huit heures est le maximum : " + dailySetterHours);
        assertTrue(setterStarts.size() >= 3,
                "les prises de poste doivent être échelonnées, vues : " + setterStarts);
    }

    /** Ensemble par IDENTITÉ — {@code equals} laisserait passer une copie profonde. */
    private static final class Map<T> {
        private final IdentityHashMap<T, Boolean> seen = new IdentityHashMap<>();

        static <T> Map<T> identity() {
            return new Map<>();
        }

        void add(T value) {
            seen.put(value, Boolean.TRUE);
        }

        int size() {
            return seen.size();
        }
    }
}
