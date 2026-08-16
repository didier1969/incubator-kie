package kki.domain;

import java.util.List;

/**
 * REQ-KKI-006 — fait fixe : la VALEUR de la seule variable de planification
 * de cette tranche (Schedule.orderSequence, @PlanningListVariable). Toutes
 * ses opérations se déplacent avec elle en bloc (CPT-KKI-003) —
 * structurellement garanti, un déplacement d'Order dans la liste déplace
 * tout son bloc.
 * Pas de shadow variable ici : previousOrderInSequence/nextOrderInSequence
 * ont été essayées puis retirées — elles ne sont fiables qu'après
 * triggerVariableListeners(), qui n'a pas encore couru au moment où
 * VerticalSliceIncrementalScoreCalculator reçoit before/afterListVariable*
 * (vérifié en lisant IncrementalScoreDirector/AbstractScoreDirector :
 * le score-calculator est notifié AVANT que la file de notification des
 * shadow variables ne soit déclenchée). Le calculateur retrouve ses
 * voisins machine par indexation directe sur Schedule.orderSequence (déjà
 * mutée au moment des hooks after*), pas par chaînage de shadow variable.
 * Order n'a donc plus besoin d'être @PlanningEntity.
 */
public final class Order {

    private long id;
    private long articleId;
    private int priorityWeight;
    private long requiredDueEpochSec;
    private List<Operation> operations;

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

    @Override
    public String toString() {
        return "Order-" + id;
    }
}
