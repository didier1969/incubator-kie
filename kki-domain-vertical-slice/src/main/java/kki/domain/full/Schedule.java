package kki.domain.full;

import java.util.List;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningListVariable;

/**
 * L'unique entité de planification de ce modèle : la priorité globale X sur les ordres.
 *
 * <p>
 * CPT-KKI-012 — X n'impose pas une file figée sur chaque ressource : il DÉPARTAGE. Deux ordres
 * qui ne se disputent jamais une machine au même instant ne sont liés par aucune contrainte, et
 * les échanger est un no-op exact. Une opération non prête n'est candidate à rien et ne bloque
 * personne.
 *
 * <p>
 * La chaîne d'un ordre voyage en bloc avec lui : déplacer l'ordre dans cette liste déplace
 * toutes ses opérations, ce qui est structurellement garanti et non une règle à faire respecter.
 */
@PlanningEntity
public class Schedule {

    /**
     * Identité stable, exigée par {@code ScoreDirector.lookUpWorkingObject} — donc par
     * {@code Move.rebase}, donc par la résolution multi-thread. La solution ne porte qu'UN
     * Schedule, mais chaque fil de résolution en a sa propre COPIE : sans identifiant, un
     * mouvement ne sait pas désigner le Schedule de la copie destinataire.
     */
    @PlanningId
    private long id;

    @PlanningListVariable(valueRangeProviderRefs = "orderRange")
    private List<Order> orderSequence;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public List<Order> getOrderSequence() {
        return orderSequence;
    }

    public void setOrderSequence(List<Order> orderSequence) {
        this.orderSequence = orderSequence;
    }
}
