package kki.tracer;

import java.util.Map;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

@PlanningEntity
public class Task {

    @PlanningId
    private Long id;
    private long load;
    private Map<Machine, Long> costByMachine;

    @PlanningVariable(valueRangeProviderRefs = "machineRange")
    private Machine machine;

    public Task() {
        // required by OptaPlanner
    }

    public Task(long id, long load, Map<Machine, Long> costByMachine) {
        this.id = id;
        this.load = load;
        this.costByMachine = costByMachine;
    }

    public Long getId() {
        return id;
    }

    public long getLoad() {
        return load;
    }

    public long getCostForMachine(Machine m) {
        return costByMachine.get(m);
    }

    public Machine getMachine() {
        return machine;
    }

    public void setMachine(Machine machine) {
        this.machine = machine;
    }

    @Override
    public String toString() {
        return "Task-" + id;
    }
}
