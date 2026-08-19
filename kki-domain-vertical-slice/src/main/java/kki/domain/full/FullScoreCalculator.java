package kki.domain.full;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

/**
 * Calculateur de score du domaine COMPLET (PIL-KKI-004), incrémental.
 *
 * <p>
 * Six mécanismes, tous présents, aucun simplifié :
 * <ol>
 * <li><b>Axe Z</b> — un ordre peut repasser sur la même machine ; l'identité d'une opération est
 * (ordre, passe).</li>
 * <li><b>Mise en train</b> — matrice asymétrique sur (article, passe), nulle entre deux passages
 * du même article.</li>
 * <li><b>Deux calendriers</b> — la mise en train ne consomme que du temps metteur, mais bloque
 * la machine trous compris.</li>
 * <li><b>Compatibilité machine</b> — ascendante, coût horaire croissant, qui entre dans
 * l'objectif par les heures machine perdues.</li>
 * <li><b>Trois paliers de gel</b> — dur au score DUR, souple pénalisé, libre.</li>
 * <li><b>Coûts retard et avance</b> — quadratiques, le retard pondéré par la priorité, l'avance
 * jamais.</li>
 * </ol>
 *
 * <p>
 * Tout est compté en <b>centimes</b>, une seule unité de bout en bout : sans ça, comparer des
 * heures de metteur à des heures machine et à des quadratiques de retard n'a pas de sens.
 *
 * <p>
 * Discipline de propagation héritée de REQ-KKI-008 : file de priorité sur
 * {@code (xPosition, passe)}, ordre topologique du graphe, donc chaque nœud est finalisé dès son
 * premier dépilement. Le rattachement aux chaînes machine est refait par tri sur les seules
 * machines touchées par le mouvement — à ~22 opérations par machine, trier est négligeable et
 * évidemment correct, là où un raccommodage de liens l'est beaucoup moins.
 */
public final class FullScoreCalculator implements IncrementalScoreCalculator<JobShopSolution, HardSoftLongScore> {

    /** 250 CHF de l'heure de metteur en train, en centimes. */
    private static final long SETTER_CENTS_PER_HOUR = 25_000L;
    /** Retard : quadratique en heures, pondéré par la priorité. 10 CHF par heure² et par point. */
    private static final long TARDINESS_CENTS_PER_HOUR2 = 1_000L;
    /** Avance : quadratique en heures, divisée par 10, JAMAIS pondérée par la priorité. */
    private static final long EARLINESS_CENTS_PER_HOUR2 = 100L;
    /** Gel souple : écart au dernier plan publié, 5 CHF par heure de dérive. */
    private static final long SOFT_FREEZE_CENTS_PER_HOUR = 500L;

    /** Nombre d'appels à calculateScore — le débit du solveur. */
    public static final AtomicLong CALCULATE_SCORE_CALLS = new AtomicLong();
    /** Opérations réellement redatées, cumulées. */
    public static final AtomicLong DIRTY_OPERATIONS = new AtomicLong();
    /** Ordres dont la date de fin a bougé — la seule part du travail qui change le coût. */
    public static final AtomicLong ORDER_COMPLETION_CHANGES = new AtomicLong();
    /** Nombre de propagations, pour ramener les compteurs ci-dessus au mouvement. */
    public static final AtomicLong PROPAGATIONS = new AtomicLong();

    private JobShopSolution solution;
    private Schedule schedule;
    private SetupMatrix setupMatrix;
    private long origin;

    private int[] xPosition;
    private long[] opStart;
    private long[] opEnd;
    private long[] opResourceCents;
    private long[] orderCents;
    private long[] machineHourlyCents;
    private Operation[] prevOnMachine;
    private Operation[] nextOnMachine;
    private long[] hardViolation;
    private boolean[] queued;
    private List<Operation>[] operationsByMachine;
    private PriorityQueue<Operation> worklist;
    private long softTotalCents;
    private long hardTotal;

    // Tampons réutilisés d'un mouvement à l'autre : allouer un HashSet par évaluation, à
    // plusieurs dizaines de milliers d'évaluations par seconde, se paie en ramasse-miettes.
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

