package kki.domain.solver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

import kki.bench.CostKernel;
import kki.domain.Operation;
import kki.domain.Order;
import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * REQ-KKI-006 (réécriture incrémentale) — état persistant (opStart/opEnd par
 * opération, chaîne machine explicite prevOnMachine/nextOnMachine, coût par
 * ordre) au lieu d'un balayage complet à chaque calculateScore(). Sur un
 * changement de Schedule.orderSequence, seule la zone touchée est détachée
 * puis rattachée — le voisin machine se trouve en indexant DIRECTEMENT
 * schedule.getOrderSequence() de part et d'autre de la zone (la liste est
 * déjà mutée au moment des hooks after*), puis propagation par liste de
 * nœuds sales jusqu'à convergence.
 * Recherche de voisin machine : operationsByMachine (groupement STATIQUE,
 * construit une fois — l'affectation machine est un fait fixe dans cette
 * tranche, CPT-KKI-008 différé) + xPosition (position X courante par ordre,
 * tenue à jour sur chaque appel). Trouver le prédécesseur/successeur machine
 * d'une opération devient un scan de operationsByMachine[machineId] (taille
 * ~opCount/machineCount, ex. ~22 à N=5000) au lieu d'un parcours de la
 * séquence globale (taille N) — mesuré : marcher la séquence globale était
 * le vrai goulot (walk O(N) par attache, pas la structure worklist/delta
 * elle-même), cf. corps REQ-KKI-006.
 * Toute la logique passe par before/afterListVariableChanged UNIQUEMENT —
 * vérifié dans le code source d'OptaPlanner (ListAssignMove, ListChangeMove,
 * ListSwapMove, ListUnassignMove, SubListChangeMove) : les 5 encadrent
 * TOUJOURS leur mutation par un couple beforeListVariableChanged(...,
 * fromIndex, toIndex) / afterListVariableChanged(..., fromIndex, toIndex),
 * y compris ListAssignMove/ListUnassignMove qui appellent AUSSI
 * before/afterListVariableElementAssigned|Unassigned — mais ceux-ci restent
 * sans effet ici (défauts no-op de l'interface, non surchargés) : le couple
 * Changed seul suffit, avec fromIndex==toIndex pour "rien à détacher" ou
 * "rien de nouveau à attacher".
 * ATTENTION — piège vérifié empiriquement (N=100 réel donnait score=0) :
 * Order.previousOrderInSequence/nextOrderInSequence (shadow variables)
 * NE SONT PAS fiables au moment où ces hooks courent. En lisant
 * IncrementalScoreDirector + VariableListenerSupport : le score-calculator
 * est notifié DIRECTEMENT depuis before/afterListVariableChanged, alors que
 * la mise à jour des shadow variables est seulement MISE EN FILE à ce
 * moment-là et n'est appliquée que plus tard, à
 * triggerVariableListenersInNotificationQueues(). D'où l'abandon total des
 * shadow variables ici (Order n'est plus @PlanningEntity) au profit d'un
 * parcours par INDEX sur la liste, seule source vraiment à jour.
 * Façon Bellman-Ford sur un DAG : l'ordre de traitement du worklist est
 * indifférent — un nœud dépilé avant qu'un prédécesseur ait convergé donne
 * une valeur transitoire, mais tout changement ré-enfile les successeurs,
 * donc calculateScore() n'est interrogé qu'après un worklist vide, qui
 * garantit le point fixe correct (unique sur un DAG acyclique).
 * Pire cas toujours O(N) (un ordre déplacé en tête peut redater tout le
 * reste, cf. PIL-KKI-003) — le gain se mesure sur le cas courant (mouvement
 * local de recherche locale), jamais supposé.
 * Les ids Order/Operation DOIVENT être denses 0..count-1 (invariant de
 * SyntheticDataGenerator) — indexation directe par tableau, pas de
 * Map&lt;Long,?&gt; sur le chemin chaud (c'est tout l'objet de PIL-KKI-003).
 * fullSweepScore() reste l'oracle de non-régression (jamais supprimé,
 * jamais appelé par calculateScore() en production).
 */
public class VerticalSliceIncrementalScoreCalculator
        implements IncrementalScoreCalculator<VerticalSliceSolution, HardSoftLongScore> {

    private static final float K = 5f;

    /** Compteur d'appels — mesure IPS (REQ-KKI-006). */
    public static final AtomicLong CALCULATE_SCORE_CALLS = new AtomicLong();

    /**
     * Horodatage du PREMIER calculateScore() depuis le dernier reset de
     * CALCULATE_SCORE_CALLS (REQ-KKI-008, discriminant propagation vs
     * construction de sélecteur de mouvement) — permet à l'appelant de
     * calculer combien de temps s'est écoulé avant le premier appel réel,
     * séparément du temps passé dans les calculateScore() eux-mêmes.
     */
    public static final AtomicLong FIRST_CALL_NANOS = new AtomicLong(0L);

    /**
     * Somme du temps passé DANS afterListVariableChanged (détache/resync/
     * rattache/propagate — le vrai travail incrémental ; calculateScore()
     * lui-même est O(1), il relit juste softScoreTotal). REQ-KKI-008,
     * discriminant propagation-calculateur vs machinerie de sélection de
     * mouvement d'OptaPlanner autour de l'appel : comparé au temps mur total
     * d'une phase par l'appelant, si très inférieur, le coût est ailleurs
     * (sélection de mouvement list-variable côté OptaPlanner), pas dans la
     * propagation elle-même. Mesuré une fois (N=5000, REQ-KKI-008) :
     * propagation_pct=98.6% — le coût EST dans ce hook, pas autour.
     * Instrumentation PERMANENTE (deux nanoTime() par hook, &lt;1% même au
     * régime le plus rapide observé — CH à 0.19ms/appel) : garder pour toute
     * future régression de performance, pas du code d'appoint à retirer.
     */
    public static final AtomicLong PROPAGATION_NANOS = new AtomicLong(0L);

    private VerticalSliceSolution solution;
    private Schedule schedule;
    private long scheduleOrigin;

    private long[] opStart;
    private long[] opEnd;
    private Operation[] prevOnMachine;
    private Operation[] nextOnMachine;
    private long[] orderCost;
    private boolean[] queued;
    private long softScoreTotal;
    private int[] xPosition;
    private List<Operation>[] operationsByMachine;

    private final ArrayDeque<Operation> worklist = new ArrayDeque<>();
    private List<Order> changedRangeBefore;

    @Override
    public void resetWorkingSolution(VerticalSliceSolution workingSolution) {
        this.solution = workingSolution;
        this.schedule = workingSolution.getScheduleList().get(0);
        int opCount = workingSolution.getOperationList().size();
        int orderCount = workingSolution.getOrderList().size();
        int machineCount = workingSolution.getMachineList().size();
        opStart = new long[opCount];
        opEnd = new long[opCount];
        prevOnMachine = new Operation[opCount];
        nextOnMachine = new Operation[opCount];
        orderCost = new long[orderCount];
        queued = new boolean[opCount];
        xPosition = new int[orderCount];
        // -1 = "pas encore placé dans orderSequence" (défaut 0 du tableau int[] serait
        // indiscernable d'une vraie position 0 — bug réel trouvé empiriquement : en
        // construction heuristique, la plupart des candidats de operationsByMachine[m]
        // appartiennent à des ordres pas encore placés, faussement matchés en position 0).
        Arrays.fill(xPosition, -1);
        scheduleOrigin = SyntheticDataGenerator.BASE_EPOCH;
        worklist.clear();
        buildOperationsByMachine(workingSolution, machineCount);
        fullRebuild();
    }

    @SuppressWarnings("unchecked")
    private void buildOperationsByMachine(VerticalSliceSolution workingSolution, int machineCount) {
        operationsByMachine = new List[machineCount];
        for (int m = 0; m < machineCount; m++) {
            operationsByMachine[m] = new ArrayList<>();
        }
        for (Operation op : workingSolution.getOperationList()) {
            operationsByMachine[(int) op.getMachineId()].add(op);
        }
    }

    private void fullRebuild() {
        Map<Long, Operation> machineTail = new HashMap<>();
        softScoreTotal = 0L;
        List<Order> sequence = schedule.getOrderSequence();
        for (int orderIndex = 0; orderIndex < sequence.size(); orderIndex++) {
            Order order = sequence.get(orderIndex);
            xPosition[(int) order.getId()] = orderIndex;
            long previousEndInOrder = scheduleOrigin;
            long lastEnd = scheduleOrigin;
            for (Operation op : order.getOperations()) {
                Operation machinePred = machineTail.get(op.getMachineId());
                long machinePredEnd = machinePred != null ? opEnd[idx(machinePred)] : scheduleOrigin;
                long start = Math.max(machinePredEnd, previousEndInOrder);
                long end = start + op.getDurationSeconds();
                opStart[idx(op)] = start;
                opEnd[idx(op)] = end;
                prevOnMachine[idx(op)] = machinePred;
                nextOnMachine[idx(op)] = null;
                if (machinePred != null) {
                    nextOnMachine[idx(machinePred)] = op;
                }
                machineTail.put(op.getMachineId(), op);
                previousEndInOrder = end;
                lastEnd = end;
            }
            long cost = computeOrderCost(order, lastEnd);
            orderCost[(int) order.getId()] = cost;
            softScoreTotal -= cost;
        }
    }

    /**
     * Oracle de non-régression : balayage complet à froid, indépendant de
     * l'état incrémental. Ne touche à aucun champ persistant.
     */
    HardSoftLongScore fullSweepScore() {
        Map<Long, Long> lastEndByMachine = new HashMap<>();
        long soft = 0L;
        for (Order order : schedule.getOrderSequence()) {
            long previousEndInOrder = scheduleOrigin;
            long lastOperationEnd = scheduleOrigin;
            for (Operation op : order.getOperations()) {
                long machinePredEnd = lastEndByMachine.getOrDefault(op.getMachineId(), scheduleOrigin);
                long start = Math.max(machinePredEnd, previousEndInOrder);
                long end = start + op.getDurationSeconds();
                lastEndByMachine.put(op.getMachineId(), end);
                previousEndInOrder = end;
                lastOperationEnd = end;
            }
            soft -= computeOrderCost(order, lastOperationEnd);
        }
        return HardSoftLongScore.of(0L, soft);
    }

    private static int idx(Operation op) {
        return (int) op.getId();
    }

    private long computeOrderCost(Order order, long completionEpochSec) {
        float hoursLateOrEarly = (completionEpochSec - order.getRequiredDueEpochSec()) / 3600f;
        float cost = CostKernel.cost(order.getPriorityWeight(), K, hoursLateOrEarly);
        return Math.round((double) cost);
    }

    private void enqueue(Operation op) {
        if (op != null && !queued[idx(op)]) {
            queued[idx(op)] = true;
            worklist.add(op);
        }
    }

    private Operation findMachinePredecessor(int strictlyBeforeIndex, long machineId) {
        Operation best = null;
        int bestPos = -1;
        int bestSeq = -1;
        for (Operation candidate : operationsByMachine[(int) machineId]) {
            int pos = xPosition[(int) candidate.getOrder().getId()];
            if (pos == -1 || pos > strictlyBeforeIndex) {
                continue;
            }
            int seq = candidate.getSequenceIndexInOrder();
            if (pos > bestPos || (pos == bestPos && seq > bestSeq)) {
                best = candidate;
                bestPos = pos;
                bestSeq = seq;
            }
        }
        return best;
    }

    private Operation findMachineSuccessor(int strictlyAfterIndex, long machineId) {
        Operation best = null;
        int bestPos = Integer.MAX_VALUE;
        int bestSeq = Integer.MAX_VALUE;
        for (Operation candidate : operationsByMachine[(int) machineId]) {
            int pos = xPosition[(int) candidate.getOrder().getId()];
            if (pos == -1 || pos < strictlyAfterIndex) {
                continue;
            }
            int seq = candidate.getSequenceIndexInOrder();
            if (pos < bestPos || (pos == bestPos && seq < bestSeq)) {
                best = candidate;
                bestPos = pos;
                bestSeq = seq;
            }
        }
        return best;
    }

    private void detachFromMachineChain(Operation op) {
        Operation pred = prevOnMachine[idx(op)];
        Operation succ = nextOnMachine[idx(op)];
        if (pred != null) {
            nextOnMachine[idx(pred)] = succ;
        }
        if (succ != null) {
            prevOnMachine[idx(succ)] = pred;
            enqueue(succ);
        }
        prevOnMachine[idx(op)] = null;
        nextOnMachine[idx(op)] = null;
    }

    private void attachToMachineChain(Operation op, int orderIndex) {
        Order order = op.getOrder();
        List<Operation> ops = order.getOperations();
        int i = op.getSequenceIndexInOrder();

        Operation pred = null;
        for (int j = i - 1; j >= 0 && pred == null; j--) {
            if (ops.get(j).getMachineId() == op.getMachineId()) {
                pred = ops.get(j);
            }
        }
        if (pred == null) {
            pred = findMachinePredecessor(orderIndex - 1, op.getMachineId());
        }

        Operation succ = null;
        for (int j = i + 1; j < ops.size() && succ == null; j++) {
            if (ops.get(j).getMachineId() == op.getMachineId()) {
                succ = ops.get(j);
            }
        }
        if (succ == null) {
            succ = findMachineSuccessor(orderIndex + 1, op.getMachineId());
        }

        prevOnMachine[idx(op)] = pred;
        nextOnMachine[idx(op)] = succ;
        if (pred != null) {
            nextOnMachine[idx(pred)] = op;
        }
        if (succ != null) {
            prevOnMachine[idx(succ)] = op;
            enqueue(succ);
        }
        enqueue(op);
    }

    private void propagate() {
        while (!worklist.isEmpty()) {
            Operation op = worklist.poll();
            queued[idx(op)] = false;

            Order order = op.getOrder();
            List<Operation> ops = order.getOperations();
            int i = op.getSequenceIndexInOrder();
            long orderPredEnd = i == 0 ? scheduleOrigin : opEnd[idx(ops.get(i - 1))];
            Operation machinePred = prevOnMachine[idx(op)];
            long machinePredEnd = machinePred != null ? opEnd[idx(machinePred)] : scheduleOrigin;

            long newStart = Math.max(orderPredEnd, machinePredEnd);
            long newEnd = newStart + op.getDurationSeconds();
            if (newStart == opStart[idx(op)] && newEnd == opEnd[idx(op)]) {
                continue;
            }
            opStart[idx(op)] = newStart;
            opEnd[idx(op)] = newEnd;

            if (i + 1 < ops.size()) {
                enqueue(ops.get(i + 1));
            } else {
                int oi = (int) order.getId();
                long newCost = computeOrderCost(order, newEnd);
                softScoreTotal += orderCost[oi];
                softScoreTotal -= newCost;
                orderCost[oi] = newCost;
            }
            enqueue(nextOnMachine[idx(op)]);
        }
    }

    @Override
    public void beforeEntityAdded(Object entity) {
    }

    @Override
    public void afterEntityAdded(Object entity) {
    }

    @Override
    public void beforeVariableChanged(Object entity, String variableName) {
    }

    @Override
    public void afterVariableChanged(Object entity, String variableName) {
    }

    @Override
    public void beforeListVariableChanged(Object entity, String variableName, int fromIndex, int toIndex) {
        changedRangeBefore = new ArrayList<>(schedule.getOrderSequence().subList(fromIndex, toIndex));
    }

    @Override
    public void afterListVariableChanged(Object entity, String variableName, int fromIndex, int toIndex) {
        long propagationStartNanos = System.nanoTime();
        for (Order order : changedRangeBefore) {
            for (Operation op : order.getOperations()) {
                detachFromMachineChain(op);
            }
        }
        List<Order> sequence = schedule.getOrderSequence();
        if (changedRangeBefore.size() != toIndex - fromIndex) {
            // Net insert/unassign (ListAssignMove/ListUnassignMove) : the index shift extends
            // past [fromIndex,toIndex) to the rest of the list, unlike same-size relocate/swap/
            // reversal moves where the span fully bounds it. Only construction heuristic hits
            // this branch here (every Order is mandatory, never unassigned again once placed).
            // Positions before fromIndex provably never shift — resync from fromIndex only, not 0.
            for (int i = fromIndex; i < sequence.size(); i++) {
                xPosition[(int) sequence.get(i).getId()] = i;
            }
        } else {
            for (int i = fromIndex; i < toIndex; i++) {
                xPosition[(int) sequence.get(i).getId()] = i;
            }
        }
        for (int i = fromIndex; i < toIndex; i++) {
            for (Operation op : sequence.get(i).getOperations()) {
                attachToMachineChain(op, i);
            }
        }
        propagate();
        changedRangeBefore = null;
        PROPAGATION_NANOS.addAndGet(System.nanoTime() - propagationStartNanos);
    }

    @Override
    public void beforeEntityRemoved(Object entity) {
    }

    @Override
    public void afterEntityRemoved(Object entity) {
    }

    @Override
    public HardSoftLongScore calculateScore() {
        if (CALCULATE_SCORE_CALLS.incrementAndGet() == 1L) {
            FIRST_CALL_NANOS.set(System.nanoTime());
        }
        return HardSoftLongScore.of(0L, softScoreTotal);
    }
}
