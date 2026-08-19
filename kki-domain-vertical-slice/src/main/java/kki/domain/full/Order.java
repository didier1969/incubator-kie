package kki.domain.full;

import java.util.List;

/**
 * Un ordre : une chaîne séquentielle de 1 à 6 opérations pour un article, avec sa date due,
 * son poids de priorité et son palier de gel.
 *
 * <p>
 * PIL-KKI-004 — trois paliers de gel, et ils ne se traitent pas de la même façon :
 * <ul>
 * <li>{@link FreezeLevel#HARD} — ordre démarré ou modifié à la main. Le déplacer est une
 * violation, pas un surcoût : il pèse sur le score DUR.</li>
 * <li>{@link FreezeLevel#SOFT} — dans l'horizon de trois semaines. Le déplacer est permis mais
 * pénalisé par l'écart au dernier plan publié : la stabilité du plan a une valeur, elle n'est
 * pas absolue.</li>
 * <li>{@link FreezeLevel#FREE} — au-delà, aucune contrainte de stabilité.</li>
 * </ul>
 */
public final class Order {

    public enum FreezeLevel {
        HARD, SOFT, FREE
    }

    private final long id;
    private final int articleId;
    private final int priorityWeight;
    private final long dueEpochSec;
    private final FreezeLevel freezeLevel;
    private final long referenceCompletionEpochSec;
    private List<Operation> operations;

    public Order(long id, int articleId, int priorityWeight, long dueEpochSec,
            FreezeLevel freezeLevel, long referenceCompletionEpochSec) {
        this.id = id;
        this.articleId = articleId;
        this.priorityWeight = priorityWeight;
        this.dueEpochSec = dueEpochSec;
        this.freezeLevel = freezeLevel;
        this.referenceCompletionEpochSec = referenceCompletionEpochSec;
    }

    public long getId() {
        return id;
    }

    public int getArticleId() {
        return articleId;
    }

    public int getPriorityWeight() {
        return priorityWeight;
    }

    public long getDueEpochSec() {
        return dueEpochSec;
    }

    public FreezeLevel getFreezeLevel() {
        return freezeLevel;
    }

    /** Date de fin dans le dernier plan publié — référence de la pénalité de stabilité. */
    public long getReferenceCompletionEpochSec() {
        return referenceCompletionEpochSec;
    }

    public List<Operation> getOperations() {
        return operations;
    }

    public void setOperations(List<Operation> operations) {
        this.operations = operations;
    }

    @Override
    public String toString() {
        return "O" + id;
    }
}
