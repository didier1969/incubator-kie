package kki.domain.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

import kki.domain.Machine;
import kki.domain.Operation;
import kki.domain.Order;
import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * REQ-KKI-006 — diagnostic : reproduit le chemin de la construction
 * heuristique réelle (Schedule.orderSequence part vide, les ordres sont
 * insérés un par un à une position arbitraire) via le même couple
 * before/afterListVariableChanged(fromIndex, fromIndex)/(fromIndex,
 * fromIndex+1) qu'utilise org.optaplanner...ListAssignMove — jamais exercé
 * par IncrementalPropagationDifferentialTest (qui part d'une séquence déjà
 * pleine). Compare contre fullSweepScore() après chaque insertion.
 */
class GrowthFromEmptyDifferentialTest {

    @Test
    void incrementalScoreMatchesFullSweepWhileGrowingFromEmpty() {
        Random random = new Random(11L);
        int orderCount = 150;
        int machineCount = 30;

        List<Machine> machines = new ArrayList<>();
        for (long m = 0; m < machineCount; m++) {
            machines.add(new Machine(m, 100));
        }

        long origin = SyntheticDataGenerator.BASE_EPOCH;
        List<Order> orders = new ArrayList<>();
        List<Operation> operations = new ArrayList<>();
        long opId = 0;
        for (long o = 0; o < orderCount; o++) {
            int priority = 1 + random.nextInt(5);
            long due = origin + (long) (random.nextDouble() * 6L * 30 * 24 * 3600);
            Order order = new Order(o, o % 20, priority, due);
            int opCount = 3 + random.nextInt(4);
            List<Operation> orderOps = new ArrayList<>(opCount);
            for (int i = 0; i < opCount; i++) {
                long duration = 1800 + random.nextInt(5400);
                long machineId = random.nextInt(machineCount);
                Operation op = new Operation(opId++, order, i, duration, machineId);
                orderOps.add(op);
                operations.add(op);
            }
            order.setOperations(orderOps);
            orders.add(order);
        }

        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>());
        VerticalSliceSolution solution = new VerticalSliceSolution(orders, operations, machines, List.of(schedule));

        VerticalSliceIncrementalScoreCalculator calculator = new VerticalSliceIncrementalScoreCalculator();
        calculator.resetWorkingSolution(solution);
        assertEquals(HardSoftLongScore.of(0L, 0L), calculator.calculateScore());

        List<Order> shuffled = new ArrayList<>(orders);
        java.util.Collections.shuffle(shuffled, random);
        List<Order> built = schedule.getOrderSequence();
        for (Order order : shuffled) {
            int insertIndex = random.nextInt(built.size() + 1);

            calculator.beforeListVariableChanged(schedule, "orderSequence", insertIndex, insertIndex);
            built.add(insertIndex, order);
            calculator.afterListVariableChanged(schedule, "orderSequence", insertIndex, insertIndex + 1);

            HardSoftLongScore incremental = calculator.calculateScore();
            HardSoftLongScore oracle = calculator.fullSweepScore();
            assertEquals(oracle, incremental,
                    "divergence after inserting order " + order.getId() + " at " + insertIndex);
        }
    }
}
