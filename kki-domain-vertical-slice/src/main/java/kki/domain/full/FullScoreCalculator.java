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
 * Calculateur de score du domaine COMPLET (PIL-KKI-004), incrémental.
 *
 * <p>
 * Six mécanismes, tous présents, aucun simplifié : axe Z par clé (ordre, passe) · matrice de mise
 * en train asymétrique sur (article, passe), nulle entre deux passages du même article · deux
 * calendriers indépendants, la mise en train ne consommant que du temps metteur mais bloquant la
 * machine trous compris · compatibilité machine ascendante avec coût horaire croissant · trois
 * paliers de gel, le dur au score DUR · coûts retard et avance quadratiques, le retard pondéré
 * par la priorité, l'avance jamais. Tout en centimes, une seule unité de bout en bout.
 *
 * <p>
 * <b>Navigation par identifiant, jamais par référence d'objet.</b> Depuis que l'opération est une
 * entité (M2), le cloneur de solution clone les opérations mais pas les ordres : un
 * {@code Order.getOperations()} renvoie les opérations d'ORIGINE, pas celles de la solution de
 * travail. Tout ce qui suit passe donc par des tableaux indexés sur l'identifiant, construits au
 * {@code resetWorkingSolution} depuis la liste d'opérations de la solution de travail. C'est à la
 * fois la correction de ce piège et la mémoïsation du chemin chaud — les deux coïncident.
 *
 * <p>
 * Discipline de propagation héritée de REQ-KKI-008 : file de priorité sur un rang topologique
 * pré-calculé, donc chaque nœud est finalisé dès son premier dépilement.
 */
public final class FullScoreCalculator implements IncrementalScoreCalculator<JobShopSolution, HardSoftLongScore> {

    /** Assez grand pour que le rang reste (position X, passe) sans collision. */
    private static final int RANK_STRIDE = 16;

    public static final AtomicLong CALCULATE_SCORE_CALLS = new AtomicLong();
    public static final AtomicLong DIRTY_OPERATIONS = new AtomicLong();
    public static final AtomicLong ORDER_COMPLETION_CHANGES = new AtomicLong();
    public static final AtomicLong PROPAGATIONS = new AtomicLong();

    /**
     * Référence vivante, pour que le sélecteur de mouvements guidé (M3) lise les arcs tendus sans
     * reconstruire l'état. Couplage assumé et documenté : le sélecteur ne peut pas recalculer les
     * dates à chaque pas sans ruiner le débit qu'il sert à améliorer.
     */
    public static volatile FullScoreCalculator LIVE;

    private JobShopSolution solution;
    private Schedule schedule;
    private SetupMatrix setupMatrix;
    private long origin;

    private Operation[] opById;
    private Order[] orderById;
    private int[] xPosition;
    private int[] rank;
    private int[] chainPredecessorId;
    private int[] chainSuccessorId;
    private int[] lastOpIdOfOrder;
    private long[] opStart;
    private long[] opEnd;
    private long[] opResourceCents;
    private long[] orderCents;
    private long[] hardViolation;
    private long[] machineHourlyCents;
    private int[] prevOnMachineId;
    private int[] nextOnMachineId;
    private int[] assignedMachineId;
    private boolean[] queued;
    private List<Operation>[] operationsByMachine;
    private PriorityQueue<Operation> worklist;
    private long softTotalCents;
    private long hardTotal;

    // Tampons réutilisés : allouer par évaluation, à des dizaines de milliers d'évaluations par
    // seconde, se paie en ramasse-miettes.
    private Order[] movedOrders = new Order[16];
    private int movedCount;
    private int[] touchedMachines;
    private boolean[] machineTouched;
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

        opById = new Operation[opCount];
        for (Operation op : operations) {
            opById[(int) op.getId()] = op;
        }
        orderById = new Order[orderCount];
        for (Order order : workingSolution.getOrderList()) {
            orderById[(int) order.getId()] = order;
        }

        xPosition = new int[orderCount];
        Arrays.fill(xPosition, -1);
        rank = new int[opCount];
        opStart = new long[opCount];
        opEnd = new long[opCount];
        opResourceCents = new long[opCount];
        orderCents = new long[orderCount];
        hardViolation = new long[orderCount];
        prevOnMachineId = new int[opCount];
        nextOnMachineId = new int[opCount];
        assignedMachineId = new int[opCount];
        queued = new boolean[opCount];
        touchedMachines = new int[machineCount];
        machineTouched = new boolean[machineCount];
        orderDirty = new boolean[orderCount];

        // Chaînes reconstruites depuis la solution DE TRAVAIL, jamais depuis Order.getOperations()
        // — voir le commentaire de classe sur le clonage.
        chainPredecessorId = new int[opCount];
        chainSuccessorId = new int[opCount];
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

        machineHourlyCents = new long[machineCount];
        for (Machine machine : workingSolution.getMachineList()) {
            machineHourlyCents[(int) machine.getId()] = machine.getHourlyCostCents();
        }

