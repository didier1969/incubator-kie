package kki.domain.full;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

/**
 * Calculateur de score du domaine complet (PIL-KKI-004), incrémental.
 *
 * <p>
 * <b>Trois familles de prédécesseurs, pas deux.</b> Une opération attend sa chaîne (la passe
 * précédente du même ordre), sa machine (l'opération précédente sur la ressource) et, depuis que
 * le metteur en train est une ressource comptable, <b>son metteur</b> (la mise en train
 * précédente confiée au même homme). Les trois files sont ordonnées par le même rang topologique
 * {@code (position X, passe)}, donc toutes les arêtes vont du rang faible vers le rang fort :
 * l'acyclicité reste gratuite et chaque nœud se finalise dès son premier dépilement.
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
public final class FullScoreCalculator implements IncrementalScoreCalculator<JobShopSolution, HardSoftLongScore> {

    /** Assez grand pour que le rang reste (position X, passe) sans collision. */
    private static final int RANK_STRIDE = 16;

    public static final AtomicLong CALCULATE_SCORE_CALLS = new AtomicLong();
    public static final AtomicLong DIRTY_OPERATIONS = new AtomicLong();
    public static final AtomicLong ORDER_COMPLETION_CHANGES = new AtomicLong();
    public static final AtomicLong PROPAGATIONS = new AtomicLong();

    /** Référence vivante, pour que le sélecteur guidé lise les arcs tendus sans tout recalculer. */
    public static volatile FullScoreCalculator LIVE;

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

    private int[] prevOnMachineId;
    private int[] nextOnMachineId;
    private int[] assignedMachineId;
    private int[] prevOnSetterId;
    private int[] nextOnSetterId;
    private int[] assignedSetterId;

    private List<Operation>[] operationsByMachine;
    private List<Operation>[] setupsBySetter;
    private PriorityQueue<Operation> worklist;
    private boolean[] queued;
    private long softTotalCents;
    private long hardTotal;

    // Tampons réutilisés : allouer par évaluation, à des dizaines de milliers d'évaluations par
    // seconde, se paie en ramasse-miettes.
    private Order[] movedOrders = new Order[16];
    private int movedCount;
    private int[] touchedMachines;
    private boolean[] machineTouched;
    private int touchedMachineCount;
    private int[] touchedSetters;
    private boolean[] setterTouched;
    private int touchedSetterCount;
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
        queued = new boolean[opCount];
        touchedMachines = new int[machineCount];
        machineTouched = new boolean[machineCount];
        touchedSetters = new int[setterCount];
        setterTouched = new boolean[setterCount];
        orderDirty = new boolean[orderCount];

        buildChains(operations, orderCount);

        machineHourlyCents = new long[machineCount];
        machineCalendar = new WorkCalendar[machineCount];
        for (Machine machine : workingSolution.getMachineList()) {
            machineHourlyCents[(int) machine.getId()] = machine.getHourlyCostCents();
            machineCalendar[(int) machine.getId()] = machine.getCalendar();
        }
        setterCalendar = new WorkCalendar[setterCount];
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

        List<Order> sequence = schedule.getOrderSequence();
        for (int i = 0; i < sequence.size(); i++) {
            xPosition[(int) sequence.get(i).getId()] = i;
        }
        for (Operation op : operations) {
            int opId = (int) op.getId();
            rank[opId] = xPosition[(int) op.getOrder().getId()] * RANK_STRIDE + op.getPassIndex();
            assignedMachineId[opId] = (int) op.getMachineId();
            assignedSetterId[opId] = (int) op.getSetter().getId();
            operationsByMachine[assignedMachineId[opId]].add(op);
            setupsBySetter[assignedSetterId[opId]].add(op);
        }
        for (List<Operation> queue : operationsByMachine) {
            queue.sort(this::byRank);
        }
        for (List<Operation> queue : setupsBySetter) {
            queue.sort(this::byRank);
        }
        for (int m = 0; m < machineCount; m++) {
            relinkMachine(operationsByMachine[m]);
        }
        for (int s = 0; s < setterCount; s++) {
            relinkSetter(setupsBySetter[s]);
        }

        worklist = new PriorityQueue<>(this::byRank);
        LIVE = this;
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

    private void insertSorted(List<Operation> queue, Operation op) {
        int key = rank[(int) op.getId()];
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
        queue.add(low, op);
    }

    /**
     * Date une opération et recalcule sa part de coût ressource.
     *
     * <p>
     * L'ordre des attentes n'est pas arbitraire : la mise en train ne peut commencer que lorsque
     * la machine EST libre ET que le metteur EST libre, puis elle consomme le temps ouvert DU
     * METTEUR. La machine, elle, reste immobilisée depuis l'instant où elle s'est libérée —
     * c'est ce décalage que CPT-KKI-007 fait payer au coût horaire machine.
     */
    private boolean recomputeOperation(int opId, boolean track) {
        Operation op = opById[opId];
        int machineId = assignedMachineId[opId];
        int setterId = assignedSetterId[opId];

        int machinePredecessor = prevOnMachineId[opId];
        long machineFreeAt = machinePredecessor >= 0 ? opEnd[machinePredecessor] : origin;
        int setterPredecessor = prevOnSetterId[opId];
        long setterFreeAt = setterPredecessor >= 0 ? setupEndAt[setterPredecessor] : origin;

        long setupSeconds = machinePredecessor >= 0
                ? setupMatrix.secondsBetween(opById[machinePredecessor].getSetupKey(), op.getSetupKey())
                : setupMatrix.coldStartSeconds(op.getSetupKey());

        long setupReadyAt = Math.max(machineFreeAt, setterFreeAt);
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
    public ColdSweep coldSweep() {
        int machineCount = machineCalendar.length;
        int setterCount = setterCalendar.length;
        long[] machineFree = new long[machineCount];
        long[] setterFree = new long[setterCount];
        int[] lastKeyOnMachine = new int[machineCount];
        Arrays.fill(machineFree, origin);
        Arrays.fill(setterFree, origin);
        Arrays.fill(lastKeyOnMachine, -1);

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
                        : setupMatrix.secondsBetween(lastKeyOnMachine[m], op.getSetupKey());
                long setupEnd = setterCalendar[s]
                        .occupancyEnd(Math.max(machineFree[m], setterFree[s]), setupSeconds);
                long machineIdle = setupEnd - machineFree[m] - setupSeconds;
                long finish = machineCalendar[m]
                        .occupancyEnd(Math.max(setupEnd, chainReadyAt), op.getDurationSeconds());

                setter += setupSeconds * CostModel.SETTER_CENTS_PER_HOUR / 3600L;
                idle += machineIdle * machineHourlyCents[m] / 3600L;
                machineFree[m] = finish;
                setterFree[s] = setupEnd;
                lastKeyOnMachine[m] = op.getSetupKey();
                chainReadyAt = finish;
            }
            completions[(int) order.getId()] = chainReadyAt;
            long total = CostModel.orderCents(order, chainReadyAt);
            long freeze = order.getFreezeLevel() == Order.FreezeLevel.SOFT
                    ? total - CostModel.orderCents(asFree(order), chainReadyAt)
                    : 0L;
            softFreeze += freeze;
            if (chainReadyAt > order.getDueEpochSec()) {
                tardiness += total - freeze;
            } else {
                earliness += total - freeze;
            }
            hard -= CostModel.hardViolation(order, chainReadyAt);
        }
        return new ColdSweep(setter, idle, tardiness, earliness, softFreeze, hard, completions);
    }

    /** Le même ordre au palier libre — isole la part de pénalité de stabilité. */
    private static Order asFree(Order order) {
        return new Order(order.getId(), order.getArticleId(), order.getPriorityWeight(),
                order.getDueEpochSec(), Order.FreezeLevel.FREE, order.getReferenceCompletionEpochSec());
    }

    public record ColdSweep(long setter, long machineIdle, long tardiness, long earliness,
            long softFreeze, long hard, long[] completions) {

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
                            + "tardiness_over_physical=%.0f%n",
                    label, total / 100.0, setter / 100.0, machineIdle / 100.0, tardiness / 100.0,
                    earliness / 100.0, softFreeze / 100.0,
                    (double) tardiness / Math.max(1L, setter + machineIdle));
        }
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
        operationsByMachine[previous].remove(op);
        op.setMachine(target);
        assignedMachineId[opId] = next;
        insertSorted(operationsByMachine[next], op);
        touchMachine(previous);
        touchMachine(next);
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
        setupsBySetter[previous].remove(op);
        op.setSetter(target);
        assignedSetterId[opId] = next;
        insertSorted(setupsBySetter[next], op);
        touchSetter(previous);
        touchSetter(next);
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
                operationsByMachine[assignedMachineId[opId]].remove(opById[opId]);
                setupsBySetter[assignedSetterId[opId]].remove(opById[opId]);
                touchMachine(assignedMachineId[opId]);
                touchSetter(assignedSetterId[opId]);
            }
        }
        for (int i = fromIndex; i < end; i++) {
            xPosition[(int) sequence.get(i).getId()] = i;
        }
        for (int k = 0; k < movedCount; k++) {
            int position = xPosition[(int) movedOrders[k].getId()];
            for (int opId = firstOpIdOf(movedOrders[k]); opId >= 0; opId = chainSuccessorId[opId]) {
                rank[opId] = position * RANK_STRIDE + opById[opId].getPassIndex();
                insertSorted(operationsByMachine[assignedMachineId[opId]], opById[opId]);
                insertSorted(setupsBySetter[assignedSetterId[opId]], opById[opId]);
            }
        }
        flushTouched();
        propagate();
    }

    private void touchMachine(int machine) {
        if (!machineTouched[machine]) {
            machineTouched[machine] = true;
            touchedMachines[touchedMachineCount++] = machine;
        }
    }

    private void touchSetter(int setter) {
        if (!setterTouched[setter]) {
            setterTouched[setter] = true;
            touchedSetters[touchedSetterCount++] = setter;
        }
    }

    private void flushTouched() {
        for (int t = 0; t < touchedMachineCount; t++) {
            int m = touchedMachines[t];
            relinkMachine(operationsByMachine[m]);
            for (Operation op : operationsByMachine[m]) {
                enqueue((int) op.getId());
            }
            machineTouched[m] = false;
        }
        touchedMachineCount = 0;
        for (int t = 0; t < touchedSetterCount; t++) {
            int s = touchedSetters[t];
            relinkSetter(setupsBySetter[s]);
            for (Operation op : setupsBySetter[s]) {
                enqueue((int) op.getId());
            }
            setterTouched[s] = false;
        }
        touchedSetterCount = 0;
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
        }
        for (int k = 0; k < dirtyOrderCount; k++) {
            recomputeOrderCost(dirtyOrders[k], true);
            orderDirty[(int) dirtyOrders[k].getId()] = false;
        }
    }

    private void enqueue(int opId) {
        if (opId >= 0 && !queued[opId]) {
            queued[opId] = true;
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
