package kki.domain.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

import kki.domain.Machine;
import kki.domain.Operation;
import kki.domain.Order;
import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * REQ-KKI-006 (réécrit) : score vérifié indépendamment à la main, sur un
 * cas qui croise précédence machine ET précédence ordre — ce qu'exerçait
 * l'ancien TimeCascadeVariableListenerTest, maintenant couvert ici puisque
 * le balayage fait les deux dans la même passe (plus de shadow variable
 * séparée).
 *
 * Séquence X : [Order A, Order B]. A = [A1(M1,1h), A2(M2,1h)]. B = [B1(M1,30min)].
 *
 * Balayage :
 *  A1 sur M1 : pas de prédécesseur -> start=0, end=3600.
 *  A2 sur M2 : pred-machine=0 (M2 vide), pred-ordre=3600 (fin A1) -> start=3600, end=7200.
 *  B1 sur M1 : pred-machine=3600 (fin A1, PAS 7200 : M2 n'affecte pas M1), pred-ordre=0 -> start=3600, end=5400.
 *
 * Order A finit à 7200, due=14400 -> 2h en avance -> cost=(5/10)*2^2=2.0 -> round 2.
 * Order B finit à 5400, due=0 -> 1.5h en retard, priorité 3 -> cost=3*5*1.5^2=33.75 -> round 34.
 * Total attendu : -(2+34) = -36.
 */
class VerticalSliceIncrementalScoreCalculatorTest {

    @Test
    void sweepsBothMachineAndOrderPrecedenceInOnePass() {
        Machine m1 = new Machine(1, 100);
        Machine m2 = new Machine(2, 100);

        long origin = SyntheticDataGenerator.BASE_EPOCH;
        Order orderA = new Order(0, 100, 2, origin + 14_400L);
        Operation a1 = new Operation(0, orderA, 0, 3_600L, m1.getId());
        Operation a2 = new Operation(1, orderA, 1, 3_600L, m2.getId());
        orderA.setOperations(List.of(a1, a2));

        Order orderB = new Order(1, 200, 3, origin);
        Operation b1 = new Operation(2, orderB, 0, 1_800L, m1.getId());
        orderB.setOperations(List.of(b1));

        Schedule schedule = new Schedule();
        List<Order> sequence = new ArrayList<>();
        sequence.add(orderA);
        sequence.add(orderB);
        schedule.setOrderSequence(sequence);

        VerticalSliceSolution solution = new VerticalSliceSolution(
                List.of(orderA, orderB), List.of(a1, a2, b1), List.of(m1, m2), List.of(schedule));

        VerticalSliceIncrementalScoreCalculator calculator = new VerticalSliceIncrementalScoreCalculator();
        calculator.resetWorkingSolution(solution);

        assertEquals(HardSoftLongScore.of(0L, -36L), calculator.calculateScore());
    }

    @Test
    void emptySequenceScoresZero() {
        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>());
        VerticalSliceSolution solution = new VerticalSliceSolution(List.of(), List.of(), List.of(), List.of(schedule));

        VerticalSliceIncrementalScoreCalculator calculator = new VerticalSliceIncrementalScoreCalculator();
        calculator.resetWorkingSolution(solution);

        assertEquals(HardSoftLongScore.of(0L, 0L), calculator.calculateScore());
    }
}
