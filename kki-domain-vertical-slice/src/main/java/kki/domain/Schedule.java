package kki.domain;

import java.util.ArrayList;
import java.util.List;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.variable.PlanningListVariable;

/**
 * REQ-KKI-006 (réécrit) — porte l'axe X : UNE seule instance dans la
 * solution, une seule liste ordonnée partagée par toutes les machines
 * (concept opérateur : "une grille 3D, on déplace les ordres complets en
 * X"). Remplace le @PlanningListVariable par-machine (Machine.operations) —
 * c'est ce dernier qui permettait à deux machines de choisir des séquences
 * mutuellement incohérentes, source du cycle de précédence trouvé en test.
 */
@PlanningEntity
public class Schedule {

    @PlanningListVariable
    private List<Order> orderSequence = new ArrayList<>();

    public List<Order> getOrderSequence() {
        return orderSequence;
    }

    public void setOrderSequence(List<Order> orderSequence) {
        this.orderSequence = orderSequence;
    }
}
