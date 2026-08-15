package kki.tracer;

import java.util.List;

import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

@PlanningSolution
public class TaskAssignmentSolution {

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "machineRange")
    private List<Machine> machineList;

    @PlanningEntityCollectionProperty
    private List<Task> taskList;

    @PlanningScore
    private HardSoftLongScore score;

    public TaskAssignmentSolution() {
        // required by OptaPlanner
    }

    public TaskAssignmentSolution(List<Machine> machineList, List<Task> taskList) {
        this.machineList = machineList;
        this.taskList = taskList;
    }

    public List<Machine> getMachineList() {
        return machineList;
    }

    public List<Task> getTaskList() {
        return taskList;
    }

    public HardSoftLongScore getScore() {
        return score;
    }

    public void setScore(HardSoftLongScore score) {
        this.score = score;
    }
}