        int opCount = workingSolution.getOperationList().size();
        int orderCount = workingSolution.getOrderList().size();
        int machineCount = workingSolution.getMachineList().size();

        xPosition = new int[orderCount];
        Arrays.fill(xPosition, -1);
        opStart = new long[opCount];
        opEnd = new long[opCount];
        opResourceCents = new long[opCount];
        orderCents = new long[orderCount];
        prevOnMachine = new Operation[opCount];
        nextOnMachine = new Operation[opCount];
        hardViolation = new long[orderCount];
        queued = new boolean[opCount];
        touchedMachines = new int[machineCount];
        machineTouched = new boolean[machineCount];
        orderDirty = new boolean[orderCount];

        machineHourlyCents = new long[machineCount];
        for (Machine machine : workingSolution.getMachineList()) {
            machineHourlyCents[(int) machine.getId()] = machine.getHourlyCostCents();
        }

        operationsByMachine = new List[machineCount];
        for (int m = 0; m < machineCount; m++) {
            operationsByMachine[m] = new ArrayList<>();
        }
        for (Operation op : workingSolution.getOperationList()) {
            operationsByMachine[(int) op.getMachineId()].add(op);
        }

        worklist = new PriorityQueue<>(Comparator
                .<Operation>comparingInt(op -> xPosition[(int) op.getOrder().getId()])
                .thenComparingInt(Operation::getPassIndex));

