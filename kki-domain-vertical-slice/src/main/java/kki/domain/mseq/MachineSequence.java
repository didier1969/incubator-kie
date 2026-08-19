package kki.domain.mseq;

import java.util.List;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.variable.PlanningListVariable;

import kki.domain.full.Machine;
import kki.domain.full.Operation;

/**
 * DEC-KKI-004 — la file de passage d'une ressource devient LA variable de décision.
 *
 * <p>
 * C'est le changement de représentation : la séquence machine n'est plus dérivée d'une priorité
 * globale X, elle est choisie ressource par ressource. Conséquences en cascade :
 * <ul>
 * <li>l'espace de solutions devient celui du graphe disjonctif complet, au lieu des seuls
 * ordonnancements exprimables par une permutation unique ;</li>
 * <li>l'appartenance d'une opération à cette liste EST son affectation machine — le mouvement M2
 * devient un déplacement inter-listes, sans variable supplémentaire ;</li>
 * <li><b>l'acyclicité n'est plus gratuite</b> : deux ressources peuvent se contredire et produire
 * un plan impossible, ce qui doit peser sur le score dur et non sur le souple.</li>
 * </ul>
 *
 * <p>
 * Une seule classe d'entité, toutes porteuses de la même variable-liste : la limitation
 * d'OptaPlanner qui bloquait M2 en représentation X (voir DEC-KKI-004) n'est jamais atteinte ici.
 */
@PlanningEntity
public class MachineSequence {

    private Machine machine;

    @PlanningListVariable(valueRangeProviderRefs = "operationRange")
    private List<Operation> operations;

    public MachineSequence() {
    }

    public MachineSequence(Machine machine, List<Operation> operations) {
        this.machine = machine;
        this.operations = operations;
    }

    public Machine getMachine() {
        return machine;
    }

    public void setMachine(Machine machine) {
        this.machine = machine;
    }

    public List<Operation> getOperations() {
        return operations;
    }

    public void setOperations(List<Operation> operations) {
        this.operations = operations;
    }

    @Override
    public String toString() {
        return "Seq(" + machine + ")";
    }
}
