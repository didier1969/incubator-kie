package kki.domain.solver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import kki.domain.Machine;
import kki.domain.Operation;
import kki.domain.Order;
import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * REQ-KKI-006 (réécrit) — 5000 ordres (corrigé par l'opérateur). La machine
 * de chaque opération est un fait FIXE assigné ici (choix machine = CPT-KKI-008,
 * différé) ; seule la position X des ordres (Schedule.orderSequence) est
 * une décision de planification — la liste démarre vide, la construction
 * heuristique la peuple.
 */
public final class SyntheticDataGenerator {

    private static final long HORIZON_SECONDS = 6L * 30 * 24 * 3600; // ~6 mois

    /** Origine absolue partagée avec le balayage du score (même base de temps). */
    public static final long BASE_EPOCH = 1_700_000_000L;

    private SyntheticDataGenerator() {
    }

    public static VerticalSliceSolution generate(int orderCount, int machineCount, long seed) {
        Random random = new Random(seed);

        List<Machine> machineList = new ArrayList<>(machineCount);
        for (long m = 0; m < machineCount; m++) {
            machineList.add(new Machine(m, 1000));
        }

        List<Order> orderList = new ArrayList<>(orderCount);
        List<Operation> operationList = new ArrayList<>();
        long operationId = 0;
        for (long o = 0; o < orderCount; o++) {
            long articleId = random.nextInt(200);
            int priorityWeight = 1 + random.nextInt(5);
            long requiredDueEpochSec = BASE_EPOCH + (long) (random.nextDouble() * HORIZON_SECONDS);
            Order order = new Order(o, articleId, priorityWeight, requiredDueEpochSec);

            int opCount = 3 + random.nextInt(4); // 3..6
            List<Operation> orderOps = new ArrayList<>(opCount);
            for (int i = 0; i < opCount; i++) {
                long durationSeconds = 1800 + random.nextInt(5400); // 30min..2h
                long machineId = random.nextInt(machineCount);
                Operation op = new Operation(operationId++, order, i, durationSeconds, machineId);
                orderOps.add(op);
                operationList.add(op);
            }
            order.setOperations(orderOps);
            orderList.add(order);
        }

        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>()); // vide : la construction heuristique la peuple

        return new VerticalSliceSolution(orderList, operationList, machineList, List.of(schedule));
    }
}
