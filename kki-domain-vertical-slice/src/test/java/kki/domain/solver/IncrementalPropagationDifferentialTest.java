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
 * REQ-KKI-006 — la propagation incrémentale (worklist/delta) doit produire
 * EXACTEMENT le score de calculator.fullSweepScore() (balayage complet à
 * froid, oracle) après CHAQUE mouvement d'une séquence de mouvements
 * aléatoires. Les tests à valeurs calculées à la main
 * (VerticalSliceIncrementalScoreCalculatorTest) ne peuvent pas détecter un
 * nœud sale oublié dans la propagation ; celui-ci le peut, en divergeant
 * dès le premier mouvement fautif.
 */
class IncrementalPropagationDifferentialTest {

    @Test
    void incrementalScoreMatchesFullSweepAfterRandomMoves() {
        Random random = new Random(7L);
        int orderCount = 40;
        int machineCount = 12;

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
        List<Order> sequence = new ArrayList<>(orders);
        schedule.setOrderSequence(sequence);

        VerticalSliceSolution solution = new VerticalSliceSolution(orders, operations, machines, List.of(schedule));

        VerticalSliceIncrementalScoreCalculator calculator = new VerticalSliceIncrementalScoreCalculator();
        calculator.resetWorkingSolution(solution);
        assertEquals(calculator.fullSweepScore(), calculator.calculateScore());

        for (int move = 0; move < 200; move++) {
            // REQ-KKI-008 : 3 formes, dont swap (ListSwapMove même entité — jamais exercée
            // avant ce REQ) qui déclenche le chemin rapide de findDisplacedOrders le plus
            // agressivement (span large, seulement 2 ordres réellement déplacés).
            int moveKind = random.nextInt(3);
            String moveLabel;
            int lo;
            int hi;
            if (moveKind == 0) {
                moveLabel = "reverse";
                int a = random.nextInt(sequence.size());
                int b = random.nextInt(sequence.size());
                lo = Math.min(a, b);
                hi = Math.max(a, b) + 1;
                if (hi - lo < 2) {
                    continue;
                }
                calculator.beforeListVariableChanged(schedule, "orderSequence", lo, hi);
                java.util.Collections.reverse(sequence.subList(lo, hi));
            } else if (moveKind == 1) {
                moveLabel = "relocate";
                int from = random.nextInt(sequence.size());
                int to = random.nextInt(sequence.size());
                if (from == to) {
                    continue;
                }
                lo = Math.min(from, to);
                hi = Math.max(from, to) + 1;
                calculator.beforeListVariableChanged(schedule, "orderSequence", lo, hi);
                Order moved = sequence.remove(from);
                sequence.add(to > from ? to - 1 : to, moved);
            } else {
                moveLabel = "swap";
                int left = random.nextInt(sequence.size());
                int right = random.nextInt(sequence.size());
                if (left == right) {
                    continue;
                }
                lo = Math.min(left, right);
                hi = Math.max(left, right) + 1;
                calculator.beforeListVariableChanged(schedule, "orderSequence", lo, hi);
                Order leftOrder = sequence.get(left);
                Order rightOrder = sequence.get(right);
                sequence.set(left, rightOrder);
                sequence.set(right, leftOrder);
            }
            calculator.afterListVariableChanged(schedule, "orderSequence", lo, hi);

            HardSoftLongScore incremental = calculator.calculateScore();
            HardSoftLongScore oracle = calculator.fullSweepScore();
            assertEquals(oracle, incremental,
                    "divergence after move " + move + " (" + moveLabel + " " + lo + ".." + hi + ")");
        }
    }
}