        operationsByMachine = new List[machineCount];
        for (int m = 0; m < machineCount; m++) {
            operationsByMachine[m] = new ArrayList<>();
        }
        List<Order> sequence = schedule.getOrderSequence();
        for (int i = 0; i < sequence.size(); i++) {
            xPosition[(int) sequence.get(i).getId()] = i;
        }
        for (Operation op : operations) {
            rank[(int) op.getId()] =
                    xPosition[(int) op.getOrder().getId()] * RANK_STRIDE + op.getPassIndex();
            assignedMachineId[(int) op.getId()] = (int) op.getMachineId();
            operationsByMachine[(int) op.getMachineId()].add(op);
        }
        for (List<Operation> onMachine : operationsByMachine) {
            onMachine.sort((a, b) -> Integer.compare(rank[(int) a.getId()], rank[(int) b.getId()]));
            relink(onMachine);
        }

        worklist = new PriorityQueue<>((a, b) -> Integer.compare(rank[(int) a.getId()], rank[(int) b.getId()]));
        LIVE = this;
        fullRebuild();
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
        int last = lastOpIdOfOrder[(int) order.getId()];
        int first = last;
        while (first >= 0 && chainPredecessorId[first] >= 0) {
            first = chainPredecessorId[first];
        }
        return first;
    }

    private void relink(List<Operation> onMachine) {
        int previous = -1;
        for (Operation op : onMachine) {
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

    /** Insertion à la bonne place dans une liste déjà triée par rang — pas de tri complet. */
    private void insertSorted(List<Operation> onMachine, Operation op) {
        int key = rank[(int) op.getId()];
        int low = 0;
        int high = onMachine.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (rank[(int) onMachine.get(mid).getId()] < key) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        onMachine.add(low, op);
    }

    private boolean recomputeOperation(int opId, boolean track) {
        Operation op = opById[opId];
        int machinePredecessor = prevOnMachineId[opId];
        long machineFreeAt = machinePredecessor >= 0 ? opEnd[machinePredecessor] : origin;
        long setupSeconds = machinePredecessor >= 0
                ? setupMatrix.secondsBetween(opById[machinePredecessor].getSetupKey(), op.getSetupKey())
                : setupMatrix.coldStartSeconds(op.getSetupKey());

        long setupEnd = SetterCalendar.setupEnd(machineFreeAt, setupSeconds);
        long machineIdle = setupEnd - machineFreeAt - setupSeconds;

        int chainPredecessor = chainPredecessorId[opId];
        long chainReadyAt = chainPredecessor >= 0 ? opEnd[chainPredecessor] : origin;

        long start = Math.max(setupEnd, chainReadyAt);
        long end = start + op.getDurationSeconds();
        long resourceCents = CostModel.resourceCents(setupSeconds, machineIdle,
                machineHourlyCents[assignedMachineId[opId]]);

        boolean changed = start != opStart[opId] || end != opEnd[opId]
                || resourceCents != opResourceCents[opId];
        if (changed && track) {
            DIRTY_OPERATIONS.incrementAndGet();
        }
        softTotalCents -= resourceCents - opResourceCents[opId];
        opResourceCents[opId] = resourceCents;
        opStart[opId] = start;
        opEnd[opId] = end;
        return changed;
    }

    private void recomputeOrderCost(Order order, boolean track) {
        int oi = (int) order.getId();
        long completion = opEnd[lastOpIdOfOrder[oi]];
        long cents = orderCostCents(order, completion);
        if (cents != orderCents[oi]) {
            if (track) {
                ORDER_COMPLETION_CHANGES.incrementAndGet();
            }
            softTotalCents -= cents - orderCents[oi];
            orderCents[oi] = cents;
        }
        if (order.getFreezeLevel() == Order.FreezeLevel.HARD) {
            // Déplacer un ordre déjà lancé n'est pas un surcoût, c'est une faute : elle pèse sur
            // le DUR, que le solveur ne peut jamais échanger contre du souple.
            long violation = Math.abs(completion - order.getReferenceCompletionEpochSec());
            hardTotal -= violation - hardViolation[oi];
            hardViolation[oi] = violation;
        }
    }

    private long orderCostCents(Order order, long completion) {
        return CostModel.orderCents(order, completion);
    }

    /** ORACLE — balayage complet à froid, indépendant de tout état incrémental. */
    public HardSoftLongScore fullSweepScore() {
        int machineCount = solution.getMachineList().size();
        long[] machineFree = new long[machineCount];
        int[] lastKeyOnMachine = new int[machineCount];
        Arrays.fill(machineFree, origin);
        Arrays.fill(lastKeyOnMachine, -1);
        long[] end = new long[opById.length];
        long soft = 0L;
        long hard = 0L;

        for (Order order : schedule.getOrderSequence()) {
            long chainReadyAt = origin;
            for (int opId = firstOpIdOf(order); opId >= 0; opId = chainSuccessorId[opId]) {
                Operation op = opById[opId];
                int m = (int) op.getMachineId();
                long setupSeconds = lastKeyOnMachine[m] < 0
                        ? setupMatrix.coldStartSeconds(op.getSetupKey())
                        : setupMatrix.secondsBetween(lastKeyOnMachine[m], op.getSetupKey());
                long setupEnd = SetterCalendar.setupEnd(machineFree[m], setupSeconds);
                long machineIdle = setupEnd - machineFree[m] - setupSeconds;
                long start = Math.max(setupEnd, chainReadyAt);
                long finish = start + op.getDurationSeconds();
                soft -= CostModel.resourceCents(setupSeconds, machineIdle, machineHourlyCents[m]);
                machineFree[m] = finish;
                lastKeyOnMachine[m] = op.getSetupKey();
                chainReadyAt = finish;
                end[opId] = finish;
            }
            soft -= orderCostCents(order, chainReadyAt);
            if (order.getFreezeLevel() == Order.FreezeLevel.HARD) {
                hard -= Math.abs(chainReadyAt - order.getReferenceCompletionEpochSec());
            }
        }
        return HardSoftLongScore.of(hard, soft);
    }

    // ************************************************************************
    // M3 — lecture des arcs tendus pour le sélecteur guidé
    // ************************************************************************

    /**
     * Tire au sort une paire d'ordres <b>adjacents sur une ressource partagée</b> et <b>tendus</b>
     * dessus : la seconde opération démarre exactement quand la première finit, l'arc disjonctif
     * est donc contraignant. Échanger ces deux ordres inverse cet arc — et quand la paire ne
     * partage que cette machine, c'est exactement l'inversion d'arc de M3.
     *
     * @return deux ordres distincts, ou {@code null} si le tirage n'a rien trouvé
     */
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
            // Un ordre à verrou dur ne se déplace pas : inutile de le proposer (CPT-KKI-004).
            if (current.getOrder().getFreezeLevel() == Order.FreezeLevel.HARD
                    || next.getOrder().getFreezeLevel() == Order.FreezeLevel.HARD) {
                continue;
            }
            // TENDU = c'est la MACHINE qui retient l'opération suivante, pas sa propre chaîne.
            // Le critère naïf « démarre quand la précédente finit » ne marche pas ici : il y a
            // toujours une mise en train entre deux opérations d'une ressource, donc l'égalité
            // n'est jamais atteinte et le sélecteur ne trouvait AUCUNE paire (attrapé par test).
            // Si l'opération démarre plus tard que sa chaîne ne l'exige, c'est l'arc disjonctif
            // qui est contraignant — et l'inverser peut changer le coût.
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

    // ************************************************************************
    // Hooks OptaPlanner
    // ************************************************************************

    @Override
    public void afterListVariableChanged(Object entity, String variableName, int fromIndex, int toIndex) {
        PROPAGATIONS.incrementAndGet();
        List<Order> sequence = schedule.getOrderSequence();
        int end = Math.min(toIndex, sequence.size());

        // OptaPlanner annonce la PLAGE qui encadre le mouvement, pas les positions qui bougent :
        // un échange entre 200 et 4800 déclare 4600 indices quand deux ordres changent de place.
        // Ne retenir que les ordres réellement déplacés valait un facteur 27 sur le débit.
        movedCount = 0;
        for (int i = fromIndex; i < end; i++) {
            Order order = sequence.get(i);
            int oi = (int) order.getId();
            if (xPosition[oi] != i) {
                if (movedCount == movedOrders.length) {
                    movedOrders = Arrays.copyOf(movedOrders, movedCount * 2);
                }
                movedOrders[movedCount++] = order;
            }
        }
        if (movedCount == 0) {
            return;
        }

        // Retirer AVANT de changer le rang : la liste machine est triée sur l'ancien rang.
        for (int k = 0; k < movedCount; k++) {
            for (int opId = firstOpIdOf(movedOrders[k]); opId >= 0; opId = chainSuccessorId[opId]) {
                operationsByMachine[assignedMachineId[opId]].remove(opById[opId]);
                touchMachine(assignedMachineId[opId]);
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
            }
        }
        flushTouchedMachines();
        propagate();
    }

    @Override
    public void afterVariableChanged(Object entity, String variableName) {
        if (!(entity instanceof Operation op)) {
            return;
        }
        PROPAGATIONS.incrementAndGet();
        int opId = (int) op.getId();
        int previousMachine = assignedMachineId[opId];
        int newMachine = (int) op.getMachineId();
        if (previousMachine == newMachine) {
            return;
        }
        operationsByMachine[previousMachine].remove(op);
        assignedMachineId[opId] = newMachine;
        insertSorted(operationsByMachine[newMachine], op);
        touchMachine(previousMachine);
        touchMachine(newMachine);
        flushTouchedMachines();
        propagate();
    }

    private int touchedCount;

    private void touchMachine(int machine) {
        if (!machineTouched[machine]) {
            machineTouched[machine] = true;
            touchedMachines[touchedCount++] = machine;
        }
    }

    private void flushTouchedMachines() {
        for (int t = 0; t < touchedCount; t++) {
            int m = touchedMachines[t];
            List<Operation> onMachine = operationsByMachine[m];
            relink(onMachine);
            for (Operation op : onMachine) {
                enqueue((int) op.getId());
            }
            machineTouched[m] = false;
        }
        touchedCount = 0;
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
    public void beforeEntityRemoved(Object entity) {
    }

    @Override
    public void afterEntityRemoved(Object entity) {
    }
}
