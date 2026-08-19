package kki.domain.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import kki.domain.Machine;
import kki.domain.Operation;
import kki.domain.Order;
import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * CPT-KKI-012 · V2 — l'instrument de mesure de marge doit être vrai avant que ses chiffres
 * servent à trancher H1 et H2. Deux angles complémentaires :
 *
 * <ul>
 * <li>différentiel — sa passe aval doit reproduire EXACTEMENT le score du calculateur de
 * production ({@code fullSweepScore()}), sinon les marges sont calculées sur des dates qui
 * ne sont pas celles du plan mesuré ;</li>
 * <li>valeurs calculées à la main — un cas minimal où la marge attendue est connue, y compris
 * la branche successeur-machine que le différentiel seul pourrait laisser silencieuse.</li>
 * </ul>
 */
class SlackReporterTest {

    @Test
    void forwardPassMatchesProductionFullSweepAtEveryScale() {
        for (int orderCount : new int[] { 50, 200, 800 }) {
            VerticalSliceSolution solution = naiveStart(orderCount, 60);
            VerticalSliceIncrementalScoreCalculator calculator = new VerticalSliceIncrementalScoreCalculator();
            calculator.resetWorkingSolution(solution);
            assertEquals(calculator.fullSweepScore().getSoftScore(), new SlackReporter(solution).softScore(),
                    "l'instrument date le plan autrement que le calculateur de production, à N=" + orderCount);
        }
    }

    @Test
    void slackToCompletionIsNeverNegative() {
        VerticalSliceSolution solution = naiveStart(300, 40);
        long[] slack = new SlackReporter(solution).slack(false);
        for (int i = 0; i < slack.length; i++) {
            assertTrue(slack[i] >= 0L,
                    "marge à la complétion négative sur l'opération " + i + " : le plan courant serait infaisable");
        }
    }

    @Test
    void lastOperationOfEachOrderHasZeroSlackToCompletion() {
        VerticalSliceSolution solution = naiveStart(300, 40);
        long[] slack = new SlackReporter(solution).slack(false);
        for (Order order : solution.getOrderList()) {
            List<Operation> operations = order.getOperations();
            Operation last = operations.get(operations.size() - 1);
            assertEquals(0L, slack[(int) last.getId()],
                    "la dernière opération d'un ordre est amorcée à sa propre date de fin : sa marge est 0 par"
                            + " construction (ordre " + order.getId() + ")");
        }
    }

    /**
     * Cas minimal à valeurs connues. Z occupe M1 pendant 100 s ; A doit y passer ensuite, donc
     * A attend — et c'est l'opération PRÉCÉDENTE de A qui hérite de la marge, pas celle qui
     * attend. C'est exactement le motif que l'élagage par marge (L3) doit détecter.
     *
     * <pre>
     *   M0   [a0 10s]································
     *   M1   [====== z0 100s ======][a1 10s]
     *        0         ...        100      110
     *
     *   marge(a0) = 90 s   — a0 peut glisser jusqu'à 90 s sans rien décaler
     *   marge(a1) = 0      — a1 démarre au plus tôt possible
     *   marge(z0) = 0      — z0 borne à la fois sa propre fin et le départ de a1
     * </pre>
     */
    @Test
    void slackAppearsBeforeAWaitAndAccountsForTheMachineSuccessor() {
        long origin = SyntheticDataGenerator.BASE_EPOCH;
        List<Machine> machines = List.of(new Machine(0L, 100L), new Machine(1L, 100L));

        Order z = new Order(0L, 0L, 1, origin + 100L);
        Operation z0 = new Operation(0L, z, 0, 100L, 1L);
        z.setOperations(List.of(z0));

        Order a = new Order(1L, 1L, 1, origin + 110L);
        Operation a0 = new Operation(1L, a, 0, 10L, 0L);
        Operation a1 = new Operation(2L, a, 1, 10L, 1L);
        a.setOperations(List.of(a0, a1));

        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>(List.of(z, a)));
        VerticalSliceSolution solution = new VerticalSliceSolution(
                List.of(z, a), List.of(z0, a0, a1), machines, List.of(schedule));

        long[] slack = new SlackReporter(solution).slack(false);
        assertEquals(0L, slack[(int) z0.getId()], "z0 borne sa propre fin ET le départ de a1");
        assertEquals(90L, slack[(int) a0.getId()], "a0 précède une attente : c'est lui qui porte la marge");
        assertEquals(0L, slack[(int) a1.getId()], "a1 démarre dès que M1 se libère");
    }

    /** Départ naïf : les ordres dans l'ordre de génération, comme le runner de mesure. */
    private static VerticalSliceSolution naiveStart(int orderCount, int machineCount) {
        VerticalSliceSolution generated = SyntheticDataGenerator.generate(orderCount, machineCount, 42L);
        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>(generated.getOrderList()));
        return new VerticalSliceSolution(generated.getOrderList(), generated.getOperationList(),
                generated.getMachineList(), List.of(schedule));
    }
}
