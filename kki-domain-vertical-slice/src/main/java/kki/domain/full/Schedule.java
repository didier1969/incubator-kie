package kki.domain.full;

import java.util.List;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
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

    @PlanningListVariable(valueRangeProviderRefs = "orderRange")
    private List<Order> orderSequence;

    public List<Order> getOrderSequence() {
        return orderSequence;
    }

    public void setOrderSequence(List<Order> orderSequence) {
        this.orderSequence = orderSequence;
    }
}
