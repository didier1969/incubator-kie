package kki.domain.full;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.calculator.ConstraintMatchAwareIncrementalScoreCalculator;
import org.optaplanner.core.api.score.constraint.ConstraintMatchTotal;
import org.optaplanner.core.api.score.constraint.Indictment;
import org.optaplanner.core.impl.score.constraint.DefaultConstraintMatchTotal;

/**
 * Calculateur de score du domaine complet (PIL-KKI-004), incrémental.
 *
 * <p>
 * <b>Quatre familles de prédécesseurs, pas deux.</b> Une opération attend sa chaîne (la passe
 * précédente du même ordre), sa machine (l'opération précédente sur la ressource), <b>son
 * metteur</b> (la mise en train précédente confiée au même homme) et, quand elle en emprunte un,
 * <b>son outillage</b> (la mise en train précédente qui tenait le même montage). Les quatre files
 * sont ordonnées par le même rang topologique {@code (position X, passe)}, donc toutes les arêtes
 * vont du rang faible vers le rang fort : l'acyclicité reste gratuite et chaque nœud se finalise
 * dès son premier dépilement.
 *
 * <p>
 * <b>Fenêtre d'emprunt de l'outillage — hypothèse explicite.</b> Un exemplaire est pris au début
 * de la mise en train et rendu à sa FIN ({@code setupEndAt}), conformément à `CPT-KKI-006`
 * (« emprunté pour la durée de la mise en train et rendu ensuite »). Un montage physiquement
 * resté en place pendant l'usinage se rendrait à {@code opEnd} — les deux seuls points du fichier
 * qui décident sont marqués {@code RENDU-OUTILLAGE}, pour que le jour où l'atelier tranche
 * autrement, le changement soit d'une ligne et non d'une refonte.
 *
 * <p>
 * <b>Chaque ressource a son calendrier.</b> Machine et metteur sont deux ressources au même
 * titre — c'est la précision opérateur qui a unifié le modèle. Une mise en train consomme du
 * temps de metteur dans le calendrier DE CE METTEUR, immobilise la machine pendant tout ce
 * temps-là, trous compris, et l'usinage qui suit consomme du temps machine dans le calendrier de
 * la machine. Les fenêtres de maintenance et les absences ne sont que des indisponibilités
 * datées dans ces mêmes calendriers.
 *
 * <p>
 * <b>Navigation par identifiant, jamais par référence d'objet</b> — les tableaux indexés sur
 * l'identifiant sont à la fois la mémoïsation du chemin chaud et l'immunité au clonage de
 * solution.
 */
