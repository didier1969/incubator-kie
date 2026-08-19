package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

/**
 * Le domaine complet n'a de valeur que si son score est juste. Trois angles :
 *
 * <ul>
 * <li><b>différentiel</b> — après chaque mouvement d'une séquence aléatoire, le score
 * incrémental doit égaler EXACTEMENT le balayage à froid. C'est le seul test capable de
 * détecter un nœud sale oublié, et il diverge dès le premier mouvement fautif ;</li>
 * <li><b>calendrier</b> — valeurs calculées à la main, parce qu'une erreur de fenêtre metteur
 * fausserait tout le terme de coût machine sans que le différentiel s'en aperçoive ;</li>
 * <li><b>invariants du domaine</b> — l'instance porte réellement les six mécanismes, sinon on
 * mesurerait encore un modèle réduit en croyant le contraire.</li>
 * </ul>
 */
class FullScoreCalculatorTest {

    @org.junit.jupiter.api.BeforeEach
    void resetDomainParameters() {
        // Les dimensions du domaine sont des statiques mutables partagés par toute la JVM de
        // test. Repartir du référentiel avant CHAQUE test évite qu'un montage paramétré ayant
        // levé avant sa restauration ne fasse mesurer un autre modèle à ceux qui le suivent.
        FullDataGenerator.reset();
    }

    @Test
    void incrementalScoreMatchesFullSweepAfterRandomSwaps() {
        JobShopSolution solution = FullDataGenerator.generate(120, 7L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);
        assertEquals(calculator.fullSweepScore(), calculator.calculateScore(),
                "l'état initial doit déjà coïncider avec l'oracle");

        List<Order> sequence = solution.getScheduleList().get(0).getOrderSequence();
        Random random = new Random(11L);
        for (int move = 0; move < 60; move++) {
            int a = random.nextInt(sequence.size());
            int b = random.nextInt(sequence.size());
            if (a == b) {
                continue;
            }
            int from = Math.min(a, b);
            int to = Math.max(a, b) + 1;
            calculator.beforeListVariableChanged(null, "orderSequence", from, to);
            Collections.swap(sequence, a, b);
            calculator.afterListVariableChanged(null, "orderSequence", from, to);
            assertEquals(calculator.fullSweepScore(), calculator.calculateScore(),
                    "divergence au mouvement " + move + " (échange des positions " + a + " et " + b + ")");
        }
    }

    @Test
    void setupWaitsForTheSetterWindowAndTheMachineWaitsWithIt() {
        // Origine = lundi 00:00. Une mise en train d'une heure demandée à minuit ne peut pas
        // commencer avant 08:00 : le metteur n'est pas là. La machine est immobilisée les
        // 8 heures d'attente PLUS l'heure de travail, mais seule l'heure de travail est du
        // temps metteur — c'est exactement le trou que PIL-KKI-004 fait payer.
        long oneHour = 3600L;
        assertEquals(9L * oneHour, SetterCalendar.setupEnd(0L, oneHour),
                "fin de mise en train = 08:00 + 1 h");
        assertEquals(8L * oneHour, SetterCalendar.machineIdleDuringSetup(0L, oneHour),
                "8 heures de machine immobilisée pour 1 heure de metteur");

        // Mercredi 15:00 + 4 h de metteur : il ne reste qu'une heure de fenêtre cette semaine,
        // les 3 h restantes basculent au lundi suivant 08:00.
        long wednesday15h = 2L * 86_400L + 15L * oneHour;
        long expected = 7L * 86_400L + 8L * oneHour + 3L * oneHour;
        assertEquals(expected, SetterCalendar.setupEnd(wednesday15h, 4L * oneHour),
                "la fenêtre metteur se ferme mercredi 16:00 et rouvre lundi 08:00");
    }

    @Test
    void workingTimeTransformIsItsOwnInverse() {
        for (long time = 0L; time < 3L * 604_800L; time += 997L) {
            long worked = SetterCalendar.workedSecondsBefore(time);
            long back = SetterCalendar.workedSecondsBefore(SetterCalendar.timeAtWorkedSeconds(worked));
            assertEquals(worked, back,
                    "aller-retour temps réel / temps ouvré incohérent à t=" + time);
        }
    }

