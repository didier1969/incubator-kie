package kki.domain;

import java.util.List;

import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

@PlanningSolution
public class VerticalSliceSolution {

    // Pattern vérifié sur VehicleRoutingSolution.customerList : les VALEURS
    // d'une @PlanningListVariable se déclarent en @ProblemFactCollectionProperty
    // + @ValueRangeProvider, même si Order reste @PlanningEntity (shadow vars).
    @ProblemFactCollectionProperty
    @ValueRangeProvider
    private List<Order> orderList;

    @ProblemFactCollectionProperty
    private List<Operation> operationList;

    @ProblemFactCollectionProperty
    private List<Machine> machineList;

    // Singleton : une seule instance, porte l'axe X partagé (Schedule.orderSequence).
    @PlanningEntityCollectionProperty
    private List<Schedule> scheduleList;

    @PlanningScore
    private HardSoftLongScore score;

    public VerticalSliceSolution() {
    }

    public VerticalSliceSolution(List<Order> orderList, List<Operation> operationList,
            List<Machine> machineList, List<Schedule> scheduleList) {
        this.orderList = orderList;
        this.operationList = operationList;
        this.machineList = machineList;
        this.scheduleList = scheduleList;
    }

    public List<Order> getOrderList() {
        return orderList;
    }

    public List<Operation> getOperationList() {
        return operationList;
    }

    public List<Machine> getMachineList() {
        return machineList;
    }

    public List<Schedule> getScheduleList() {
        return scheduleList;
    }

    public HardSoftLongScore getScore() {
        return score;
    }

    public void setScore(HardSoftLongScore score) {
        this.score = score;
    }
}
