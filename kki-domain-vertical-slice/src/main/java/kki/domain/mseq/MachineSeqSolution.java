package kki.domain.mseq;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

import kki.domain.full.JobShopSolution;
import kki.domain.full.Machine;
import kki.domain.full.Operation;
import kki.domain.full.Order;
import kki.domain.full.SetupMatrix;

/**
 * Le même problème que {@link JobShopSolution}, dans la représentation par séquence machine.
 *
 * <p>
 * La plage de valeurs est GLOBALE — toutes les opérations, pour toutes les ressources. Une plage
 * par entité serait plus fidèle à la compatibilité ascendante, mais le décompte des valeurs non
 * assignées d'OptaPlanner interroge la plage sans entité, ce qui la rendrait inutilisable. La
 * compatibilité est donc portée par les mouvements, qui ne proposent jamais une ressource
 * incompatible, et par le score DUR, qui refuse ce qui passerait quand même.
 */
@PlanningSolution
public class MachineSeqSolution {

    @ProblemFactCollectionProperty
    private List<Order> orderList;

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "operationRange")
    private List<Operation> operationList;

    @ProblemFactCollectionProperty
    private List<Machine> machineList;

    @PlanningEntityCollectionProperty
    private List<MachineSequence> sequenceList;

    @PlanningScore
    private HardSoftLongScore score;

    private SetupMatrix setupMatrix;
    private long originEpochSec;

    public MachineSeqSolution() {
    }

    /**
     * Convertit une instance de la représentation X en son équivalent exact : chaque opération
     * reste sur sa machine, et l'ordre de passage sur chaque ressource est celui que la priorité X
     * induisait. Les deux représentations partent donc du MÊME plan — sans quoi la comparaison des
     * deux mesures ne voudrait rien dire.
     */
    public static MachineSeqSolution from(JobShopSolution source) {
        MachineSeqSolution target = new MachineSeqSolution();
        target.orderList = source.getOrderList();
        target.operationList = source.getOperationList();
        target.machineList = source.getMachineList();
        target.setupMatrix = source.getSetupMatrix();
        target.originEpochSec = source.getOriginEpochSec();

        List<Order> sequence = source.getScheduleList().get(0).getOrderSequence();
        int[] xPosition = new int[source.getOrderList().size()];
        for (int i = 0; i < sequence.size(); i++) {
            xPosition[(int) sequence.get(i).getId()] = i;
        }

        List<List<Operation>> byMachine = new ArrayList<>(target.machineList.size());
        for (int m = 0; m < target.machineList.size(); m++) {
            byMachine.add(new ArrayList<>());
        }
        for (Operation op : target.operationList) {
            byMachine.get((int) op.getMachineId()).add(op);
        }
        Comparator<Operation> byXThenPass =
                Comparator.<Operation>comparingInt(op -> xPosition[(int) op.getOrder().getId()])
                        .thenComparingInt(Operation::getPassIndex);
        target.sequenceList = new ArrayList<>(target.machineList.size());
        for (Machine machine : target.machineList) {
            List<Operation> onMachine = byMachine.get((int) machine.getId());
            onMachine.sort(byXThenPass);
            target.sequenceList.add(new MachineSequence(machine, onMachine));
        }
        return target;
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

    public List<MachineSequence> getSequenceList() {
        return sequenceList;
    }

    public HardSoftLongScore getScore() {
        return score;
    }

    public void setScore(HardSoftLongScore score) {
        this.score = score;
    }

    public SetupMatrix getSetupMatrix() {
        return setupMatrix;
    }

    public long getOriginEpochSec() {
        return originEpochSec;
    }
}
