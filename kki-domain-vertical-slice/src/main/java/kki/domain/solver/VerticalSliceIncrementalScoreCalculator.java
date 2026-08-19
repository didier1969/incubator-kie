package kki.domain.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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
 * REQ-KKI-008 : détacher/rattacher TOUT le span [fromIndex,toIndex) sur un
 * mouvement même-taille (relocate/swap) mesurait 98.6% du temps mur de la
 * recherche locale (N=5000) alors qu'un seul ordre est réellement déplacé
 * dans un relocate, deux dans un swap — le reste du span n'a fait que
 * décaler de position sans que son adjacence machine change. Voir
 * findDisplacedOrders : ensemble minimal par comparaison position-à-
 * position, repli sûr sur tout le span pour toute forme non reconnue
 * (reversal, sous-liste). PROPAGATION_NANOS mesure le résultat en continu.
 * Mesuré ensuite (toujours REQ-KKI-008) : ce narrowing avait un effet NUL
 * sur propagation_pct/ls_ips — le vrai coût était le worklist lui-même
 * (ArrayDeque FIFO non trié topologiquement sur un DAG, ~70 relaxations
 * répétées par opération à N=5000, prouvé via PROPAGATE_POPS). Remplacé par
 * une PriorityQueue triée sur (xPosition, sequenceIndexInOrder) — voir le
 * commentaire du champ worklist pour la preuve + la vérification empirique
 * (TOPOLOGICAL_INVERSIONS, 0 sur ~350M+ arêtes). Résultat mesuré : à
 * N=5000, pops_per_call 1650463.8→1980.7 (~833×) ; à N=3000 (échelle
 * PIL-KKI-003), ls_ips 35.6→1354.9 (~38×, contre la cible 2000 IPS — pas
 * encore atteinte, mais l'écart passe de 56× à 1.48×). À N=200, régression
 * mineure de ls_ips
 * malgré moins de pops (surcoût constant O(log n) du tas face à un
 * ArrayDeque O(1), quand il n'y avait déjà presque rien à éliminer) — sans
 * conséquence, très au-dessus de la cible à cette échelle de toute façon.
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

    /**
     * Nombre total de noeuds dépilés du worklist (REQ-KKI-008, discriminant
     * détachement/rattachement vs cascade propagate() elle-même) — si ce
     * nombre reste élevé malgré un ensemble déplacé réduit (findDisplacedOrders),
     * le coût est dans l'étendue de la cascade Bellman-Ford, pas dans le
     * volume détaché/rattaché.
     */
    public static final AtomicLong PROPAGATE_POPS = new AtomicLong(0L);

    /**
     * REQ-KKI-008, vérification AVANT d'adopter une PriorityQueue(xPosition,
     * sequenceIndexInOrder) comme ordre de dépilement : compte les paires
     * (op, nextOnMachine[op]) où xPosition[successeur] &lt; xPosition[op] —
     * une INVERSION signifierait que (xPosition, sequenceIndexInOrder) n'est
     * PAS un ordre topologique valide sur le graphe VIVANT (detachFromMachineChain
     * épisse pred→succ sans revérifier leurs positions), auquel cas une
     * PriorityQueue sur cette clé resterait une heuristique, pas une garantie
     * de finalisation en un seul dépilement par nœud.
     */
    public static final AtomicLong TOPOLOGICAL_INVERSIONS = new AtomicLong(0L);

    /**
     * REQ-KKI-006, diagnostic V_dirty : incrémenté UNIQUEMENT quand un
     * dépilement produit un changement réel de (opStart, opEnd) — jamais sur
     * le cas no-op (continue de propagate()). Discriminant : si
     * PROPAGATE_DIRTY_POPS ≈ PROPAGATE_POPS, la propagation ne gaspille pas
     * de dépilements (déjà quasi-optimale) ; si très inférieur, il reste un
     * levier algorithmique dans propagate() lui-même, distinct du levier
     * d'ordre de dépilement déjà exploité par REQ-KKI-008.
     */
    public static final AtomicLong PROPAGATE_DIRTY_POPS = new AtomicLong(0L);

    /**
     * REQ-KKI-006, diagnostic span : somme de (toIndex - fromIndex) sur
     * chaque appel afterListVariableChanged — mesure l'étendue réelle des
     * mouvements essayés par la recherche locale, pas la propagation
     * elle-même. Discriminant : un span moyen grand à N=3000 pointerait vers
     * un move selector borné/local côté configuration OptaPlanner comme
     * levier — un levier absent des pistes REQ-KKI-006/008 déjà explorées.
     */
    public static final AtomicLong MOVE_SPAN_TOTAL = new AtomicLong(0L);

    /**
     * REQ-KKI-006, diagnostic span : nombre d'appels afterListVariableChanged
     * — dénominateur propre pour MOVE_SPAN_TOTAL (mean_span_per_move), sert
     * aussi de vérification croisée contre CALCULATE_SCORE_CALLS (do/undo
     * de mouvement rejeté n'appelle pas forcément calculateScore()).
     */
    public static final AtomicLong PROPAGATION_CALLS = new AtomicLong(0L);

    /**
     * REQ-KKI-007 piste (d) : reference LIVE vers xPosition, exposee pour
     * OrderPositionNearbyDistanceMeter (nearby selection cote
     * LocalSearchPhaseConfig, VerticalSliceRunner). xPosition n'est jamais
     * REASSIGNE hors resetWorkingSolution (seulement mute en place ensuite),
     * donc cette reference reste a jour sans resynchronisation explicite.
     * Couplage assume, diagnostic de piste (d), revertible.
     */
    public static volatile int[] LIVE_X_POSITION;

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

    // REQ-KKI-008 : PriorityQueue triée sur (xPosition[ordre], sequenceIndexInOrder) —
    // ordre quasi-topologique du DAG (order-chain : même xPosition, sequenceIndexInOrder
    // croissant ; machine-chain : xPosition strictement croissant par construction de
    // findMachineSuccessor). Prouvé ET vérifié empiriquement (TOPOLOGICAL_INVERSIONS,
    // 0 sur ~350M+ arêtes à 3 échelles) que cette clé est un ordre topologique valide sur
    // le graphe VIVANT, pas seulement au moment de l'attache — donc chaque nœud est
    // finalisé dès son PREMIER dépilement (les prédécesseurs, topologiquement plus
    // petits, ont toujours déjà convergé). Convergence indépendante de l'ordre de
    // dépilement (Bellman-Ford sur DAG, cf. commentaire de classe) : les 4 tests
    // différentiels existants restent la vérification correcte sans modification.
    // RÉALLOUÉE dans resetWorkingSolution (pas au champ) : le comparateur capture `this`
    // et relit xPosition dynamiquement, mais la file DOIT être vide au moment où
    // xPosition change de tableau — allouer après l'assignation, jamais avant, évite
    // tout doute sur ce point plutôt que de s'y fier.
    private PriorityQueue<Operation> worklist;
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
        // Allouée ICI, après l'assignation de xPosition ci-dessus — voir le commentaire
        // du champ worklist pour pourquoi ce n'est pas un détail interchangeable.
        worklist = new PriorityQueue<>(
                Comparator.<Operation>comparingInt(op -> xPosition[(int) op.getOrder().getId()])
                        .thenComparingInt(Operation::getSequenceIndexInOrder));
        LIVE_X_POSITION = xPosition;
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

    /**
     * Source unique de la formule de coût d'un ordre (GUI-PRO-013). Rendue statique et
     * visible du paquet pour que {@link SlackReporter} la réutilise au lieu de la
     * recopier — deux formules de coût qui divergent silencieusement rendraient toute
     * mesure de marge incomparable au score.
     */
    static long computeOrderCost(Order order, long completionEpochSec) {
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

    /**
     * REQ-KKI-008 — identifie l'ensemble minimal d'ordres à détacher/
     * rattacher pour un mouvement MÊME-TAILLE, par une seule passe de
     * comparaison position-à-position ancien/nouveau span — sans table LCS.
     * Mesuré : sans ce filtre, un relocate à grande portée (span ~L/3 en
     * moyenne) rattachait ~1667 ordres/mouvement à L=5000 pour 1 seul
     * réellement déplacé (455× le régime CH, cf. corps REQ-KKI-008).
     * Trois formes reconnues :
     *  - swap (ListSwapMove même entité, avant(min,max+1)) : exactement 2
     *    positions diffèrent → seuls ces 2 ordres sont déplacés, le reste
     *    du span est identique en contenu ET en position (adjacence
     *    machine inchangée par construction).
     *  - relocate (ListChangeMove) : toutes les positions diffèrent MAIS
     *    selon un motif de décalage en bloc d'un cran (old[i+1]==new[i]
     *    partout sauf un, ou son miroir) → un seul ordre réellement
     *    déplacé aux deux extrémités du span ; le bloc décalé garde son
     *    ordre relatif entre ses éléments, donc son adjacence machine
     *    mutuelle est inchangée.
     *  - toute autre forme (reversal, sous-liste, etc.) : repli sûr PAR
     *    CONSTRUCTION — tout le span ancien est retourné, comportement
     *    identique à avant cette optimisation (pas plus rapide, jamais
     *    moins correct).
     * Le même ensemble retourné ici DOIT servir à la fois au détachement
     * et au rattachement dans afterListVariableChanged — sinon des
     * pointeurs prevOnMachine/nextOnMachine détachés (mis à null) ne sont
     * jamais rattachés.
     */
    private List<Order> findDisplacedOrders(List<Order> oldSpan, int fromIndex, int toIndex, List<Order> sequence) {
        int span = toIndex - fromIndex;
        if (span < 2) {
            return oldSpan;
        }
        int diffCount = 0;
        int firstDiff = -1;
        int lastDiff = -1;
        for (int i = 0; i < span; i++) {
            if (oldSpan.get(i) != sequence.get(fromIndex + i)) {
                diffCount++;
                if (firstDiff == -1) {
                    firstDiff = i;
                }
                lastDiff = i;
            }
        }
        if (diffCount == 0) {
            return List.of();
        }
        if (diffCount == 2) {
            return List.of(oldSpan.get(firstDiff), oldSpan.get(lastDiff));
        }
        if (diffCount == span) {
            boolean shiftLeft = true;
            for (int i = 0; i < span - 1 && shiftLeft; i++) {
                if (oldSpan.get(i + 1) != sequence.get(fromIndex + i)) {
                    shiftLeft = false;
                }
            }
            if (shiftLeft && sequence.get(toIndex - 1) == oldSpan.get(0)) {
                return List.of(oldSpan.get(0));
            }
            boolean shiftRight = true;
            for (int i = 1; i < span && shiftRight; i++) {
                if (oldSpan.get(i - 1) != sequence.get(fromIndex + i)) {
                    shiftRight = false;
                }
            }
            if (shiftRight && sequence.get(fromIndex) == oldSpan.get(span - 1)) {
                return List.of(oldSpan.get(span - 1));
            }
        }
        return oldSpan;
    }

    private void propagate() {
        while (!worklist.isEmpty()) {
            Operation op = worklist.poll();
            PROPAGATE_POPS.incrementAndGet();
            queued[idx(op)] = false;

            Operation machineSucc = nextOnMachine[idx(op)];
            if (machineSucc != null
                    && xPosition[(int) machineSucc.getOrder().getId()] < xPosition[(int) op.getOrder().getId()]) {
                TOPOLOGICAL_INVERSIONS.incrementAndGet();
            }

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
            PROPAGATE_DIRTY_POPS.incrementAndGet();
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
        PROPAGATION_CALLS.incrementAndGet();
        MOVE_SPAN_TOTAL.addAndGet(toIndex - fromIndex);
        List<Order> sequence = schedule.getOrderSequence();
        boolean sameSize = changedRangeBefore.size() == toIndex - fromIndex;
        if (sameSize) {
            // REQ-KKI-008 : ensemble minimal (swap/relocate) au lieu de tout le span —
            // voir findDisplacedOrders. Le MÊME ensemble sert au détachement et au
            // rattachement ci-dessous, condition de correction (cf. javadoc de la méthode).
            List<Order> displaced = findDisplacedOrders(changedRangeBefore, fromIndex, toIndex, sequence);
            for (Order order : displaced) {
                for (Operation op : order.getOperations()) {
                    detachFromMachineChain(op);
                }
            }
            for (int i = fromIndex; i < toIndex; i++) {
                xPosition[(int) sequence.get(i).getId()] = i;
            }
            for (Order order : displaced) {
                int orderIndex = xPosition[(int) order.getId()];
                for (Operation op : order.getOperations()) {
                    attachToMachineChain(op, orderIndex);
                }
            }
        } else {
            // Net insert/unassign (ListAssignMove/ListUnassignMove) : the index shift extends
            // past [fromIndex,toIndex) to the rest of the list, unlike same-size relocate/swap/
            // reversal moves where the span fully bounds it. Only construction heuristic hits
            // this branch here (every Order is mandatory, never unassigned again once placed).
            for (Order order : changedRangeBefore) {
                for (Operation op : order.getOperations()) {
                    detachFromMachineChain(op);
                }
            }
            // Positions before fromIndex provably never shift — resync from fromIndex only, not 0.
            for (int i = fromIndex; i < sequence.size(); i++) {
                xPosition[(int) sequence.get(i).getId()] = i;
            }
            // [fromIndex,toIndex) already bounds exactly what's new here (empty on unassign,
            // the single inserted element on assign) — no narrowing needed, already minimal.
            for (int i = fromIndex; i < toIndex; i++) {
                for (Operation op : sequence.get(i).getOperations()) {
                    attachToMachineChain(op, i);
                }
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
