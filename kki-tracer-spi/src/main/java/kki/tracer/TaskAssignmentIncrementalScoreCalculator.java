package kki.tracer;

import java.util.HashMap;
import java.util.Map;

import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

/**
 * REQ-KKI-001 tracer bullet: proves the IncrementalScoreCalculator SPI end-to-end
 * (PIL-KKI-002) without modeling the real job-shop domain (PIL-KKI-004).
 * Maintains machine load as a running total so calculateScore() is O(1),
 * never a full rescan of taskList.
 */
public class TaskAssignmentIncrementalScoreCalculator
        implements IncrementalScoreCalculator<TaskAssignmentSolution, HardSoftLongScore> {

    private Map<Machine, Long> machineLoad;
    private long hardScore;
    private long softScore;

    @Override
    public void resetWorkingSolution(TaskAssignmentSolution solution) {
        hardScore = 0L;
        softScore = 0L;
        machineLoad = new HashMap<>();
        for (Machine machine : solution.getMachineList()) {
            machineLoad.put(machine, 0L);
        }
        for (Task task : solution.getTaskList()) {
            insert(task);
        }
    }

    @Override
    public void beforeEntityAdded(Object entity) {
        // no-op: retract/insert bracket variable changes, not entity lifecycle, for this toy
    }

    @Override
    public void afterEntityAdded(Object entity) {
        insert((Task) entity);
    }

    @Override
    public void beforeVariableChanged(Object entity, String variableName) {
        retract((Task) entity);
    }

    @Override
    public void afterVariableChanged(Object entity, String variableName) {
        insert((Task) entity);
    }

    @Override
    public void beforeEntityRemoved(Object entity) {
        retract((Task) entity);
    }

    @Override
    public void afterEntityRemoved(Object entity) {
        // no-op, symmetric with beforeEntityAdded
    }

    private void insert(Task task) {
        Machine machine = task.getMachine();
        if (machine == null) {
            return;
        }
        long oldLoad = machineLoad.get(machine);
        hardScore -= overloadPenalty(oldLoad, machine.getCapacity());
        long newLoad = oldLoad + task.getLoad();
        machineLoad.put(machine, newLoad);
        hardScore += overloadPenalty(newLoad, machine.getCapacity());
        softScore -= task.getCostForMachine(machine);
    }

    private void retract(Task task) {
        Machine machine = task.getMachine();
        if (machine == null) {
            return;
        }
        long oldLoad = machineLoad.get(machine);
        hardScore -= overloadPenalty(oldLoad, machine.getCapacity());
        long newLoad = oldLoad - task.getLoad();
        machineLoad.put(machine, newLoad);
        hardScore += overloadPenalty(newLoad, machine.getCapacity());
        softScore += task.getCostForMachine(machine);
    }

    private long overloadPenalty(long load, long capacity) {
        return load > capacity ? -(load - capacity) : 0L;
    }

    @Override
    public HardSoftLongScore calculateScore() {
        return HardSoftLongScore.of(hardScore, softScore);
    }
}