    @Test
    void setupIsFreeOnlyWhenNOTHINGChanges() {
        // Ce test encodait l'inverse avant l'audit REQ-KKI-015 : il affirmait que deux passes du
        // même article étaient gratuites. CPT-KKI-006 dit explicitement le contraire — « pas
        // passe-à-passe du même article ». Un test qui encode une violation la protège.
        SetupMatrix matrix = new SetupMatrix(20, 6, 3L);
        assertEquals(0L, matrix.secondsBetween(matrix.keyOf(7, 2), matrix.keyOf(7, 2)),
                "rien ne change : aucune mise en train");
        assertTrue(matrix.secondsBetween(matrix.keyOf(7, 0), matrix.keyOf(7, 3)) > 0L,
                "une autre passe du même article demande une VRAIE mise en train (CPT-KKI-006)");
        assertTrue(matrix.secondsBetween(matrix.keyOf(7, 0), matrix.keyOf(8, 0)) > 0L,
                "changer d'article coûte");
    }

    @Test
    void setupDurationsMatchTheObservedRange() {
        // CPT-KKI-006 : 2 h minimum, 48 h maximum (rare), 16 h le cas courant. Le générateur
        // précédent tirait 10 min à 1 h 40 — un ordre de grandeur trop court, ce qui vidait de
        // son sens le piège du calendrier metteur.
        SetupMatrix matrix = new SetupMatrix(40, 6, 11L);
        List<Long> durations = new java.util.ArrayList<>();
        for (int from = 0; from < 240; from++) {
            for (int to = 0; to < 240; to++) {
                if (from != to) {
                    durations.add(matrix.secondsBetween(from, to));
                }
            }
        }
        java.util.Collections.sort(durations);
        long median = durations.get(durations.size() / 2);
        assertTrue(durations.get(0) >= 2 * 3600L, "plancher 2 h, mesuré " + durations.get(0));
        assertTrue(durations.get(durations.size() - 1) <= 48 * 3600L,
                "plafond 48 h, mesuré " + durations.get(durations.size() - 1));
        assertTrue(median >= 14 * 3600L && median <= 18 * 3600L,
                "le cas courant doit être autour de 16 h, médiane mesurée " + median / 3600.0 + " h");
        long heavy = durations.stream().filter(d -> d > 24 * 3600L).count();
        assertTrue(100.0 * heavy / durations.size() < 8.0,
                "les mises en train de plus de 24 h doivent rester rares, mesuré "
                        + (100.0 * heavy / durations.size()) + " %");
    }

    @Test
    void setupMatrixIsAsymmetric() {
        SetupMatrix matrix = new SetupMatrix(50, 6, 5L);
        int asymmetricPairs = 0;
        for (int article = 0; article < 40; article++) {
            long forward = matrix.secondsBetween(matrix.keyOf(article, 0), matrix.keyOf(article + 1, 0));
            long backward = matrix.secondsBetween(matrix.keyOf(article + 1, 0), matrix.keyOf(article, 0));
            if (forward != backward) {
                asymmetricPairs++;
            }
        }
        assertTrue(asymmetricPairs > 30,
                "la matrice doit être franchement asymétrique, paires asymétriques : " + asymmetricPairs);
    }

    @Test
    void instanceCarriesAllSixMechanisms() {
        JobShopSolution solution = FullDataGenerator.generate(400, 13L);

        long revisits = solution.getOrderList().stream()
                .filter(order -> order.getOperations().stream()
                        .map(Operation::getMachineId).distinct().count() < order.getOperations().size())
                .count();
        assertTrue(revisits > 50, "axe Z : des ordres doivent repasser sur une même machine, vus " + revisits);

        assertTrue(solution.getOrderList().stream()
                .anyMatch(o -> o.getFreezeLevel() == Order.FreezeLevel.HARD), "gel dur présent");
        assertTrue(solution.getOrderList().stream()
                .anyMatch(o -> o.getFreezeLevel() == Order.FreezeLevel.SOFT), "gel souple présent");
        assertTrue(solution.getOrderList().stream()
                .anyMatch(o -> o.getFreezeLevel() == Order.FreezeLevel.FREE), "ordres libres présents");

        assertTrue(solution.getMachineList().stream().mapToLong(Machine::getHourlyCostCents)
                .distinct().count() >= 10, "l'échelle de coût horaire doit compter 10 paliers");

        for (Operation op : solution.getOperationList()) {
            Machine machine = solution.getMachineList().get((int) op.getMachineId());
            assertTrue(machine.canRun(op.getRequiredTechnology(), op.getRequiredLevel()),
                    "compatibilité violée : " + op + " sur " + machine);
        }
    }

    @Test
    void hardFreezeViolationsLandOnTheHardScoreNotTheSoftOne() {
        JobShopSolution solution = FullDataGenerator.generate(200, 17L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);
        HardSoftLongScore score = calculator.fullSweepScore();
        assertTrue(score.getHardScore() < 0L,
                "des ordres à verrou dur sont déplacés par rapport au plan de référence :"
                        + " la violation doit peser sur le DUR, mesuré " + score.getHardScore());
    }
}
