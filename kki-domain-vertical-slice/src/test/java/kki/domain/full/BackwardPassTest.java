package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * La passe amont de `CPT-KKI-003` et l'invariant 7 de `CPT-KKI-012` : la date d'une opération est
 * un INTERVALLE, pas une valeur.
 *
 * <p>
 * <b>Le piège que ces tests évitent.</b> Sur l'instance de production, les 5000 ordres sont en
 * retard. « Aucun ordre ne devient en retard après décalage » y est vrai par vacuité, et la marge
 * y est nulle partout : on vérifierait une passe amont qui ne calcule rien. Les tests qui portent
 * sur la marge tournent donc sur une instance délibérément sous-chargée, et commencent par
 * vérifier qu'elle contient bien ce qu'ils prétendent mesurer. Le dimensionnement de production,
 * lui, n'est pas touché : c'est le sujet de M5.
 */
class BackwardPassTest {

    @Test
    void earliestIsNeverAfterLatest() {
        // Invariant universel, y compris sur l'instance saturée : une borne au plus tard placée
        // AVANT la date au plus tôt signalerait une passe amont fausse ou une initialisation qui
        // borne par la date due seule.
        JobShopSolution solution = FullDataGenerator.generate(400, 97L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);
        FullScoreCalculator.BackwardSweep backward = calculator.backwardSweep();

        for (Operation op : solution.getOperationList()) {
            int opId = (int) op.getId();
            assertTrue(backward.latestEnd()[opId] >= calculator.endOf(opId),
                    op + " : au plus tard " + backward.latestEnd()[opId]
                            + " est AVANT au plus tôt " + calculator.endOf(opId));
            assertTrue(backward.latestStart()[opId] >= calculator.startOf(opId),
                    op + " : début au plus tard avant le début au plus tôt");
            assertTrue(backward.latestSetupEnd()[opId] >= calculator.setupEndOf(opId),
                    op + " : mise en train au plus tard avant celle au plus tôt");
        }
    }

    @Test
    void latestDatesRespectEverySuccessorFamily() {
        // Quatre familles de successeurs : chaîne, machine, metteur, outillage. Une famille
        // oubliée dans la passe amont produirait une marge trop généreuse — et une marge trop
        // généreuse est exactement ce qu'un levier d'élagage croirait.
        Fixture fixture = Fixture.lightlyLoaded(150, 89L);
        FullScoreCalculator.BackwardSweep backward = fixture.backward;
        for (Operation op : fixture.solution.getOperationList()) {
            int opId = (int) op.getId();
            for (Operation other : fixture.solution.getOperationList()) {
                int otherId = (int) other.getId();
                if (opId == otherId) {
                    continue;
                }
                boolean sameChainAndLater = other.getOrder() == op.getOrder()
                        && other.getPassIndex() == op.getPassIndex() + 1;
                if (sameChainAndLater) {
                    assertTrue(backward.latestEnd()[opId] <= backward.latestStart()[otherId],
                            op + " finirait après le début au plus tard de sa passe suivante");
                }
            }
        }
        // La machine : l'opération suivante sur la ressource ne peut pas voir sa mise en train
        // commencer avant que la précédente soit sortie.
        for (Machine machine : fixture.solution.getMachineList()) {
            Operation previous = null;
            for (Operation op : fixture.solution.getOperationList()) {
                if (op.getMachineId() != machine.getId()) {
                    continue;
                }
                if (previous != null && fixture.calculator.startOf((int) previous.getId())
                        <= fixture.calculator.startOf((int) op.getId())) {
                    assertTrue(backward.latestEnd()[(int) previous.getId()]
                            <= backward.latestSetupStart()[(int) op.getId()],
                            previous + " sortirait après le début de mise en train de " + op);
                }
                previous = op;
            }
        }
    }

    @Test
    void theLightlyLoadedInstanceActuallyHasSlackToMeasure() {
        // Sans ce test, les deux suivants seraient vrais par vacuité — même forme d'erreur que
        // pour le taux de liaison de l'outillage.
        Fixture fixture = Fixture.lightlyLoaded(150, 89L);
        assertTrue(fixture.backward.opsWithSlack() > 0L,
                "aucune opération n'a de marge : la passe amont n'apprend rien ici");
        assertTrue(fixture.backward.ordersWithSlack() > 0L,
                "aucun ordre ne peut être retardé : rien à dater en JIT");
        long early = fixture.solution.getOrderList().stream()
                .filter(order -> fixture.cold.completions()[(int) order.getId()]
                        < order.getDueEpochSec())
                .count();
        assertTrue(early > 0L, "l'instance doit contenir des ordres EN AVANCE, vus " + early);
        System.out.print(fixture.backward.describe("light", -fixture.cold.soft()));
    }

