package kki.domain;

import java.util.List;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.variable.NextElementShadowVariable;
import org.optaplanner.core.api.domain.variable.PreviousElementShadowVariable;

/**
 * REQ-KKI-006 (réécrit) — la VALEUR de la seule variable de planification de
 * cette tranche : sa position sur l'axe X (Schedule.orderSequence,
 * @PlanningListVariable). Toutes ses opérations se déplacent avec elle en
 * bloc (CPT-KKI-003) — structurellement garanti, plus besoin d'un mouvement
 * Pillar dédié : déplacer un Order dans la liste déplace tout son bloc.
 */
@PlanningEntity
public class Order {

    private long id;
    private long articleId;
    private int priorityWeight;
    private long requiredDueEpochSec;
    private List<Operation> operations;

    // Shadow variables (dérivées de Schedule.orderSequence)
    private Order previousOrderInSequence;
    private Order nextOrderInSequence;

    public Order() {
    }

    public Order(long id, long articleId, int priorityWeight, long requiredDueEpochSec) {
        this.id = id;
        this.articleId = articleId;
        this.priorityWeight = priorityWeight;
        this.requiredDueEpochSec = requiredDueEpochSec;
    }

    public void setOperations(List<Operation> operations) {
        this.operations = operations;
    }

    public long getId() {
        return id;
    }

    public long getArticleId() {
        return articleId;
    }

    public int getPriorityWeight() {
        return priorityWeight;
    }

    public long getRequiredDueEpochSec() {
        return requiredDueEpochSec;
    }

    public List<Operation> getOperations() {
        return operations;
    }

    @PreviousElementShadowVariable(sourceVariableName = "orderSequence")
    public Order getPreviousOrderInSequence() {
        return previousOrderInSequence;
    }

    public void setPreviousOrderInSequence(Order previousOrderInSequence) {
        this.previousOrderInSequence = previousOrderInSequence;
    }

    @NextElementShadowVariable(sourceVariableName = "orderSequence")
    public Order getNextOrderInSequence() {
        return nextOrderInSequence;
    }

    public void setNextOrderInSequence(Order nextOrderInSequence) {
        this.nextOrderInSequence = nextOrderInSequence;
    }

    @Override
    public String toString() {
        return "Order-" + id;
    }
}