        fullRebuild();
    }

    /** Reconstruit tout à froid, et fixe l'état incrémental de référence. */
    private void fullRebuild() {
        List<Order> sequence = schedule.getOrderSequence();
        for (int i = 0; i < sequence.size(); i++) {
            xPosition[(int) sequence.get(i).getId()] = i;
        }
        for (List<Operation> onMachine : operationsByMachine) {
            sortByTopology(onMachine);
        }
        softTotalCents = 0L;
        hardTotal = 0L;
        Arrays.fill(orderCents, 0L);
        Arrays.fill(opResourceCents, 0L);

        for (List<Operation> onMachine : operationsByMachine) {
            relink(onMachine);
        }
        for (Order order : sequence) {
            for (Operation op : order.getOperations()) {
                recomputeOperation(op, false);
            }
        }
        for (Order order : sequence) {
            recomputeOrderCost(order, false);
        }
    }

    /** Recâble les liens prédécesseur/successeur d'une ressource après un tri. */
    private void relink(List<Operation> onMachine) {
        Operation previous = null;
        for (Operation op : onMachine) {
            prevOnMachine[idx(op)] = previous;
            if (previous != null) {
                nextOnMachine[idx(previous)] = op;
            }
            previous = op;
        }
        if (previous != null) {
            nextOnMachine[idx(previous)] = null;
        }
    }

    private void sortByTopology(List<Operation> onMachine) {
        onMachine.sort(Comparator
                .<Operation>comparingInt(op -> xPosition[(int) op.getOrder().getId()])
                .thenComparingInt(Operation::getPassIndex));
    }

    /**
     * Date une opération et recalcule sa part de coût ressource (metteur + machine immobilisée).
     *
     * @return true si ses dates ont changé
     */
    private boolean recomputeOperation(Operation op, boolean track) {
        Operation machinePredecessor = prevOnMachine[idx(op)];
        long machineFreeAt = machinePredecessor != null ? opEnd[idx(machinePredecessor)] : origin;
        long setupSeconds = machinePredecessor != null
                ? setupMatrix.secondsBetween(machinePredecessor.getSetupKey(), op.getSetupKey())
                : setupMatrix.coldStartSeconds(op.getSetupKey());

        // La mise en train ne consomme que du temps metteur, mais la machine reste immobilisée
        // du moment où elle est libre jusqu'à la fin de la mise en train, trous compris.
        long setupEnd = SetterCalendar.setupEnd(machineFreeAt, setupSeconds);
        long machineIdle = setupEnd - machineFreeAt - setupSeconds;

        List<Operation> chain = op.getOrder().getOperations();
        int pass = op.getPassIndex();
        long chainReadyAt = pass == 0 ? origin : opEnd[idx(chain.get(pass - 1))];

        long start = Math.max(setupEnd, chainReadyAt);
        long end = start + op.getDurationSeconds();

        long resourceCents = setupSeconds * SETTER_CENTS_PER_HOUR / 3600L
                + machineIdle * machineHourlyCents[(int) op.getMachineId()] / 3600L;

        boolean changed = start != opStart[idx(op)] || end != opEnd[idx(op)]
                || resourceCents != opResourceCents[idx(op)];
        if (changed && track) {
            DIRTY_OPERATIONS.incrementAndGet();
        }
        softTotalCents -= resourceCents - opResourceCents[idx(op)];
        opResourceCents[idx(op)] = resourceCents;
        opStart[idx(op)] = start;
        opEnd[idx(op)] = end;
        return changed;
    }

    /** Recalcule le coût d'un ordre à partir de sa date de fin : retard, avance, gel. */
    private void recomputeOrderCost(Order order, boolean track) {
        List<Operation> chain = order.getOperations();
        long completion = opEnd[idx(chain.get(chain.size() - 1))];
        long cents = orderCostCents(order, completion);
        int oi = (int) order.getId();
        if (cents != orderCents[oi]) {
            if (track) {
                ORDER_COMPLETION_CHANGES.incrementAndGet();
            }
            softTotalCents -= cents - orderCents[oi];
            orderCents[oi] = cents;
        }
        if (order.getFreezeLevel() == Order.FreezeLevel.HARD) {
            // Un ordre déjà démarré qu'on déplace n'est pas un surcoût : c'est une faute. Elle
            // pèse sur le score DUR, que le solveur ne peut jamais échanger contre du souple.
            long violation = Math.abs(completion - order.getReferenceCompletionEpochSec());
            hardTotal -= violation - hardViolation[oi];
            hardViolation[oi] = violation;
        }
    }

    private long orderCostCents(Order order, long completion) {
        double deviationHours = (completion - order.getDueEpochSec()) / 3600.0;
        long cents;
        if (deviationHours > 0.0) {
            cents = Math.round(deviationHours * deviationHours * TARDINESS_CENTS_PER_HOUR2
                    * order.getPriorityWeight());
        } else {
            cents = Math.round(deviationHours * deviationHours * EARLINESS_CENTS_PER_HOUR2);
        }
        if (order.getFreezeLevel() == Order.FreezeLevel.SOFT) {
            double driftHours =
                    Math.abs(completion - order.getReferenceCompletionEpochSec()) / 3600.0;
            cents += Math.round(driftHours * SOFT_FREEZE_CENTS_PER_HOUR);
        }
        return cents;
    }

    /**
     * ORACLE — balayage complet à froid, indépendant de tout état incrémental. Ne touche à aucun
     * champ persistant, sert de référence aux tests différentiels.
     */
    public HardSoftLongScore fullSweepScore() {
        int opCount = solution.getOperationList().size();
        long[] end = new long[opCount];
        long[] machineFree = new long[solution.getMachineList().size()];
        int[] lastKeyOnMachine = new int[machineFree.length];
        Arrays.fill(machineFree, origin);
        Arrays.fill(lastKeyOnMachine, -1);
        long soft = 0L;
        long hard = 0L;

        for (Order order : schedule.getOrderSequence()) {
            long chainReadyAt = origin;
            for (Operation op : order.getOperations()) {
                int m = (int) op.getMachineId();
                long setupSeconds = lastKeyOnMachine[m] < 0
                        ? setupMatrix.coldStartSeconds(op.getSetupKey())
                        : setupMatrix.secondsBetween(lastKeyOnMachine[m], op.getSetupKey());
                long setupEnd = SetterCalendar.setupEnd(machineFree[m], setupSeconds);
                long machineIdle = setupEnd - machineFree[m] - setupSeconds;
                long start = Math.max(setupEnd, chainReadyAt);
                long finish = start + op.getDurationSeconds();
                soft -= setupSeconds * SETTER_CENTS_PER_HOUR / 3600L
                        + machineIdle * machineHourlyCents[m] / 3600L;
                machineFree[m] = finish;
                lastKeyOnMachine[m] = op.getSetupKey();
                chainReadyAt = finish;
                end[idx(op)] = finish;
            }
            soft -= orderCostCents(order, chainReadyAt);
            if (order.getFreezeLevel() == Order.FreezeLevel.HARD) {
                hard -= Math.abs(chainReadyAt - order.getReferenceCompletionEpochSec());
            }
        }
        return HardSoftLongScore.of(hard, soft);
    }

    // ************************************************************************
    // Hooks OptaPlanner
    // ************************************************************************

    @Override
    public void beforeListVariableChanged(Object entity, String variableName, int fromIndex, int toIndex) {
    }

    @Override
    public void afterListVariableChanged(Object entity, String variableName, int fromIndex, int toIndex) {
        PROPAGATIONS.incrementAndGet();
        List<Order> sequence = schedule.getOrderSequence();
        int end = Math.min(toIndex, sequence.size());

        // OptaPlanner annonce la PLAGE qui encadre le mouvement, pas les positions qui bougent :
        // un échange entre les positions 200 et 4800 déclare 4600 indices alors que deux ordres
        // seulement changent de place. Balayer la plage pour comparer des entiers coûte ~rien ;
        // traiter la plage entière comme modifiée coûtait un facteur 50 sur le débit (mesuré :
        // 34 évaluations/s avant ce filtre). On ne retient donc que les ordres dont la position
        // a REELLEMENT change.
        movedCount = 0;
        for (int i = fromIndex; i < end; i++) {
            Order order = sequence.get(i);
            int oi = (int) order.getId();
            if (xPosition[oi] != i) {
                xPosition[oi] = i;
                if (movedCount == movedOrders.length) {
                    movedOrders = Arrays.copyOf(movedOrders, movedCount * 2);
                }
                movedOrders[movedCount++] = order;
            }
        }

        // Seules les ressources visitées par ces ordres changent d'ordre de passage. Les retrier
        // est trivial (~18 opérations par machine) et évidemment correct, là où un raccommodage
        // de liens ne l'est pas.
        int touchedCount = 0;
        for (int k = 0; k < movedCount; k++) {
            for (Operation op : movedOrders[k].getOperations()) {
                int m = (int) op.getMachineId();
                if (!machineTouched[m]) {
                    machineTouched[m] = true;
                    touchedMachines[touchedCount++] = m;
                }
            }
        }
        for (int t = 0; t < touchedCount; t++) {
            List<Operation> onMachine = operationsByMachine[touchedMachines[t]];
            sortByTopology(onMachine);
            relink(onMachine);
            for (Operation op : onMachine) {
                enqueue(op);
            }
            machineTouched[touchedMachines[t]] = false;
        }
        for (int k = 0; k < movedCount; k++) {
            for (Operation op : movedOrders[k].getOperations()) {
                enqueue(op);
            }
        }
        propagate();
    }

    private void propagate() {
        int dirtyOrderCount = 0;
        while (!worklist.isEmpty()) {
            Operation op = worklist.poll();
            queued[idx(op)] = false;
            if (!recomputeOperation(op, true)) {
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
            List<Operation> chain = order.getOperations();
            if (op.getPassIndex() + 1 < chain.size()) {
                enqueue(chain.get(op.getPassIndex() + 1));
            }
            enqueue(nextOnMachine[idx(op)]);
        }
        for (int k = 0; k < dirtyOrderCount; k++) {
            recomputeOrderCost(dirtyOrders[k], true);
            orderDirty[(int) dirtyOrders[k].getId()] = false;
        }
    }

    private void enqueue(Operation op) {
        if (op != null && !queued[idx(op)]) {
            queued[idx(op)] = true;
            worklist.add(op);
        }
    }

    @Override
    public HardSoftLongScore calculateScore() {
        CALCULATE_SCORE_CALLS.incrementAndGet();
        return HardSoftLongScore.of(hardTotal, softTotalCents);
    }

    private static int idx(Operation op) {
        return (int) op.getId();
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