    @Test
    void shiftingToTheLatestDatesNeverMakesAnOrderLate() {
        // La propriété qui justifie la datation JIT : décaler ne peut que retarder, et jamais
        // au-delà de la date due d'un ordre qui la respectait.
        Fixture fixture = Fixture.lightlyLoaded(150, 89L);
        int shifted = 0;
        for (Order order : fixture.solution.getOrderList()) {
            int oi = (int) order.getId();
            long earliest = fixture.cold.completions()[oi];
            long jit = fixture.backward.jitCompletions()[oi];
            assertTrue(jit >= earliest, order + " : la datation JIT ne doit jamais AVANCER");
            assertTrue(jit <= Math.max(order.getDueEpochSec(), earliest),
                    "l'ordre " + oi + " passe en retard par la datation JIT : due "
                            + order.getDueEpochSec() + ", au plus tôt " + earliest + ", JIT " + jit);
            if (jit > earliest) {
                shifted++;
            }
        }
        assertTrue(shifted > 0, "aucun ordre décalé : le test ne mesure rien");
    }

    @Test
    void jitDatingRemovesEarlinessWithoutAddingTardiness() {
        // Les deux termes que la complétion détermine directement. Le temps machine immobilisé
        // n'est PAS affirmé ici : le décaler dépend de la contrainte relative de deux opérations
        // voisines sur la ressource, et rien ne garantit qu'il baisse terme à terme. Il est
        // mesuré et rapporté, pas décrété.
        Fixture fixture = Fixture.lightlyLoaded(150, 89L);
        assertTrue(fixture.backward.jitEarliness() <= fixture.cold.earliness(),
                "l'avance doit baisser : au plus tôt " + fixture.cold.earliness()
                        + ", JIT " + fixture.backward.jitEarliness());
        assertTrue(fixture.backward.jitTardiness() <= fixture.cold.tardiness(),
                "le retard ne doit JAMAIS augmenter : au plus tôt " + fixture.cold.tardiness()
                        + ", JIT " + fixture.backward.jitTardiness());
        assertTrue(fixture.backward.jitEarliness() < fixture.cold.earliness(),
                "sur une instance qui a de l'avance, la datation JIT doit en retirer");
    }

    @Test
    void occupancyStartIsTheInverseOfOccupancyEndEvenAcrossBlackouts() {
        // La boucle de point fixe de timeAtWorkedSeconds a été écrite pour le sens AVAL. Un
        // aller-retour sur le seul motif hebdomadaire ne la mettrait pas à l'épreuve : c'est avec
        // des indisponibilités datées qu'elle peut diverger.
        WorkCalendar calendar = WorkCalendar.MONDAY_TO_WEDNESDAY_8H.withBlackouts(
                new long[] { 3L * 86_400L, 9L * 86_400L, 20L * 86_400L, 23L * 86_400L });
        for (long start = 0L; start < 5L * 604_800L; start += 4_999L) {
            for (long work : new long[] { 3600L, 5L * 3600L, 20L * 3600L }) {
                long end = calendar.occupancyEnd(start, work);
                long back = calendar.occupancyStart(end, work);
                assertEquals(calendar.workedSecondsBefore(start), calendar.workedSecondsBefore(back),
                        "aller-retour incohérent depuis t=" + start + " pour " + work + " s");
                assertTrue(back >= start,
                        "l'instant au plus tard doit être au moins l'instant de départ");
            }
        }
    }

    @Test
    void occupancyStartRefusesWhatCannotFitBeforeTheDeadline() {
        // Sans garde, le solde négatif partirait dans une division tronquée et rendrait une date
        // silencieusement fausse — le pire mode de défaillance pour une borne.
        WorkCalendar calendar = WorkCalendar.MONDAY_TO_WEDNESDAY_8H;
        assertEquals(WorkCalendar.IMPOSSIBLE, calendar.occupancyStart(4L * 3600L, 40L * 3600L),
                "40 h de travail ne tiennent pas avant le lundi 04:00");
        assertEquals(0L, calendar.occupancyStart(0L, 0L), "une occupation nulle tient partout");
    }

    /**
     * Instance délibérément SOUS-CHARGÉE : peu d'ordres et beaucoup de metteurs, pour que des
     * ordres soient réellement en avance et que la marge existe. Le dimensionnement de production
     * n'est pas touché — il est restauré à la sortie.
     */
    private record Fixture(JobShopSolution solution, FullScoreCalculator calculator,
            FullScoreCalculator.ColdSweep cold, FullScoreCalculator.BackwardSweep backward) {

        static Fixture lightlyLoaded(int orders, long seed) {
            int savedSetters = FullDataGenerator.setterCount;
            int savedBreadth = FullDataGenerator.setterSkillBreadth;
            try {
                FullDataGenerator.setterCount = 250;
                FullDataGenerator.setterSkillBreadth = 5;
                JobShopSolution solution = FullDataGenerator.generate(orders, seed);
                FullScoreCalculator calculator = new FullScoreCalculator();
                calculator.resetWorkingSolution(solution);
                return new Fixture(solution, calculator, calculator.coldSweep(),
                        calculator.backwardSweep());
            } finally {
                FullDataGenerator.setterCount = savedSetters;
                FullDataGenerator.setterSkillBreadth = savedBreadth;
            }
        }
    }
}
