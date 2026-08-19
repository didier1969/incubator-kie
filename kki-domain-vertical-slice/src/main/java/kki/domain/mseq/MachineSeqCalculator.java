package kki.domain.mseq;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.calculator.EasyScoreCalculator;

import kki.domain.full.CostModel;
import kki.domain.full.Machine;
import kki.domain.full.Operation;
import kki.domain.full.Order;
import kki.domain.full.SetterCalendar;
import kki.domain.full.SetupMatrix;

/**
 * Score de la représentation par séquence machine.
 *
 * <p>
 * <b>Pourquoi un balayage complet et non un calcul incrémental.</b> En représentation X, l'ordre
 * topologique du graphe était connu d'avance — (position X, passe) — ce qui permettait de finaliser
 * chaque nœud dès son premier dépilement. Ici les séquences sont choisies ressource par ressource :
 * il n'existe plus d'ordre topologique gratuit, et il peut même n'en exister AUCUN. Un calcul
 * incrémental correct exigerait de maintenir cet ordre à chaque mouvement, ce qui est précisément
 * le travail que la représentation X offrait gratuitement. Le balayage par relaxation ci-dessous
 * est donc la mesure honnête du coût de cette représentation, pas un raccourci : il montre ce que
 * la liberté de séquencement se paie.
 *
 * <p>
 * <b>L'acyclicité n'est plus gratuite.</b> Deux ressources peuvent se contredire — A avant B ici,
 * B avant A là — et produire un plan que rien ne peut ordonnancer. Ce n'est pas un plan cher,
 * c'est un plan impossible : il pèse sur le score DUR. La détection se fait par comptage des nœuds
 * finalisés (Kahn) : si tous ne le sont pas, ceux qui restent sont dans un cycle.
 */
public final class MachineSeqCalculator implements EasyScoreCalculator<MachineSeqSolution, HardSoftLongScore> {

    public static final AtomicLong CALCULATE_SCORE_CALLS = new AtomicLong();
    /** Nombre d'évaluations ayant rencontré un cycle — un plan impossible. */
    public static final AtomicLong CYCLIC_EVALUATIONS = new AtomicLong();

    @Override
    public HardSoftLongScore calculateScore(MachineSeqSolution solution) {
        CALCULATE_SCORE_CALLS.incrementAndGet();

        List<Operation> operations = solution.getOperationList();
        int opCount = operations.size();
        SetupMatrix setupMatrix = solution.getSetupMatrix();
        long origin = solution.getOriginEpochSec();

        Operation[] opById = new Operation[opCount];
        for (Operation op : operations) {
            opById[(int) op.getId()] = op;
        }

        // Prédécesseurs : la chaîne de l'ordre, et la file de la ressource.
        int[] chainPredecessor = new int[opCount];
        int[] machinePredecessor = new int[opCount];
        int[] machineOf = new int[opCount];
        int[] inDegree = new int[opCount];
        Arrays.fill(chainPredecessor, -1);
        Arrays.fill(machinePredecessor, -1);
        Arrays.fill(machineOf, -1);

        int orderCount = solution.getOrderList().size();
        int[][] byOrderAndPass = new int[orderCount][8];
        for (int[] row : byOrderAndPass) {
            Arrays.fill(row, -1);
        }
        for (Operation op : operations) {
            byOrderAndPass[(int) op.getOrder().getId()][op.getPassIndex()] = (int) op.getId();
        }
        int[] lastOpIdOfOrder = new int[orderCount];
        Arrays.fill(lastOpIdOfOrder, -1);
        for (int o = 0; o < orderCount; o++) {
            int previous = -1;
            for (int pass = 0; pass < 8; pass++) {
                int current = byOrderAndPass[o][pass];
                if (current < 0) {
                    break;
                }
                if (previous >= 0) {
                    chainPredecessor[current] = previous;
                    inDegree[current]++;
                }
                previous = current;
                lastOpIdOfOrder[o] = current;
            }
        }

        long[] machineHourlyCents = new long[solution.getMachineList().size()];
        for (Machine machine : solution.getMachineList()) {
            machineHourlyCents[(int) machine.getId()] = machine.getHourlyCostCents();
        }

        long hard = 0L;
        for (MachineSequence sequence : solution.getSequenceList()) {
            int m = (int) sequence.getMachine().getId();
            int previous = -1;
            for (Operation op : sequence.getOperations()) {
                int current = (int) op.getId();
                machineOf[current] = m;
                if (previous >= 0) {
                    machinePredecessor[current] = previous;
                    inDegree[current]++;
                }
                previous = current;
                // La compatibilité ascendante est portée par les mouvements ; ce qui passerait
                // quand même est refusé ici, pas absorbé silencieusement dans le coût.
                if (!sequence.getMachine().canRun(op.getRequiredTechnology(), op.getRequiredLevel())) {
                    hard -= 1_000_000L;
                }
            }
        }

        // Relaxation en ordre topologique (Kahn). Chaque nœud est daté une seule fois, dès que ses
        // deux prédécesseurs possibles sont datés.
        long[] opEnd = new long[opCount];
        long soft = 0L;
        Deque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < opCount; i++) {
            if (inDegree[i] == 0) {
                ready.add(i);
            }
        }
        List<Integer> chainSuccessorsOf = new ArrayList<>();
        int[] chainSuccessor = new int[opCount];
        int[] machineSuccessor = new int[opCount];
        Arrays.fill(chainSuccessor, -1);
        Arrays.fill(machineSuccessor, -1);
        for (int i = 0; i < opCount; i++) {
            if (chainPredecessor[i] >= 0) {
                chainSuccessor[chainPredecessor[i]] = i;
            }
            if (machinePredecessor[i] >= 0) {
                machineSuccessor[machinePredecessor[i]] = i;
            }
        }
        chainSuccessorsOf.clear();