public final class FullScoreCalculator
        implements ConstraintMatchAwareIncrementalScoreCalculator<JobShopSolution, HardSoftLongScore> {

    /** Assez grand pour que le rang reste (position X, passe) sans collision. */
    private static final int RANK_STRIDE = 16;

    public static final AtomicLong CALCULATE_SCORE_CALLS = new AtomicLong();
    public static final AtomicLong DIRTY_OPERATIONS = new AtomicLong();
    public static final AtomicLong ORDER_COMPLETION_CHANGES = new AtomicLong();
    public static final AtomicLong PROPAGATIONS = new AtomicLong();

    /**
     * Opérations ENFILÉES par {@code flushTouched}, contre celles dont la date change vraiment
     * ({@code DIRTY_OPERATIONS}). L'écart entre les deux est du travail pur : recalculer une
     * opération pour constater qu'elle n'a pas bougé coûte le calcul complet de ses quatre
     * attentes et deux appels de calendrier.
     */
    public static final AtomicLong ENQUEUED_OPERATIONS = new AtomicLong();

    /** Référence vivante, pour que le sélecteur guidé lise les arcs tendus sans tout recalculer. */

    private JobShopSolution solution;
    private Schedule schedule;
    private SetupMatrix setupMatrix;
    private long origin;

    private Operation[] opById;
    private int[] xPosition;
    private int[] rank;
    private int[] chainPredecessorId;
    private int[] chainSuccessorId;
    private int[] lastOpIdOfOrder;

    private long[] setupStartAt;
    private long[] setupEndAt;
    private long[] opStart;
    private long[] opEnd;
    private long[] opResourceCents;
    private long[] orderCents;
    private long[] hardViolation;

    private long[] machineHourlyCents;
    private WorkCalendar[] machineCalendar;
    private WorkCalendar[] setterCalendar;
    /** Les metteurs, pour tirer un candidat compétent sans repasser par la solution. */
    private List<Setter> setterList;

    private int[] prevOnMachineId;
    private int[] nextOnMachineId;
    private int[] assignedMachineId;
    private int[] prevOnSetterId;
    private int[] nextOnSetterId;
    private int[] assignedSetterId;
    private int[] prevOnToolingId;
    private int[] nextOnToolingId;
    private int[] assignedToolingId;

    private List<Operation>[] operationsByMachine;
    private List<Operation>[] setupsBySetter;
    private List<Operation>[] setupsByTooling;
    private PriorityQueue<Operation> worklist;
    private boolean[] queued;
    private long softTotalCents;
    private long hardTotal;

    // Tampons réutilisés : allouer par évaluation, à des dizaines de milliers d'évaluations par
    // seconde, se paie en ramasse-miettes.
    private Order[] movedOrders = new Order[16];
    private int movedCount;
    /**
     * Position MINIMALE touchée dans la file de chaque ressource, ou {@link Integer#MAX_VALUE}
     * quand elle est inconnue.
     *
     * <p>
     * Les files sont triées par rang, donc une insertion ou un retrait en position <i>k</i> ne
     * peut déplacer que les opérations de rang <b>≥ k</b> : celles qui précèdent gardent le même
     * prédécesseur, donc les mêmes dates. Les enfiler quand même les fait recalculer pour
     * constater qu'elles n'ont pas bougé — mesuré à 36,5 % du travail de propagation
     * (`REQ-KKI-043`), et amplifié par les ressources rares : 76 mises en train par metteur et 64
     * emprunts par outillage, contre 18 opérations par machine.
     *
     * <p>
     * {@code MAX_VALUE} vaut « je ne sais pas » et fait retomber sur l'enfilage INTÉGRAL. Un
     * chemin qui toucherait une ressource sans passer par une insertion ni un retrait resterait
     * donc correct, au prix de l'ancien coût — jamais faux.
     */
    private int[] touchedFromMachine;
    private int[] touchedFromSetter;
    private int[] touchedFromTooling;
    private int[] touchedMachines;
    private boolean[] machineTouched;
    private int touchedMachineCount;
    private int[] touchedSetters;
    private boolean[] setterTouched;
    private int touchedSetterCount;
    private int[] touchedToolings;
    private boolean[] toolingTouched;
    private int touchedToolingCount;
    private Order[] dirtyOrders = new Order[256];
    private boolean[] orderDirty;

    @Override
    @SuppressWarnings("unchecked")
    public void resetWorkingSolution(JobShopSolution workingSolution) {
        this.solution = workingSolution;
        this.schedule = workingSolution.getScheduleList().get(0);
        this.setupMatrix = workingSolution.getSetupMatrix();
        this.origin = workingSolution.getOriginEpochSec();

        List<Operation> operations = workingSolution.getOperationList();
        int opCount = operations.size();
        int orderCount = workingSolution.getOrderList().size();
        int machineCount = workingSolution.getMachineList().size();
        int setterCount = workingSolution.getSetterList().size();
        int toolingCount = workingSolution.getToolingList().size();

        opById = new Operation[opCount];
        for (Operation op : operations) {
            opById[(int) op.getId()] = op;
        }

        xPosition = new int[orderCount];
        Arrays.fill(xPosition, -1);
        rank = new int[opCount];
        setupStartAt = new long[opCount];
        setupEndAt = new long[opCount];
        opStart = new long[opCount];
        opEnd = new long[opCount];
        opResourceCents = new long[opCount];
        orderCents = new long[orderCount];
        hardViolation = new long[orderCount];
        prevOnMachineId = new int[opCount];
        nextOnMachineId = new int[opCount];
        assignedMachineId = new int[opCount];
        prevOnSetterId = new int[opCount];
        nextOnSetterId = new int[opCount];
        assignedSetterId = new int[opCount];
        prevOnToolingId = new int[opCount];
        nextOnToolingId = new int[opCount];
        assignedToolingId = new int[opCount];
        // Les opérations SANS outillage n'entrent dans aucune file, donc relinkTooling ne les
        // visite jamais : sans ce remplissage elles pointeraient sur l'opération 0 par défaut.
        Arrays.fill(prevOnToolingId, -1);
        Arrays.fill(nextOnToolingId, -1);
        queued = new boolean[opCount];
        touchedMachines = new int[machineCount];
        machineTouched = new boolean[machineCount];
        touchedSetters = new int[setterCount];
        setterTouched = new boolean[setterCount];
        touchedToolings = new int[toolingCount];
        toolingTouched = new boolean[toolingCount];
        touchedFromMachine = new int[machineCount];
        touchedFromSetter = new int[setterCount];
        touchedFromTooling = new int[toolingCount];
        Arrays.fill(touchedFromMachine, Integer.MAX_VALUE);
        Arrays.fill(touchedFromSetter, Integer.MAX_VALUE);
        Arrays.fill(touchedFromTooling, Integer.MAX_VALUE);
        orderDirty = new boolean[orderCount];

        buildChains(operations, orderCount);

        machineHourlyCents = new long[machineCount];
        machineCalendar = new WorkCalendar[machineCount];
        for (Machine machine : workingSolution.getMachineList()) {
            machineHourlyCents[(int) machine.getId()] = machine.getHourlyCostCents();
            machineCalendar[(int) machine.getId()] = machine.getCalendar();
        }
        setterCalendar = new WorkCalendar[setterCount];
        setterList = workingSolution.getSetterList();
        for (Setter setter : workingSolution.getSetterList()) {
            setterCalendar[(int) setter.getId()] = setter.getCalendar();
        }

        operationsByMachine = new List[machineCount];
        for (int m = 0; m < machineCount; m++) {
            operationsByMachine[m] = new ArrayList<>();
        }
        setupsBySetter = new List[setterCount];
        for (int s = 0; s < setterCount; s++) {
            setupsBySetter[s] = new ArrayList<>();
        }
        setupsByTooling = new List[toolingCount];
        for (int t = 0; t < toolingCount; t++) {
            setupsByTooling[t] = new ArrayList<>();
        }

        List<Order> sequence = schedule.getOrderSequence();
        for (int i = 0; i < sequence.size(); i++) {
            xPosition[(int) sequence.get(i).getId()] = i;
        }
        for (Operation op : operations) {
            int opId = (int) op.getId();
            rank[opId] = xPosition[(int) op.getOrder().getId()] * RANK_STRIDE + op.getPassIndex();
            assignedMachineId[opId] = (int) op.getMachineId();
            assignedSetterId[opId] = (int) op.getSetter().getId();
            // -1 = cette mise en train n'emprunte rien : elle n'entre dans aucune file
            // d'outillage, et ne peut donc jamais être retenue par le pool.
            assignedToolingId[opId] = op.getTooling() == null ? -1 : (int) op.getTooling().getId();
            operationsByMachine[assignedMachineId[opId]].add(op);
            setupsBySetter[assignedSetterId[opId]].add(op);
            if (assignedToolingId[opId] >= 0) {
                setupsByTooling[assignedToolingId[opId]].add(op);
            }
        }
        for (List<Operation> queue : operationsByMachine) {
            queue.sort(this::byRank);
        }
        for (List<Operation> queue : setupsBySetter) {
            queue.sort(this::byRank);
        }
        for (List<Operation> queue : setupsByTooling) {
            queue.sort(this::byRank);
        }
        for (int m = 0; m < machineCount; m++) {
            relinkMachine(operationsByMachine[m]);
        }
        for (int s = 0; s < setterCount; s++) {
            relinkSetter(setupsBySetter[s]);
        }
        for (int t = 0; t < toolingCount; t++) {
            relinkTooling(setupsByTooling[t]);
        }

        worklist = new PriorityQueue<>(this::byRank);
        fullRebuild();
    }

    private int byRank(Operation a, Operation b) {
        return Integer.compare(rank[(int) a.getId()], rank[(int) b.getId()]);
    }

    /** Chaînes reconstruites depuis la solution DE TRAVAIL, jamais depuis Order.getOperations(). */
    private void buildChains(List<Operation> operations, int orderCount) {
        chainPredecessorId = new int[operations.size()];
        chainSuccessorId = new int[operations.size()];
        lastOpIdOfOrder = new int[orderCount];
        Arrays.fill(chainPredecessorId, -1);
        Arrays.fill(chainSuccessorId, -1);
        Arrays.fill(lastOpIdOfOrder, -1);
        int[][] byOrderAndPass = new int[orderCount][RANK_STRIDE];
        for (int[] row : byOrderAndPass) {
            Arrays.fill(row, -1);
        }
        for (Operation op : operations) {
            byOrderAndPass[(int) op.getOrder().getId()][op.getPassIndex()] = (int) op.getId();
        }
        for (int o = 0; o < orderCount; o++) {
            int previous = -1;
            for (int pass = 0; pass < RANK_STRIDE; pass++) {
                int current = byOrderAndPass[o][pass];
                if (current < 0) {
                    break;
                }
                chainPredecessorId[current] = previous;
                if (previous >= 0) {
                    chainSuccessorId[previous] = current;
                }
                previous = current;
                lastOpIdOfOrder[o] = current;
            }
        }
    }

    private void fullRebuild() {
        softTotalCents = 0L;
        hardTotal = 0L;
        Arrays.fill(orderCents, 0L);
        Arrays.fill(opResourceCents, 0L);
        Arrays.fill(hardViolation, 0L);
        for (Order order : schedule.getOrderSequence()) {
            for (int opId = firstOpIdOf(order); opId >= 0; opId = chainSuccessorId[opId]) {
                recomputeOperation(opId, false);
            }
        }
        for (Order order : schedule.getOrderSequence()) {
            recomputeOrderCost(order, false);
        }
    }

    private int firstOpIdOf(Order order) {
        int first = lastOpIdOfOrder[(int) order.getId()];
        while (first >= 0 && chainPredecessorId[first] >= 0) {
            first = chainPredecessorId[first];
        }
        return first;
    }

    private void relinkMachine(List<Operation> queue) {
        int previous = -1;
        for (Operation op : queue) {
            int current = (int) op.getId();
            prevOnMachineId[current] = previous;
            if (previous >= 0) {
                nextOnMachineId[previous] = current;
            }
            previous = current;
        }
        if (previous >= 0) {
            nextOnMachineId[previous] = -1;
        }
    }

    private void relinkSetter(List<Operation> queue) {
        int previous = -1;
        for (Operation op : queue) {
            int current = (int) op.getId();
            prevOnSetterId[current] = previous;
            if (previous >= 0) {
                nextOnSetterId[previous] = current;
            }
            previous = current;
        }
        if (previous >= 0) {
            nextOnSetterId[previous] = -1;
        }
    }

    private void relinkTooling(List<Operation> queue) {
        int previous = -1;
        for (Operation op : queue) {
            int current = (int) op.getId();
            prevOnToolingId[current] = previous;
            if (previous >= 0) {
                nextOnToolingId[previous] = current;
            }
            previous = current;
        }
        if (previous >= 0) {
            nextOnToolingId[previous] = -1;
        }
    }

    private int insertSorted(List<Operation> queue, Operation op) {
        int position = positionOfRank(queue, rank[(int) op.getId()]);
        queue.add(position, op);
        return position;
    }

    /**
     * Retire une opération de sa file et rend la position qu'elle occupait.
     *
     * <p>
     * {@code List.remove(Object)} balaie la liste depuis le début pour retrouver l'élément. Les
     * files sont triées par rang et le rang est unique — {@code position * RANK_STRIDE + passe},
     * et deux opérations ne partagent jamais le couple —, donc la position se trouve par
     * dichotomie. Sur une file de metteur de 76 mises en train, c'est 7 comparaisons au lieu de
     * 38 en moyenne.
     */
    private int removeSorted(List<Operation> queue, Operation op) {
        int position = positionOfRank(queue, rank[(int) op.getId()]);
        if (position < queue.size() && queue.get(position) == op) {
            queue.remove(position);
            return position;
        }
        // Repli : la file n'est pas dans l'ordre attendu pour cette opération. Retirer par
        // balayage reste correct — c'est le comportement d'origine — plutôt que de laisser une
        // opération fantôme dans la file, qui fausserait toutes les dates en aval.
        int fallback = queue.indexOf(op);
        if (fallback >= 0) {
            queue.remove(fallback);
        }
        return fallback >= 0 ? fallback : 0;
    }

    /** Première position dont le rang est ≥ {@code key}, par dichotomie sur une file triée. */
    private int positionOfRank(List<Operation> queue, int key) {
        int low = 0;
        int high = queue.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (rank[(int) queue.get(mid).getId()] < key) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Date une opération et recalcule sa part de coût ressource.
     *
     * <p>
     * L'ordre des attentes n'est pas arbitraire : la mise en train ne peut commencer que lorsque
     * la machine EST libre, que le metteur EST libre ET que l'outillage EST rendu, puis elle
     * consomme le temps ouvert DU METTEUR. La machine, elle, reste immobilisée depuis l'instant
     * où elle s'est libérée — c'est ce décalage que CPT-KKI-007 fait payer au coût horaire
     * machine.
     */
    private boolean recomputeOperation(int opId, boolean track) {
        Operation op = opById[opId];
        int machineId = assignedMachineId[opId];
        int setterId = assignedSetterId[opId];

        int machinePredecessor = prevOnMachineId[opId];
        long machineFreeAt = machinePredecessor >= 0 ? opEnd[machinePredecessor] : origin;
        int setterPredecessor = prevOnSetterId[opId];
        long setterFreeAt = setterPredecessor >= 0 ? setupEndAt[setterPredecessor] : origin;
        int toolingPredecessor = prevOnToolingId[opId];
        // RENDU-OUTILLAGE — l'exemplaire redevient libre à la FIN DE LA MISE EN TRAIN.
        long toolingFreeAt = toolingPredecessor >= 0 ? setupEndAt[toolingPredecessor] : origin;

        long setupSeconds = setupSecondsOf(opId);

        long setupReadyAt = Math.max(Math.max(machineFreeAt, setterFreeAt), toolingFreeAt);
        long setupEnd = setterCalendar[setterId].occupancyEnd(setupReadyAt, setupSeconds);
        long machineIdle = setupEnd - machineFreeAt - setupSeconds;

        int chainPredecessor = chainPredecessorId[opId];
        long chainReadyAt = chainPredecessor >= 0 ? opEnd[chainPredecessor] : origin;
        long start = Math.max(setupEnd, chainReadyAt);
        long end = machineCalendar[machineId].occupancyEnd(start, op.getDurationSeconds());

        long resourceCents =
                CostModel.resourceCents(setupSeconds, machineIdle, machineHourlyCents[machineId]);

        setupStartAt[opId] = setupReadyAt;
        boolean changed = start != opStart[opId] || end != opEnd[opId]
                || setupEnd != setupEndAt[opId] || resourceCents != opResourceCents[opId];
        if (changed && track) {
            DIRTY_OPERATIONS.incrementAndGet();
        }
        softTotalCents -= resourceCents - opResourceCents[opId];
        opResourceCents[opId] = resourceCents;
        setupEndAt[opId] = setupEnd;
        opStart[opId] = start;
        opEnd[opId] = end;
        return changed;
    }

    /**
     * Mise en train à payer pour amener cette opération sur SA machine, telle que la séquence
     * courante la laisse. Une seule définition : la passe aval et la passe amont doivent lire la
     * même, sinon la marge se calcule contre une durée qui n'a jamais été datée.
     */
    private long setupSecondsOf(int opId) {
        int machinePredecessor = prevOnMachineId[opId];
        // La technologie du POSTE entre dans le calcul : la préparation n'est pas la même sur
        // un tour automatique et sur une rectifieuse.
        int technology = opById[opId].getMachine().getTechnology();
        return machinePredecessor >= 0
                ? setupMatrix.secondsBetween(opById[machinePredecessor].getSetupKey(),
                        opById[opId].getSetupKey(), technology)
                : setupMatrix.coldStartSeconds(opById[opId].getSetupKey());
    }

    private void recomputeOrderCost(Order order, boolean track) {
        int oi = (int) order.getId();
        long completion = opEnd[lastOpIdOfOrder[oi]];
        long cents = CostModel.orderCents(order, completion);
        if (cents != orderCents[oi]) {
            if (track) {
                ORDER_COMPLETION_CHANGES.incrementAndGet();
            }
            softTotalCents -= cents - orderCents[oi];
            orderCents[oi] = cents;
        }
        long violation = CostModel.hardViolation(order, completion);
        hardTotal -= violation - hardViolation[oi];
        hardViolation[oi] = violation;
    }

    // ************************************************************************
    // Passe à froid — une seule implémentation pour toutes les lectures
    // ************************************************************************

    /**
     * Rejoue tout le plan à froid, indépendamment de l'état incrémental. Sert d'oracle au test
     * différentiel ET de source aux lectures de coût : quatre copies de la datation finiraient
     * par diverger, et c'est la divergence qui serait invisible.
     */
    /**
     * La part de chaque terme de coût, ordre par ordre.
     *
     * <p>
     * Six tableaux indexés par identifiant d'ordre. Ils ne sont alloués QUE si quelqu'un les
     * demande : {@link #coldSweep()} passe {@code null} et ne paie rien, ce qui compte parce que
     * {@code fullSweepScore()} est appelé À CHAQUE MOUVEMENT sous {@code FULL_ASSERT}.
     */
    public record Attribution(long[] setter, long[] machineIdle, long[] tardiness,
            long[] earliness, long[] softFreeze, long[] hard) {

        static Attribution of(int orderCount) {
            return new Attribution(new long[orderCount], new long[orderCount], new long[orderCount],
                    new long[orderCount], new long[orderCount], new long[orderCount]);
        }
    }

    public ColdSweep coldSweep() {
        return coldSweep(null);
    }

    /**
     * @param attribution si non nul, reçoit la part de chaque terme par ordre. Le balayage reste
     *        UNIQUE : dupliquer le corps pour produire l'imputation ferait exactement la
     *        divergence contre laquelle la javadoc ci-dessus met en garde.
     */
    public ColdSweep coldSweep(Attribution attribution) {
        int machineCount = machineCalendar.length;
        int setterCount = setterCalendar.length;
        int toolingCount = setupsByTooling.length;
        long[] machineFree = new long[machineCount];
        long[] setterFree = new long[setterCount];
        long[] toolingFree = new long[toolingCount];
        int[] lastKeyOnMachine = new int[machineCount];
        Arrays.fill(machineFree, origin);
        Arrays.fill(setterFree, origin);
        Arrays.fill(toolingFree, origin);
        Arrays.fill(lastKeyOnMachine, -1);
        long borrowing = 0L;
        long bound = 0L;

        long setter = 0L;
        long idle = 0L;
        long tardiness = 0L;
        long earliness = 0L;
        long softFreeze = 0L;
        long hard = 0L;
        long[] completions = new long[orderCents.length];

        for (Order order : schedule.getOrderSequence()) {
            long chainReadyAt = origin;
            for (int opId = firstOpIdOf(order); opId >= 0; opId = chainSuccessorId[opId]) {
                Operation op = opById[opId];
                int m = (int) op.getMachineId();
                int s = (int) op.getSetter().getId();
                long setupSeconds = lastKeyOnMachine[m] < 0
                        ? setupMatrix.coldStartSeconds(op.getSetupKey())
                        : setupMatrix.secondsBetween(lastKeyOnMachine[m], op.getSetupKey(),
                                op.getMachine().getTechnology());
                int t = op.getTooling() == null ? -1 : (int) op.getTooling().getId();
                long withoutTooling = Math.max(machineFree[m], setterFree[s]);
                long setupReadyAt = t < 0 ? withoutTooling : Math.max(withoutTooling, toolingFree[t]);
                if (t >= 0) {
                    borrowing++;
                    // TAUX DE LIAISON — l'outillage retient-il vraiment, ou la contrainte est-elle
                    // décorative ? Un pool jamais liant rendrait la 4e famille indiscernable d'une
                    // absence de famille, et le test différentiel ne pourrait pas le voir.
                    if (toolingFree[t] > withoutTooling) {
                        bound++;
                    }
                }
                long setupEnd = setterCalendar[s].occupancyEnd(setupReadyAt, setupSeconds);
                long machineIdle = setupEnd - machineFree[m] - setupSeconds;
                long finish = machineCalendar[m]
                        .occupancyEnd(Math.max(setupEnd, chainReadyAt), op.getDurationSeconds());

                long setterCost = setupSeconds * CostModel.SETTER_CENTS_PER_HOUR / 3600L;
                long idleCost = machineIdle * machineHourlyCents[m] / 3600L;
                setter += setterCost;
                idle += idleCost;
                if (attribution != null) {
                    attribution.setter()[(int) order.getId()] += setterCost;
                    attribution.machineIdle()[(int) order.getId()] += idleCost;
                }
                machineFree[m] = finish;
                setterFree[s] = setupEnd;
                if (t >= 0) {
                    // RENDU-OUTILLAGE — rendu à la FIN DE LA MISE EN TRAIN, pas de l'usinage.
                    toolingFree[t] = setupEnd;
                }
                lastKeyOnMachine[m] = op.getSetupKey();
                chainReadyAt = finish;
            }
            completions[(int) order.getId()] = chainReadyAt;
            long total = CostModel.orderCents(order, chainReadyAt);
            long freeze = order.getFreezeLevel() == Order.FreezeLevel.SOFT
                    ? total - CostModel.orderCents(asFree(order), chainReadyAt)
                    : 0L;
            softFreeze += freeze;
            long late = chainReadyAt > order.getDueEpochSec() ? total - freeze : 0L;
            long early = chainReadyAt > order.getDueEpochSec() ? 0L : total - freeze;
            tardiness += late;
            earliness += early;
            long hardPart = -CostModel.hardViolation(order, chainReadyAt);
            hard += hardPart;
            if (attribution != null) {
                int orderIndex = (int) order.getId();
                attribution.softFreeze()[orderIndex] += freeze;
                attribution.tardiness()[orderIndex] += late;
                attribution.earliness()[orderIndex] += early;
                attribution.hard()[orderIndex] += hardPart;
            }
        }
        return new ColdSweep(setter, idle, tardiness, earliness, softFreeze, hard, completions,
                borrowing, bound);
    }

    /** Le même ordre au palier libre — isole la part de pénalité de stabilité. */
    private static Order asFree(Order order) {
        return new Order(order.getId(), order.getArticleId(), order.getPriorityWeight(),
                order.getDueEpochSec(), Order.FreezeLevel.FREE, order.getReferenceCompletionEpochSec());
    }

    /**
     * @param toolingBorrowing mises en train qui empruntent un exemplaire du pool
     * @param toolingBound     celles dont le départ est retenu par l'outillage et par rien
     *                         d'autre — la mesure qui sépare une contrainte réelle d'un décor
     */
    public record ColdSweep(long setter, long machineIdle, long tardiness, long earliness,
            long softFreeze, long hard, long[] completions,
            long toolingBorrowing, long toolingBound) {

        public long soft() {
            return -(setter + machineIdle + tardiness + earliness + softFreeze);
        }

        public HardSoftLongScore score() {
            return HardSoftLongScore.of(hard, soft());
        }

        public String describe(String label) {
            long total = Math.max(1L, -soft());
            return String.format(
                    "cost_breakdown[%s] total_chf=%.3e setter_chf=%.3e machine_idle_chf=%.3e "
                            + "tardiness_chf=%.3e earliness_chf=%.3e soft_freeze_chf=%.3e "
                            + "tardiness_over_physical=%.0f tooling_borrowing=%d "
                            + "tooling_bound=%d tooling_binding_rate=%.1f%%%n",
                    label, total / 100.0, setter / 100.0, machineIdle / 100.0, tardiness / 100.0,
                    earliness / 100.0, softFreeze / 100.0,
                    (double) tardiness / Math.max(1L, setter + machineIdle),
                    toolingBorrowing, toolingBound,
                    100.0 * toolingBound / Math.max(1L, toolingBorrowing));
        }
    }

    /** Paquet des contraintes, tel qu'il apparaît dans le rapport de benchmark. */
    private static final String CONSTRAINT_PACKAGE = "kki.domain.full";

    /**
     * Les six termes du coût, sous les noms que le moteur publiera.
     *
     * <p>
     * <b>Aucun nom ne contient de virgule</b>, et ce n'est pas un choix de style : les points de
     * statistique du benchmark sont sérialisés en CSV, si bien qu'une virgule dans un nom de
     * contrainte fait échouer la relecture par un {@code NumberFormatException} au moment de
     * produire le rapport — après la mesure, donc au pire moment. Trouvé à l'exécution, pas à la
     * relecture.
     *
     * <p>
     * Ce ne sont pas des étiquettes inventées pour l'occasion : ce sont exactement les termes que
     * {@link ColdSweep#describe} imprime déjà dans {@code cost_breakdown}. Les exposer sous le
     * contrat du moteur plutôt que dans une ligne de log ouvre trois portes d'un coup — le test
     * par contrainte via {@code AbstractScoreVerifier}, les statistiques
     * {@code CONSTRAINT_MATCH_TOTAL_*} du benchmark, et l'explicabilité côté produit.
     */
    public static final class Constraints {
        public static final String SETTER = "coût du metteur en train";
        public static final String MACHINE_IDLE = "machine à l'arrêt dans un trou du calendrier metteur";
        public static final String TARDINESS = "retard quadratique pondéré par la priorité";
        public static final String EARLINESS = "avance quadratique jamais pondérée";
        public static final String SOFT_FREEZE = "écart au dernier plan publié";
        public static final String HARD = "violations dures";

        private Constraints() {
        }
    }

    @Override
    public void resetWorkingSolution(JobShopSolution workingSolution, boolean constraintMatchEnabled) {
        // Le drapeau est ignoré, comme dans l'implémentation de référence du fork
        // (MachineReassignmentIncrementalScoreCalculator) : la décomposition est calculée À LA
        // DEMANDE par un balayage complet, jamais maintenue de façon incrémentale. Le coût n'est
        // donc payé que par celui qui la demande — pas à chaque mouvement.
        resetWorkingSolution(workingSolution);
    }

    /**
     * La décomposition du coût, imputée ORDRE PAR ORDRE.
     *
     * <p>
     * Un match par couple (contrainte, ordre) plutôt qu'un agrégat par contrainte. C'est ce qui
     * donne un {@code Indictment} réel : le moteur peut répondre « ces douze ordres portent 40 %
     * du retard », au lieu de « le retard vaut 2,5e13 ».
     *
     * <p>
     * <b>Ce que ça ouvre au-delà de l'explication.</b> Un indictment est du blâme MESURÉ. Notre
     * {@code CriticalPairMoveIteratorFactory} guide déjà la sélection, mais sur une heuristique
     * SUPPOSÉE — la tension des arcs disjonctifs. Une sélection pondérée par l'indictment serait
     * guidée par ce que le coût dit réellement, et se corrigerait à chaque tour : mesurer,
     * imputer, cibler, re-mesurer.
     *
     * <p>
     * Les ordres à contribution nulle sont écartés : sur 5000 ordres la plupart des termes sont
     * creux, et un match à zéro n'apprend rien tout en coûtant un objet.
     */
    @Override
    public Collection<ConstraintMatchTotal<HardSoftLongScore>> getConstraintMatchTotals() {
        Attribution attribution = Attribution.of(orderCents.length);
        coldSweep(attribution);
        List<Order> orders = schedule.getOrderSequence();
        return List.of(
                total(Constraints.SETTER, attribution.setter(), orders, true),
                total(Constraints.MACHINE_IDLE, attribution.machineIdle(), orders, true),
                total(Constraints.TARDINESS, attribution.tardiness(), orders, true),
                total(Constraints.EARLINESS, attribution.earliness(), orders, true),
                total(Constraints.SOFT_FREEZE, attribution.softFreeze(), orders, true),
                total(Constraints.HARD, attribution.hard(), orders, false));
    }

    /**
     * @param soft vrai pour un terme souple — la part y est un COÛT, donc portée au score en
     *        négatif. Le terme dur porte déjà son signe.
     */
    private DefaultConstraintMatchTotal<HardSoftLongScore> total(String name, long[] byOrder,
            List<Order> orders, boolean soft) {
        DefaultConstraintMatchTotal<HardSoftLongScore> matchTotal =
                new DefaultConstraintMatchTotal<>(CONSTRAINT_PACKAGE, name);
        for (Order order : orders) {
            long part = byOrder[(int) order.getId()];
            if (part != 0L) {
                matchTotal.addConstraintMatch(List.of(order),
                        soft ? HardSoftLongScore.ofSoft(-part) : HardSoftLongScore.ofHard(part));
            }
        }
        return matchTotal;
    }

    @Override
    public Map<Object, Indictment<HardSoftLongScore>> getIndictmentMap() {
        return null; // dérivé non-incrémentalement de getConstraintMatchTotals()
    }

    public HardSoftLongScore fullSweepScore() {
        return coldSweep().score();
    }

    /** Distribution du retard par ordre, en heures. */
    public String latenessProfile(String label) {
        ColdSweep sweep = coldSweep();
        long[] lateness = new long[solution.getOrderList().size()];
        int index = 0;
        int late = 0;
        for (Order order : solution.getOrderList()) {
            long delta = (sweep.completions()[(int) order.getId()] - order.getDueEpochSec()) / 3600L;
            lateness[index++] = delta;
            if (delta > 0L) {
                late++;
            }
        }
        long[] sorted = lateness.clone();
        Arrays.sort(sorted);
        return String.format("lateness[%s] late=%d/%d p10=%dh median=%dh p90=%dh max=%dh%n",
                label, late, lateness.length, sorted[sorted.length / 10], sorted[sorted.length / 2],
                sorted[sorted.length * 9 / 10], sorted[sorted.length - 1]);
    }


    // ************************************************************************
    // Passe amont — la date d'une opération est un INTERVALLE, pas une valeur
    // ************************************************************************

    /**
     * Datation au plus tard, à séquence INCHANGÉE — la seconde moitié de « calcul aval puis
     * amont » de `CPT-KKI-003`, et l'invariant 7 de `CPT-KKI-012`.
     *
     * <p>
     * <b>Ce que ça calcule.</b> Pour chaque opération, la date la plus tardive à laquelle elle
     * peut se placer sans repousser quoi que ce soit : ni la passe suivante de son ordre, ni
     * l'opération suivante sur sa machine, ni la mise en train suivante de son metteur ou de son
     * outillage. La différence avec la date au plus tôt est la <b>marge</b> — qui n'existait pas
     * dans le modèle tant que cette passe manquait.
     *
     * <p>
     * <b>Pourquoi l'ordre de parcours est gratuit.</b> Les quatre familles de successeurs sont
     * ordonnées sur le même rang {@code (position X, passe)} que la passe aval. Le parcours par
     * rang DÉCROISSANT visite donc tout successeur avant son prédécesseur, exactement comme le
     * rang croissant fait l'inverse. Aucune structure supplémentaire.
     *
     * <p>
     * <b>Pourquoi ce n'est PAS incrémental.</b> Le score reste assis sur les dates au plus tôt.
     * Rendre cette passe incrémentale ajouterait une cinquième structure à un chemin chaud qui
     * repropage déjà 23 % du modèle par mouvement, pour une information que le score ne consomme
     * pas. Basculer le score sur la datation JIT est une décision mesurable, avec son propre
     * relevé — pas un effet de bord de celle-ci.
     *
     * <p>
     * <b>L'initialisation de la dernière passe, qui décide de tout.</b> On borne par
     * {@code max(date due, date au plus tôt)} et non par la date due seule. Sur une instance
     * chargée, tous les ordres sont en retard : borner par la date due donnerait une marge
     * NÉGATIVE partout et ferait tomber {@code au plus tôt ≤ au plus tard} sur l'instance
     * entière — un test rouge qui accuserait la propagation alors que l'initialisation serait
     * seule en cause. Un ordre déjà en retard a une marge nulle, ce qui est la lecture juste :
     * rien ne peut y être décalé.
     */
    public BackwardSweep backwardSweep() {
        int opCount = opById.length;
        long[] latestEnd = new long[opCount];
        long[] latestStart = new long[opCount];
        long[] latestSetupEnd = new long[opCount];
        long[] latestSetupStart = new long[opCount];

        Operation[] descending = opById.clone();
        Arrays.sort(descending, (a, b) -> Integer.compare(rank[(int) b.getId()], rank[(int) a.getId()]));

        for (Operation op : descending) {
            int opId = (int) op.getId();
            int chainNext = chainSuccessorId[opId];
            long bound = chainNext < 0
                    ? Math.max(op.getOrder().getDueEpochSec(), opEnd[opId])
                    : latestStart[chainNext];
            int machineNext = nextOnMachineId[opId];
            if (machineNext >= 0) {
                bound = Math.min(bound, latestSetupStart[machineNext]);
            }
            latestEnd[opId] = bound;
            latestStart[opId] = machineCalendar[assignedMachineId[opId]]
                    .occupancyStart(bound, op.getDurationSeconds());

            long setupBound = latestStart[opId];
            int setterNext = nextOnSetterId[opId];
            if (setterNext >= 0) {
                setupBound = Math.min(setupBound, latestSetupStart[setterNext]);
            }
            int toolingNext = nextOnToolingId[opId];
            if (toolingNext >= 0) {
                setupBound = Math.min(setupBound, latestSetupStart[toolingNext]);
            }
            latestSetupEnd[opId] = setupBound;
            latestSetupStart[opId] = setterCalendar[assignedSetterId[opId]]
                    .occupancyStart(setupBound, setupSecondsOf(opId));
        }

        // Coût du MÊME plan daté au plus tard. La formule du temps machine immobilisé est celle
        // de la passe aval, terme pour terme : la machine se libère à la fin de l'opération
        // précédente et reste prise jusqu'à la fin de la mise en train suivante.
        long jitSetter = 0L;
        long jitIdle = 0L;
        long jitTardiness = 0L;
        long jitEarliness = 0L;
        long jitSoftFreeze = 0L;
        long slackSeconds = 0L;
        long opsWithSlack = 0L;
        long ordersWithSlack = 0L;
        long[] jitCompletions = new long[orderCents.length];

        for (Operation op : opById) {
            int opId = (int) op.getId();
            long slack = latestStart[opId] - opStart[opId];
            if (slack > 0L) {
                slackSeconds += slack;
                opsWithSlack++;
            }
            long setupSeconds = setupSecondsOf(opId);
            int machinePrevious = prevOnMachineId[opId];
            long machineFreeAt = machinePrevious >= 0 ? latestEnd[machinePrevious] : origin;
            jitSetter += setupSeconds * CostModel.SETTER_CENTS_PER_HOUR / 3600L;
            jitIdle += Math.max(0L, latestSetupEnd[opId] - machineFreeAt - setupSeconds)
                    * machineHourlyCents[assignedMachineId[opId]] / 3600L;
        }

        for (Order order : schedule.getOrderSequence()) {
            int oi = (int) order.getId();
            long completion = latestEnd[lastOpIdOfOrder[oi]];
            jitCompletions[oi] = completion;
            if (completion > opEnd[lastOpIdOfOrder[oi]]) {
                ordersWithSlack++;
            }
            long total = CostModel.orderCents(order, completion);
            long freeze = order.getFreezeLevel() == Order.FreezeLevel.SOFT
                    ? total - CostModel.orderCents(asFree(order), completion)
                    : 0L;
            jitSoftFreeze += freeze;
            if (completion > order.getDueEpochSec()) {
                jitTardiness += total - freeze;
            } else {
                jitEarliness += total - freeze;
            }
        }
        return new BackwardSweep(latestSetupStart, latestSetupEnd, latestStart, latestEnd,
                jitCompletions, jitSetter, jitIdle, jitTardiness, jitEarliness, jitSoftFreeze,
                slackSeconds, opsWithSlack, ordersWithSlack);
    }

    /**
     * @param opsWithSlack    opérations dont la date au plus tard est STRICTEMENT après la date
     *                        au plus tôt — zéro voudrait dire que la passe amont n'apprend rien
     *                        sur cette instance, et rendrait les invariants de M3 creux
     * @param ordersWithSlack ordres dont la complétion peut être retardée sans coût
     */
    public record BackwardSweep(long[] latestSetupStart, long[] latestSetupEnd, long[] latestStart,
            long[] latestEnd, long[] jitCompletions, long jitSetter, long jitMachineIdle,
            long jitTardiness, long jitEarliness, long jitSoftFreeze, long slackSeconds,
            long opsWithSlack, long ordersWithSlack) {

        /** Coût total du plan daté au plus tard, en centimes. */
        public long jitCostCents() {
            return jitSetter + jitMachineIdle + jitTardiness + jitEarliness + jitSoftFreeze;
        }

        public String describe(String label, long earliestCostCents) {
            return String.format(
                    "jit[%s] jit_chf=%.3e earliest_chf=%.3e gain_pct=%.4f ops_with_slack=%d"
                            + " orders_with_slack=%d mean_slack_h=%.1f%n",
                    label, jitCostCents() / 100.0, earliestCostCents / 100.0,
                    100.0 * (earliestCostCents - jitCostCents()) / Math.max(1L, earliestCostCents),
                    opsWithSlack, ordersWithSlack,
                    slackSeconds / 3600.0 / Math.max(1L, opsWithSlack));
        }
    }


    // ************************************************************************
    // Charge et capacité — ce que les ressources font vraiment
    // ************************************************************************

    /**
     * Relevé de charge par famille de ressources, sur le plan tel qu'il est daté.
     *
     * <p>
     * Le coût seul ne dit pas où va le temps. Trois grandeurs par machine, toutes en temps MUR et
     * dont la somme fait exactement la fenêtre d'engagement de la machine :
     * <ul>
     * <li><b>immobilisation de mise en train</b> — de l'instant où la machine se libère à la fin
     * de la mise en train, temps mort du calendrier metteur compris. C'est le poste que
     * `CPT-KKI-007` désigne comme le piège du modèle ;</li>
     * <li><b>attente de chaîne</b> — la machine est prête, l'ordre n'est pas encore arrivé ;</li>
     * <li><b>usinage</b> — le seul temps productif, dont une part peut tomber hors des heures
     * d'ouverture de la machine.</li>
     * </ul>
     *
     * <p>
     * Le besoin est rapporté à côté de la dotation : pour chaque famille, le nombre d'unités qui
     * suffirait si elles étaient utilisées à cent pour cent. L'écart entre ce plancher et la
     * dotation dit laquelle des trois familles contraint réellement le plan.
     */
    public ResourceUsage resourceUsage() {
        return resourceUsage(0L);
    }

    /**
     * @param planningHorizonSeconds horizon sur lequel la CAPACITÉ est comptée. C'est lui qui
     *                               donne son sens au taux de charge : un atelier qui met huit
     *                               ans à écouler un carnet de six mois est chargé à 1600 %, pas
     *                               à 100 %. Zéro = compter sur le makespan réel.
     */
    public ResourceUsage resourceUsage(long planningHorizonSeconds) {
        long horizon = 0L;
        for (int opId = 0; opId < opEnd.length; opId++) {
            horizon = Math.max(horizon, opEnd[opId] - origin);
        }
        long capacityHorizon = planningHorizonSeconds > 0L ? planningHorizonSeconds : horizon;
        double[] load = new double[operationsByMachine.length];

        long setupHold = 0L;
        long chainWait = 0L;
        long run = 0L;
        long machiningWork = 0L;
        long setupWork = 0L;
        int machinesUsed = 0;
        long busiestMachineSpan = 0L;
        long machineOpenSeconds = 0L;

        for (int m = 0; m < operationsByMachine.length; m++) {
            List<Operation> queue = operationsByMachine[m];
            machineOpenSeconds += machineCalendar[m].workedSecondsBefore(origin + horizon)
                    - machineCalendar[m].workedSecondsBefore(origin);
            if (queue.isEmpty()) {
                continue;
            }
            machinesUsed++;
            long freeAt = origin;
            long required = 0L;
            for (Operation op : queue) {
                int opId = (int) op.getId();
                setupHold += setupEndAt[opId] - freeAt;
                chainWait += opStart[opId] - setupEndAt[opId];
                run += opEnd[opId] - opStart[opId];
                machiningWork += op.getDurationSeconds();
                setupWork += setupSecondsOf(opId);
                // CHARGE NOMINALE, et surtout pas le temps observé. Le temps observé inclut
                // toute l'attente accumulée : sur un plan qui met huit ans à écouler six mois de
                // carnet, il donne des taux à deux mille pour cent, qui mesurent l'engorgement et
                // non la capacité. La charge d'un atelier est le travail INTRINSÈQUE qu'il doit
                // absorber : l'usinage, plus le temps mur qu'une mise en train immobilise le
                // poste du seul fait du calendrier de son metteur — sans file d'attente.
                required += nominalHoldSeconds(opId) + op.getDurationSeconds();
                freeAt = opEnd[opId];
            }
            busiestMachineSpan = Math.max(busiestMachineSpan, freeAt - origin);
            long capacity = machineCalendar[m].workedSecondsBefore(origin + capacityHorizon)
                    - machineCalendar[m].workedSecondsBefore(origin);
            load[m] = capacity > 0L ? (double) required / capacity : 0.0;
        }

        int settersUsed = 0;
        long setterOpenSeconds = 0L;
        for (int s = 0; s < setupsBySetter.length; s++) {
            setterOpenSeconds += setterCalendar[s].workedSecondsBefore(origin + horizon)
                    - setterCalendar[s].workedSecondsBefore(origin);
            if (!setupsBySetter[s].isEmpty()) {
                settersUsed++;
            }
        }

        int toolingsUsed = 0;
        long toolingHold = 0L;
        for (List<Operation> queue : setupsByTooling) {
            if (queue.isEmpty()) {
                continue;
            }
            toolingsUsed++;
            for (Operation op : queue) {
                int opId = (int) op.getId();
                toolingHold += setupEndAt[opId] - setupStartAt[opId];
            }
        }

        double[] busy = Arrays.stream(load).filter(value -> value > 0.0).sorted().toArray();
        double mean = Arrays.stream(busy).average().orElse(0.0);
        long overloaded = Arrays.stream(busy).filter(value -> value > 1.0).count();
        return new ResourceUsage(horizon, capacityHorizon, operationsByMachine.length, machinesUsed,
                machineOpenSeconds, setupHold, chainWait, run, machiningWork,
                busiestMachineSpan, setupsBySetter.length, settersUsed, setterOpenSeconds,
                setupWork, setupsByTooling.length, toolingsUsed, toolingHold,
                mean, quantile(busy, 0.5), quantile(busy, 0.9),
                busy.length == 0 ? 0.0 : busy[busy.length - 1], overloaded);
    }

    /**
     * Temps mur pendant lequel une mise en train immobilise son poste, files d'attente exclues :
     * le travail du metteur étiré par son propre calendrier. Seize heures de réglage sur un
     * horaire de quarante heures par semaine prennent plus de soixante heures de poste.
     */
    private long nominalHoldSeconds(int opId) {
        long work = setupSecondsOf(opId);
        if (work <= 0L) {
            return 0L;
        }
        WorkCalendar calendar = setterCalendar[assignedSetterId[opId]];
        long openPerWeek = calendar.workedSecondsBefore(origin + 604_800L)
                - calendar.workedSecondsBefore(origin);
        if (openPerWeek <= 0L) {
            return work;
        }
        return work * 604_800L / openPerWeek;
    }

    private static double quantile(double[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0.0;
        }
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * fraction))];
    }

    /**
     * Un relevé de charge ; toutes les durées sont en secondes.
     *
     * <p>
     * La grandeur qui décide n'est pas la charge MOYENNE mais sa DISTRIBUTION. Un atelier dont
     * tous les postes sont à 80 % et un atelier à 80 % de moyenne avec des pointes au-dessus de
     * 100 % ont la même moyenne et n'ont rien à voir : le premier n'offre rien à équilibrer, le
     * second est exactement le cas où un système d'ordonnancement a de la valeur. D'où
     * {@code overloadedMachines} — le nombre de postes au-dessus de cent pour cent, qui dit si
     * l'instance a un déséquilibre à corriger ou si l'exercice est vide.
     */
    public record ResourceUsage(long horizonSeconds, long capacityHorizonSeconds, int machines,
            int machinesUsed, long machineOpenSeconds, long machineSetupHold, long machineChainWait,
            long machineRun, long machiningWork, long busiestMachineSpan,
            int setters, int settersUsed, long setterOpenSeconds, long setterWork,
            int toolings, int toolingsUsed, long toolingHold,
            double loadMean, double loadP50, double loadP90, double loadMax,
            long overloadedMachines) {

        /** Machines qu'il faudrait si elles étaient prises à cent pour cent du temps d'ouverture. */
        public double machinesNeeded() {
            double perMachine = (double) machineOpenSeconds / Math.max(1, machines);
            return (machineSetupHold + machineRun) / Math.max(1.0, perMachine);
        }

        public double settersNeeded() {
            double perSetter = (double) setterOpenSeconds / Math.max(1, setters);
            return setterWork / Math.max(1.0, perSetter);
        }

        public double toolingsNeeded() {
            return toolingHold / Math.max(1.0, horizonSeconds);
        }

        /** Charge du metteur, rapportée à l'horizon de planification et non au makespan. */
        public double setterLoad() {
            double perSetter = (double) setterOpenSeconds / Math.max(1, setters);
            double capacity = perSetter * capacityHorizonSeconds / Math.max(1L, horizonSeconds);
            return setterWork / Math.max(1.0, capacity * setters);
        }

        public String describe(String label) {
            long engaged = machineSetupHold + machineChainWait + machineRun;
            return String.format(
                    "resources[%s] horizon_d=%.0f busiest_machine_d=%.0f%n"
                            + "  machines   dotation=%d utilisees=%d necessaires=%.0f"
                            + " | mise_en_train=%.1f%% attente_chaine=%.1f%% usinage=%.1f%%"
                            + " (dont productif=%.1f%%)%n"
                            + "  metteurs   dotation=%d utilises=%d necessaires=%.0f"
                            + " | charge=%.1f%% des heures ouvertes%n"
                            + "  outillages dotation=%d utilises=%d necessaires=%.0f"
                            + " | immobilisation=%.1f%% de l_horizon%n"
                            + "  CHARGE     moyenne=%.0f%% p50=%.0f%% p90=%.0f%% max=%.0f%%"
                            + " | postes_au_dessus_de_100%%=%d/%d | metteurs=%.0f%%%n",
                    label, horizonSeconds / 86_400.0, busiestMachineSpan / 86_400.0,
                    machines, machinesUsed, machinesNeeded(),
                    100.0 * machineSetupHold / Math.max(1L, engaged),
                    100.0 * machineChainWait / Math.max(1L, engaged),
                    100.0 * machineRun / Math.max(1L, engaged),
                    100.0 * machiningWork / Math.max(1L, engaged),
                    setters, settersUsed, settersNeeded(),
                    100.0 * setterWork / Math.max(1L, setterOpenSeconds),
                    toolings, toolingsUsed, toolingsNeeded(),
                    100.0 * toolingHold / Math.max(1L, (long) toolings * horizonSeconds),
                    100.0 * loadMean, 100.0 * loadP50, 100.0 * loadP90, 100.0 * loadMax,
                    overloadedMachines, machinesUsed, 100.0 * setterLoad());
        }
    }

    // ************************************************************************
    // Lecture des arcs tendus pour le sélecteur guidé
    // ************************************************************************

    public Order[] sampleTightAdjacentPair(Random random) {
        int machineCount = operationsByMachine.length;
        for (int attempt = 0; attempt < 8; attempt++) {
            List<Operation> onMachine = operationsByMachine[random.nextInt(machineCount)];
            if (onMachine.size() < 2) {
                continue;
            }
            int i = random.nextInt(onMachine.size() - 1);
            Operation current = onMachine.get(i);
            Operation next = onMachine.get(i + 1);
            if (current.getOrder() == next.getOrder()) {
                continue;
            }
            if (current.getOrder().getFreezeLevel() == Order.FreezeLevel.HARD
                    || next.getOrder().getFreezeLevel() == Order.FreezeLevel.HARD) {
                continue;
            }
            // TENDU = c'est la ressource qui retient l'opération suivante, pas sa propre chaîne.
            int nextId = (int) next.getId();
            int chainPredecessor = chainPredecessorId[nextId];
            long chainReadyAt = chainPredecessor >= 0 ? opEnd[chainPredecessor] : origin;
            if (opStart[nextId] > chainReadyAt) {
                return new Order[] { current.getOrder(), next.getOrder() };
            }
        }
        return null;
    }

    /**
     * La solution sur laquelle CE calculateur travaille. Sert à la commande de phase pour
     * vérifier qu'elle tient bien le calculateur du directeur de score courant : `LIVE` est une
     * référence statique, et un solveur peut instancier plusieurs directeurs.
     */
    public JobShopSolution getWorkingSolution() {
        return solution;
    }

    /**
     * Une réaffectation candidate : cette opération, vers ce poste.
     *
     * @param operation opération prise sur un poste chargé
     * @param target    poste compatible moins chargé
     */
    public record Reassignment(Operation operation, Machine target) {
    }

    /**
     * Une réaffectation de METTEUR candidate — mouvement (6) de {@code CPT-KKI-010}.
     *
     * @param operation mise en train prise sur un metteur chargé
     * @param target    metteur COMPÉTENT pour cette machine, et moins chargé
     */
    public record SetterReassignment(Operation operation, Setter target) {
    }

    /** Un échange d'exemplaire d'OUTILLAGE — mouvement (7) de {@code CPT-KKI-010}. */
    public record ToolingReassignment(Operation operation, Tooling target) {
    }

    /**
     * Tire une réaffectation de metteur qui a une chance de soulager le goulot.
     *
     * <p>
     * <b>Pourquoi ce mouvement compte ici.</b> Le metteur est la ressource RARE du modèle : 242
     * metteurs portent 18 486 mises en train, soit ~76 chacun, là où 1000 machines n'en portent
     * que ~18. Un metteur saturé bloque des machines qui, elles, sont libres — et
     * {@code CPT-KKI-007} fait payer ces heures machine perdues au coût horaire du poste.
     *
     * <p>
     * <b>La compétence est un MUR, pas un surcoût.</b> Contrairement à la technologie machine,
     * qui se substitue vers le haut, un metteur qui ne sait pas régler cette machine ne le fera
     * jamais. Le tirage ne propose donc que des metteurs compétents — et il ne s'agit pas d'un
     * filtre appliqué après coup : proposer l'incompétent ferait lever {@code reassignSetter}.
     *
     * <p>
     * <b>Proxy de charge : le nombre de mises en train de la file.</b> Le span (comme pour les
     * machines) ne convient pas ici — un metteur qui règle tard n'est pas forcément chargé, il
     * peut n'avoir qu'une mise en train, placée loin. C'est le VOLUME qui sature un metteur.
     */
    public SetterReassignment sampleOverloadedSetterReassignment(Random random) {
        int setterCount = setupsBySetter.length;
        int busiest = -1;
        int busiestLoad = -1;
        for (int probe = 0; probe < 6; probe++) {
            int candidate = random.nextInt(setterCount);
            int size = setupsBySetter[candidate].size();
            if (size > busiestLoad) {
                busiestLoad = size;
                busiest = candidate;
            }
        }
        if (busiest < 0 || setupsBySetter[busiest].isEmpty()) {
            return null;
        }
        List<Operation> queue = setupsBySetter[busiest];
        Operation op = queue.get(random.nextInt(queue.size()));
        if (op.getOrder().getFreezeLevel() == Order.FreezeLevel.HARD) {
            return null;
        }

        Setter best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (int probe = 0; probe < 6; probe++) {
            Setter candidate = setterList.get(random.nextInt(setterList.size()));
            int candidateId = (int) candidate.getId();
            if (candidateId == busiest || !candidate.canSetUp(op.getMachine())) {
                continue;
            }
            int load = setupsBySetter[candidateId].size();
            if (load < bestLoad) {
                bestLoad = load;
                best = candidate;
            }
        }
        // Un mouvement vers un metteur PLUS chargé n'est pas un rééquilibrage : le laisser
        // passer dépenserait le budget en évaluations dont on sait déjà qu'elles échouent.
        return best != null && bestLoad < busiestLoad ? new SetterReassignment(op, best) : null;
    }

    /**
     * Tire un échange d'exemplaire d'outillage — mouvement (7) de {@code CPT-KKI-010}.
     *
     * <p>
     * L'outillage est la ressource la PLUS rare : 120 exemplaires pour 7 710 emprunts, ~64
     * chacun. Deux exemplaires du même type sont interchangeables ({@code CPT-KKI-009}), donc
     * déplacer une mise en train d'un exemplaire saturé vers un exemplaire libre du même type ne
     * change rien au plan sinon l'attente — c'est un gain sans contrepartie quand il existe.
     *
     * <p>
     * L'exemplaire redevient libre à la FIN de la mise en train, pas à la fin de l'usinage : la
     * file d'un outillage est donc plus courte que celle d'une machine à charge égale, et la
     * contention s'y voit d'autant mieux.
     */
    public ToolingReassignment sampleContendedToolingReassignment(Random random) {
        int toolingCount = setupsByTooling.length;
        int busiest = -1;
        int busiestLoad = -1;
        for (int probe = 0; probe < 6; probe++) {
            int candidate = random.nextInt(toolingCount);
            int size = setupsByTooling[candidate].size();
            if (size > busiestLoad) {
                busiestLoad = size;
                busiest = candidate;
            }
        }
        if (busiest < 0 || setupsByTooling[busiest].isEmpty()) {
            return null;
        }
        List<Operation> queue = setupsByTooling[busiest];
        Operation op = queue.get(random.nextInt(queue.size()));
        if (op.getOrder().getFreezeLevel() == Order.FreezeLevel.HARD) {
            return null;
        }

        // Les exemplaires compatibles sont ceux du TYPE exigé, mémoïsés à la génération : la
        // liste est partagée par toutes les opérations qui exigent ce type.
        List<Tooling> candidates = op.getCompatibleToolings();
        if (candidates == null || candidates.size() < 2) {
            return null;
        }
        Tooling best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (int probe = 0; probe < 6; probe++) {
            Tooling candidate = candidates.get(random.nextInt(candidates.size()));
            int candidateId = (int) candidate.getId();
            if (candidateId == busiest) {
                continue;
            }
            int load = setupsByTooling[candidateId].size();
            if (load < bestLoad) {
                bestLoad = load;
                best = candidate;
            }
        }
        return best != null && bestLoad < busiestLoad ? new ToolingReassignment(op, best) : null;
    }

    /**
     * Tire une réaffectation qui a une chance de rééquilibrer — le SECOND mouvement du paradigme.
     *
     * <p>
     * Symétrique de {@link #sampleTightAdjacentPair} : sans guidage, un tirage uniforme sur
     * 18 486 opérations et 1000 postes désigne presque toujours un déplacement sans intérêt, et
     * l'évaluation est payée pour rien. On cherche donc une opération sur un poste CHARGÉ et une
     * cible compatible MOINS chargée.
     *
     * <p>
     * <b>Le proxy de charge est le span du poste</b> — la date de fin de sa dernière opération.
     * Il est déjà calculé, ne demande aucun état supplémentaire sur le chemin chaud, et c'est
     * exactement la grandeur qui compte ici : un poste dont le span dépasse l'horizon est celui
     * qui retient tout le plan. Une charge nominale exacte serait plus juste mais coûterait un
     * parcours complet à chaque tirage, c'est-à-dire le débit que ce guidage sert à préserver.
     */
    public Reassignment sampleOverloadedReassignment(Random random) {
        int machineCount = operationsByMachine.length;
        int busiest = -1;
        long busiestSpan = Long.MIN_VALUE;
        for (int probe = 0; probe < 6; probe++) {
            int candidate = random.nextInt(machineCount);
            List<Operation> queue = operationsByMachine[candidate];
            if (queue.isEmpty()) {
                continue;
            }
            long span = opEnd[(int) queue.get(queue.size() - 1).getId()];
            if (span > busiestSpan) {
                busiestSpan = span;
                busiest = candidate;
            }
        }
        if (busiest < 0) {
            return null;
        }
        List<Operation> queue = operationsByMachine[busiest];
        Operation op = queue.get(random.nextInt(queue.size()));
        if (op.getOrder().getFreezeLevel() == Order.FreezeLevel.HARD) {
            return null;
        }

        List<Machine> candidates = op.getCompatibleMachines();
        if (candidates.size() < 2) {
            return null;
        }
        Machine best = null;
        long bestSpan = Long.MAX_VALUE;
        for (int probe = 0; probe < 6; probe++) {
            Machine candidate = candidates.get(random.nextInt(candidates.size()));
            if (candidate == op.getMachine()) {
                continue;
            }
            List<Operation> targetQueue = operationsByMachine[(int) candidate.getId()];
            long span = targetQueue.isEmpty()
                    ? origin
                    : opEnd[(int) targetQueue.get(targetQueue.size() - 1).getId()];
            if (span < bestSpan) {
                bestSpan = span;
                best = candidate;
            }
        }
        // Ne proposer que si la cible est RÉELLEMENT moins chargée : sinon le mouvement ne peut
        // qu'aggraver, et le proposer revient à dépenser une évaluation pour un refus certain.
        if (best == null || bestSpan >= busiestSpan) {
            return null;
        }
        return new Reassignment(op, best);
    }

    public int positionOf(Order order) {
        return xPosition[(int) order.getId()];
    }

    // Vue du plan daté — pour que les invariants de fidélité soient vérifiables de l'extérieur
    // sans rejouer une datation parallèle, qui pourrait diverger et masquer le défaut cherché.
    long setupStartOf(int opId) {
        return setupStartAt[opId];
    }

    long setupEndOf(int opId) {
        return setupEndAt[opId];
    }

    long startOf(int opId) {
        return opStart[opId];
    }

    long endOf(int opId) {
        return opEnd[opId];
    }

    // ************************************************************************
    // Réaffectation de ressource — les décisions que le domaine contient (M4)
    // ************************************************************************

    /**
     * Change la machine d'une opération et repropage.
     *
     * <p>
     * Ce n'est pas un hook OptaPlanner : cette version du moteur ne sait pas faire coexister une
     * variable-liste et une variable simple, même sur des classes d'entités différentes
     * (DEC-KKI-004). La réaffectation se fait donc entre deux phases, par une commande de phase
     * personnalisée qui appelle ceci — le coût se paie une fois par phase, pas par mouvement.
     *
     * <p>
     * La compatibilité ASCENDANTE est vérifiée ici et non supposée : une opération ne descend
     * jamais sous son niveau requis, quoi qu'en dise l'appelant.
     */
    public void reassignMachine(Operation op, Machine target) {
        if (!target.canRun(op.getRequiredTechnology(), op.getRequiredLevel())) {
            throw new IllegalArgumentException(
                    "compatibilité ascendante violée : " + op + " ne peut pas tourner sur " + target);
        }
        int opId = (int) op.getId();
        int previous = assignedMachineId[opId];
        int next = (int) target.getId();
        if (previous == next) {
            return;
        }
        PROPAGATIONS.incrementAndGet();
        touchMachine(previous, removeSorted(operationsByMachine[previous], op));
        op.setMachine(target);
        assignedMachineId[opId] = next;
        touchMachine(next, insertSorted(operationsByMachine[next], op));
        flushTouched();
        propagate();
    }

    /**
     * Change le metteur d'une mise en train et repropage. La COMPÉTENCE est vérifiée ici : un
     * metteur qui ne sait pas régler cette machine est un mur, pas un surcoût — contrairement à
     * la technologie, elle ne se substitue pas vers le haut.
     */
    public void reassignSetter(Operation op, Setter target) {
        if (!target.canSetUp(op.getMachine())) {
            throw new IllegalArgumentException(
                    target + " n'a pas la compétence pour régler " + op.getMachine());
        }
        int opId = (int) op.getId();
        int previous = assignedSetterId[opId];
        int next = (int) target.getId();
        if (previous == next) {
            return;
        }
        PROPAGATIONS.incrementAndGet();
        touchSetter(previous, removeSorted(setupsBySetter[previous], op));
        op.setSetter(target);
        assignedSetterId[opId] = next;
        touchSetter(next, insertSorted(setupsBySetter[next], op));
        flushTouched();
        propagate();
    }

    /**
     * Change l'exemplaire d'outillage emprunté par une mise en train et repropage — le mouvement
     * (7) de CPT-KKI-010, « swap sur outillage partagé ».
     *
     * <p>
     * Le TYPE est vérifié ici : deux exemplaires du même type sont interchangeables, deux types
     * différents ne le sont pas. Et l'écriture se fait des DEUX côtés — l'objet du domaine, que
     * lit la passe à froid, et le tableau indexé, que lit la propagation incrémentale. N'en
     * écrire qu'un ferait diverger l'oracle du calcul, et le test différentiel accuserait alors
     * le côté resté juste.
     */
    public void reassignTooling(Operation op, Tooling target) {
        if (op.getRequiredToolingType() == Operation.NO_TOOLING) {
            throw new IllegalArgumentException(op + " n'emprunte aucun outillage");
        }
        if (target.getType() != op.getRequiredToolingType()) {
            throw new IllegalArgumentException(
                    target + " n'est pas du type exigé par " + op + " (type "
                            + op.getRequiredToolingType() + ")");
        }
        int opId = (int) op.getId();
        int previous = assignedToolingId[opId];
        int next = (int) target.getId();
        if (previous == next) {
            return;
        }
        PROPAGATIONS.incrementAndGet();
        touchTooling(previous, removeSorted(setupsByTooling[previous], op));
        op.setTooling(target);
        assignedToolingId[opId] = next;
        touchTooling(next, insertSorted(setupsByTooling[next], op));
        flushTouched();
        propagate();
    }

    // ************************************************************************
    // Hooks OptaPlanner
    // ************************************************************************

    @Override
    public void afterListVariableChanged(Object entity, String variableName, int fromIndex, int toIndex) {
        PROPAGATIONS.incrementAndGet();
        List<Order> sequence = schedule.getOrderSequence();
        int end = Math.min(toIndex, sequence.size());

        // OptaPlanner annonce la PLAGE qui encadre le mouvement, pas les positions qui bougent.
        // Ne retenir que les ordres réellement déplacés valait un facteur 27 sur le débit.
        movedCount = 0;
        for (int i = fromIndex; i < end; i++) {
            Order order = sequence.get(i);
            if (xPosition[(int) order.getId()] != i) {
                if (movedCount == movedOrders.length) {
                    movedOrders = Arrays.copyOf(movedOrders, movedCount * 2);
                }
                movedOrders[movedCount++] = order;
            }
        }
        if (movedCount == 0) {
            return;
        }

        // Retirer AVANT de changer le rang : les files sont triées sur l'ancien rang.
        for (int k = 0; k < movedCount; k++) {
            for (int opId = firstOpIdOf(movedOrders[k]); opId >= 0; opId = chainSuccessorId[opId]) {
                touchMachine(assignedMachineId[opId],
                        removeSorted(operationsByMachine[assignedMachineId[opId]], opById[opId]));
                touchSetter(assignedSetterId[opId],
                        removeSorted(setupsBySetter[assignedSetterId[opId]], opById[opId]));
                if (assignedToolingId[opId] >= 0) {
                    touchTooling(assignedToolingId[opId],
                            removeSorted(setupsByTooling[assignedToolingId[opId]], opById[opId]));
                }
            }
        }
        for (int i = fromIndex; i < end; i++) {
            xPosition[(int) sequence.get(i).getId()] = i;
        }
        for (int k = 0; k < movedCount; k++) {
            int position = xPosition[(int) movedOrders[k].getId()];
            for (int opId = firstOpIdOf(movedOrders[k]); opId >= 0; opId = chainSuccessorId[opId]) {
                rank[opId] = position * RANK_STRIDE + opById[opId].getPassIndex();
                touchMachine(assignedMachineId[opId],
                        insertSorted(operationsByMachine[assignedMachineId[opId]], opById[opId]));
                touchSetter(assignedSetterId[opId],
                        insertSorted(setupsBySetter[assignedSetterId[opId]], opById[opId]));
                if (assignedToolingId[opId] >= 0) {
                    touchTooling(assignedToolingId[opId],
                            insertSorted(setupsByTooling[assignedToolingId[opId]], opById[opId]));
                }
            }
        }
        flushTouched();
        propagate();
    }

    private void touchMachine(int machine, int fromIndex) {
        if (!machineTouched[machine]) {
            machineTouched[machine] = true;
            touchedMachines[touchedMachineCount++] = machine;
        }
        touchedFromMachine[machine] = Math.min(touchedFromMachine[machine], fromIndex);
    }

    private void touchSetter(int setter, int fromIndex) {
        if (!setterTouched[setter]) {
            setterTouched[setter] = true;
            touchedSetters[touchedSetterCount++] = setter;
        }
        touchedFromSetter[setter] = Math.min(touchedFromSetter[setter], fromIndex);
    }

    private void touchTooling(int tooling, int fromIndex) {
        if (tooling < 0) {
            return;
        }
        if (!toolingTouched[tooling]) {
            toolingTouched[tooling] = true;
            touchedToolings[touchedToolingCount++] = tooling;
        }
        touchedFromTooling[tooling] = Math.min(touchedFromTooling[tooling], fromIndex);
    }

    /**
     * Reconstruit les chaînages des ressources touchées et enfile ce qui peut avoir bougé.
     *
     * <p>
     * Le chaînage se refait INTÉGRALEMENT : {@code relink*} réécrit les liens précédent/suivant,
     * et l'opération qui précède le point d'insertion voit bien son successeur changer. Mais sa
     * DATE, elle, ne dépend que de son prédécesseur — inchangé — donc elle n'a pas à être
     * recalculée. C'est toute la différence entre les deux boucles.
     */
    private void flushTouched() {
        for (int t = 0; t < touchedMachineCount; t++) {
            int m = touchedMachines[t];
            relinkMachine(operationsByMachine[m]);
            enqueueFrom(operationsByMachine[m], touchedFromMachine[m]);
            machineTouched[m] = false;
            touchedFromMachine[m] = Integer.MAX_VALUE;
        }
        touchedMachineCount = 0;
        for (int t = 0; t < touchedSetterCount; t++) {
            int s = touchedSetters[t];
            relinkSetter(setupsBySetter[s]);
            enqueueFrom(setupsBySetter[s], touchedFromSetter[s]);
            setterTouched[s] = false;
            touchedFromSetter[s] = Integer.MAX_VALUE;
        }
        touchedSetterCount = 0;
        for (int t = 0; t < touchedToolingCount; t++) {
            int tool = touchedToolings[t];
            relinkTooling(setupsByTooling[tool]);
            enqueueFrom(setupsByTooling[tool], touchedFromTooling[tool]);
            toolingTouched[tool] = false;
            touchedFromTooling[tool] = Integer.MAX_VALUE;
        }
        touchedToolingCount = 0;
    }

    /** Enfile la file à partir de {@code from}, ou en entier si la position est inconnue. */
    private void enqueueFrom(List<Operation> queue, int from) {
        int start = from == Integer.MAX_VALUE ? 0 : Math.max(0, from);
        for (int i = start; i < queue.size(); i++) {
            enqueue((int) queue.get(i).getId());
        }
    }

    private void propagate() {
        int dirtyOrderCount = 0;
        while (!worklist.isEmpty()) {
            Operation op = worklist.poll();
            int opId = (int) op.getId();
            queued[opId] = false;
            if (!recomputeOperation(opId, true)) {
                continue;
            }
            Order order = op.getOrder();
            int oi = (int) order.getId();
            if (!orderDirty[oi]) {
                orderDirty[oi] = true;
                if (dirtyOrderCount == dirtyOrders.length) {
                    dirtyOrders = Arrays.copyOf(dirtyOrders, dirtyOrderCount * 2);
                }
                dirtyOrders[dirtyOrderCount++] = order;
            }
            enqueue(chainSuccessorId[opId]);
            enqueue(nextOnMachineId[opId]);
            enqueue(nextOnSetterId[opId]);
            enqueue(nextOnToolingId[opId]);
        }
        for (int k = 0; k < dirtyOrderCount; k++) {
            recomputeOrderCost(dirtyOrders[k], true);
            orderDirty[(int) dirtyOrders[k].getId()] = false;
        }
    }

    private void enqueue(int opId) {
        if (opId >= 0 && !queued[opId]) {
            queued[opId] = true;
            ENQUEUED_OPERATIONS.incrementAndGet();
            worklist.add(opById[opId]);
        }
    }

    @Override
    public HardSoftLongScore calculateScore() {
        CALCULATE_SCORE_CALLS.incrementAndGet();
        return HardSoftLongScore.of(hardTotal, softTotalCents);
    }

    @Override
    public void beforeListVariableChanged(Object entity, String variableName, int fromIndex, int toIndex) {
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
    public void beforeEntityRemoved(Object entity) {
    }

    @Override
    public void afterEntityRemoved(Object entity) {
    }
}
