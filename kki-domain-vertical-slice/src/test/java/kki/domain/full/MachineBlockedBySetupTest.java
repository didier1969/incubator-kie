package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Le piège central de `CPT-KKI-007`, sur le cas que l'opérateur a donné en exemple.
 *
 * <p>
 * <b>Le mécanisme.</b> Une mise en train immobilise la machine depuis l'instant où celle-ci se
 * libère jusqu'à la FIN de la mise en train — pas seulement pendant les heures où le metteur
 * travaille. La machine pourrait tourner 24 h sur 24 ; elle ne produit rien dans les trous du
 * calendrier du metteur. C'est le multiplicateur dominant de la durée physique de l'atelier,
 * loin devant la durée d'usinage.
 *
 * <p>
 * <b>Le cas.</b> Un metteur qui travaille <b>le vendredi et le lundi</b>, huit heures chaque
 * jour. Une mise en train de seize heures. La machine se libère le jeudi. Résultat attendu :
 * cent douze heures d'immobilisation, dont <b>quatre-vingt-seize heures de production perdue</b>
 * pour seize heures de travail.
 *
 * <p>
 * Ce cas était <b>inexprimable</b> jusqu'ici : le calendrier ne savait décrire que « les N
 * premiers jours de la semaine », un motif contigu, et vendredi + lundi ne l'est pas. Le
 * généraliser a révélé une seconde erreur, que ce test verrouille aussi — voir plus bas.
 */
class MachineBlockedBySetupTest {

    private static final long HOUR = 3600L;
    private static final long DAY = 86_400L;

    /** Vendredi et lundi, 08:00–16:00. L'origine des temps est un lundi 00:00. */
    private static final WorkCalendar FRIDAY_AND_MONDAY_8H =
            new WorkCalendar(WorkCalendar.FRIDAY_AND_MONDAY, 8 * HOUR, 8 * HOUR, new long[0]);

    @BeforeEach
    void resetDomainParameters() {
        FullDataGenerator.reset();
    }

    @Test
    void sixteenHoursOfSetupBlockNinetySixHoursOfProduction() {
        // Machine libre le jeudi 00:00. Le metteur n'est pas là : il ne commencera que vendredi
        // 08:00, fera huit heures jusqu'à 16:00, puis rien jusqu'au lundi 08:00, où il finira
        // les huit heures restantes à 16:00.
        long machineFreeAt = 3 * DAY;
        long setupSeconds = 16 * HOUR;
        long expectedEnd = 7 * DAY + 16 * HOUR; // lundi de la semaine suivante, 16:00

        assertEquals(expectedEnd, FRIDAY_AND_MONDAY_8H.occupancyEnd(machineFreeAt, setupSeconds),
                "la mise en train doit se terminer le lundi suivant à 16:00");
        assertEquals(112 * HOUR, expectedEnd - machineFreeAt,
                "la machine est immobilisée cent douze heures");
        assertEquals(96 * HOUR, FRIDAY_AND_MONDAY_8H.idleDuring(machineFreeAt, setupSeconds),
                "quatre-vingt-seize heures de production perdue pour seize heures de travail");
    }

    @Test
    void theSetupEndIsTheFirstInstantOfItsClassNotTheLast() {
        // L'erreur trouvée en écrivant le test précédent. Un compteur de temps ouvert ne désigne
        // pas un instant mais un intervalle : vendredi 16:00 et lundi 08:00 portent le même
        // compteur. Rendre le représentant le plus TARDIF pour une date de FIN facturait tout
        // l'intervalle à la machine — une nuit de trop en lundi-mercredi, QUATRE JOURS ici.
        long endOfFriday = 4 * DAY + 16 * HOUR;
        assertEquals(endOfFriday, FRIDAY_AND_MONDAY_8H.occupancyEnd(4 * DAY + 8 * HOUR, 8 * HOUR),
                "huit heures depuis vendredi 08:00 finissent vendredi 16:00, pas lundi 08:00");

        // Et la borne au plus tard, elle, veut bien l'autre bout de l'intervalle.
        assertTrue(FRIDAY_AND_MONDAY_8H.latestTimeAtWorkedSeconds(
                FRIDAY_AND_MONDAY_8H.workedSecondsBefore(endOfFriday)) > endOfFriday,
                "la conversion au plus TARD doit dépasser la fermeture du vendredi");
    }

    @Test
    void aNonContiguousWeekIsExpressibleAtAll() {
        // Le motif lui-même, vérifié jour par jour : sans cela, un calendrier qui ignorerait
        // silencieusement le masque rendrait les deux tests précédents vrais pour de mauvaises
        // raisons.
        assertTrue(FRIDAY_AND_MONDAY_8H.isOpenAt(10 * HOUR), "lundi 10:00 ouvert");
        assertTrue(FRIDAY_AND_MONDAY_8H.isOpenAt(4 * DAY + 10 * HOUR), "vendredi 10:00 ouvert");
        for (int day : new int[] { 1, 2, 3, 5, 6 }) {
            assertTrue(!FRIDAY_AND_MONDAY_8H.isOpenAt(day * DAY + 10 * HOUR),
                    "le jour " + day + " ne doit PAS être ouvert");
        }
        assertTrue(!FRIDAY_AND_MONDAY_8H.isOpenAt(20 * HOUR), "lundi 20:00 hors plage");
    }

    @Test
    void theDefaultSetterCalendarIsExactlyMondayToWednesday() {
        // Le garde qui manquait. En généralisant le calendrier j'ai laissé une surcharge
        // int/long : `new WorkCalendar(3, ...)` — « les trois premiers jours » — a été résolu
        // vers le constructeur À MASQUE, donc 0b011 = lundi et mardi. Le metteur perdait son
        // mercredi, et le coût de l'instance de référence doublait sans qu'aucun test ne bouge.
        WorkCalendar setter = FullDataGenerator.generate(10, 1L).getSetterList().get(0)
                .getCalendar();
        for (int day = 0; day <= 2; day++) {
            assertTrue(setter.isOpenAt(day * DAY + 10 * HOUR),
                    "le metteur type doit travailler le jour " + day);
        }
        for (int day = 3; day <= 6; day++) {
            assertTrue(!setter.isOpenAt(day * DAY + 10 * HOUR),
                    "le metteur type ne doit PAS travailler le jour " + day);
        }
        assertEquals(3 * 8 * HOUR, setter.workedSecondsBefore(7 * DAY),
                "vingt-quatre heures ouvrées par semaine, pas seize");
    }

    @Test
    void theBlockedHoursAreChargedToTheMachineHourlyRate() {
        // Le mécanisme doit se retrouver dans le COÛT, pas seulement dans les dates : c'est
        // « heures machine perdues dans les trous du calendrier metteur × coût horaire machine »
        // de CPT-KKI-007. Une machine chère perd davantage à attendre son metteur.
        long idleSeconds = FRIDAY_AND_MONDAY_8H.idleDuring(3 * DAY, 16 * HOUR);
        long cheapMachine = CostModel.resourceCents(16 * HOUR, idleSeconds, 6_000L);
        long expensiveMachine = CostModel.resourceCents(16 * HOUR, idleSeconds, 15_000L);
        assertTrue(expensiveMachine > cheapMachine,
                "les heures bloquées doivent être facturées au tarif de LA machine bloquée");
        assertEquals(96L * 6_000L, cheapMachine - CostModel.resourceCents(16 * HOUR, 0L, 6_000L),
                "quatre-vingt-seize heures au tarif de la machine, exactement");
    }
}