        // Fin de la file de chaque ressource, et clé de mise en train du dernier passage.
        long[] machineFree = new long[machineHourlyCents.length];
        int[] lastKeyOnMachine = new int[machineHourlyCents.length];
        Arrays.fill(machineFree, origin);
        Arrays.fill(lastKeyOnMachine, -1);

        int settled = 0;
        while (!ready.isEmpty()) {
            int opId = ready.poll();
            settled++;
            Operation op = opById[opId];
            int m = machineOf[opId];
            int predecessor = machinePredecessor[opId];
            long machineFreeAt = predecessor >= 0 ? opEnd[predecessor] : origin;
            long setupSeconds = predecessor >= 0
                    ? setupMatrix.secondsBetween(opById[predecessor].getSetupKey(), op.getSetupKey())
                    : setupMatrix.coldStartSeconds(op.getSetupKey());
            long setupEnd = SetterCalendar.setupEnd(machineFreeAt, setupSeconds);
            long machineIdle = setupEnd - machineFreeAt - setupSeconds;
            long chainReadyAt = chainPredecessor[opId] >= 0 ? opEnd[chainPredecessor[opId]] : origin;
            long start = Math.max(setupEnd, chainReadyAt);
            opEnd[opId] = start + op.getDurationSeconds();
            soft -= CostModel.resourceCents(setupSeconds, machineIdle, machineHourlyCents[m]);

            int chainNext = chainSuccessor[opId];
            if (chainNext >= 0 && --inDegree[chainNext] == 0) {
                ready.add(chainNext);
            }
            int machineNext = machineSuccessor[opId];
            if (machineNext >= 0 && --inDegree[machineNext] == 0) {
                ready.add(machineNext);
            }
        }

        if (settled < opCount) {
            // Cycle : deux ressources se contredisent, le plan n'est pas ordonnançable.
            //
            // La première version rendait ici `soft` tel quel — c'est-à-dire le coût des seules
            // opérations datées, sans aucun coût d'ordre puisque la boucle des ordres n'était
            // jamais atteinte. Le solveur l'a exploité immédiatement : rendre le plan IMPOSSIBLE
            // faisait tomber le coût souple à 0,6 % du départ, et la pénalité dure d'alors
            // (1 000 000 par opération non datée) restait inférieure aux violations de gel d'un
            // plan faisable. Résultat mesuré : 97,7 % d'évaluations cycliques et une « réduction
            // de 100 % » entièrement fictive.
            //
            // Un plan impossible doit être PIRE QUE TOUT plan faisable, sans arithmétique à faire
            // confiance : le plancher ci-dessous est plusieurs ordres de grandeur au-delà de toute
            // violation de gel atteignable, et le souple est neutralisé pour qu'il ne puisse plus
            // servir de récompense.
            CYCLIC_EVALUATIONS.incrementAndGet();
            return HardSoftLongScore.of(
                    -1_000_000_000_000_000L - (long) (opCount - settled) * 1_000_000L, 0L);
        }

        for (Order order : solution.getOrderList()) {
            long completion = opEnd[lastOpIdOfOrder[(int) order.getId()]];
            soft -= CostModel.orderCents(order, completion);
            hard -= CostModel.hardViolation(order, completion);
        }
        return HardSoftLongScore.of(hard, soft);
    }
}
