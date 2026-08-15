package kki.tracer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

/**
 * Verifies the incremental delta logic against independently hand-computed
 * expected scores at each step (REQ-KKI-001 acceptance criterion: "score
 * verifie manuellement, pas seulement 'ne crashe pas'"), not just that the
 * calculator runs without throwing.
 */
class TaskAssignmentIncrementalScoreCalculatorTest {

    @Test
    void incrementalScoreMatchesHandComputedExpectationAtEachStep() {
        Machine m1 = new Machine(1, 10);
        Machine m2 = new Machine(2, 10);

        Task t1 = new Task(1, 6, costs(m1, 3L, m2, 5L));
        Task t2 = new Task(2, 5, costs(m1, 2L, m2, 4L));
        Task t3 = new Task(3, 4, costs(m1, 1L, m2, 6L));

        TaskAssignmentSolution solution = new TaskAssignmentSolution(List.of(m1, m2), List.of(t1, t2, t3));
        TaskAssignmentIncrementalScoreCalculator calculator = new TaskAssignmentIncrementalScoreCalculator();

        // step 0: reset with everything unassigned
        calculator.resetWorkingSolution(solution);
        assertEquals(HardSoftLongScore.of(0, 0), calculator.calculateScore());

        // step 1: t1 -> m1 (load 6/10, no overload). soft = -3
        assign(calculator, t1, m1);
        assertEquals(HardSoftLongScore.of(0, -3), calculator.calculateScore());

        // step 2: t2 -> m1 (load 11/10, overload 1 -> hard -1). soft = -3-2 = -5
        assign(calculator, t2, m1);
        assertEquals(HardSoftLongScore.of(-1, -5), calculator.calculateScore());

        // step 3: t3 -> m2 (load 4/10, no overload). soft = -5-6 = -11
        assign(calculator, t3, m2);
        assertEquals(HardSoftLongScore.of(-1, -11), calculator.calculateScore());

        // step 4: move t2 m1 -> m2. m1 back to 6/10 (overload clears), m2 to 9/10.
        // soft = -11 + cost(t2,m1) - cost(t2,m2) = -11 + 2 - 4 = -13
        assign(calculator, t2, m2);
        assertEquals(HardSoftLongScore.of(0, -13), calculator.calculateScore());
    }

    private static void assign(TaskAssignmentIncrementalScoreCalculator calculator, Task task, Machine machine) {
        calculator.beforeVariableChanged(task, "machine");
        task.setMachine(machine);
        calculator.afterVariableChanged(task, "machine");
    }

    private static Map<Machine, Long> costs(Machine m1, long c1, Machine m2, long c2) {
        Map<Machine, Long> map = new HashMap<>();
        map.put(m1, c1);
        map.put(m2, c2);
        return map;
    }
}
