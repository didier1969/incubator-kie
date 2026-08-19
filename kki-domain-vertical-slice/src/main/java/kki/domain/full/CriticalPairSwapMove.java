package kki.domain.full;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.heuristic.move.AbstractMove;

/**
 * M3 — échange les positions X de deux ordres.
 *
 * <p>
 * Émis uniquement pour des paires <b>adjacentes et tendues sur une ressource partagée</b>
 * (voir {@link CriticalPairMoveIteratorFactory}). Quand la paire ne partage que cette machine —
 * le cas majoritaire à 3,5 opérations par ordre sur 1000 machines — l'échange inverse exactement
 * un arc disjonctif : c'est l'inversion d'arc critique du concept, obtenue sans changer de
 * représentation.
 *
 * <p>
 * L'échange est sa propre inverse, d'où un mouvement d'annulation identique.
 */
public final class CriticalPairSwapMove extends AbstractMove<JobShopSolution> {

    private final Schedule schedule;
    private final int leftIndex;
    private final int rightIndex;

    public CriticalPairSwapMove(Schedule schedule, int leftIndex, int rightIndex) {
        this.schedule = schedule;
        this.leftIndex = Math.min(leftIndex, rightIndex);
        this.rightIndex = Math.max(leftIndex, rightIndex);
    }

    @Override
    public boolean isMoveDoable(ScoreDirector<JobShopSolution> scoreDirector) {
        return leftIndex != rightIndex;
    }

    @Override
    protected AbstractMove<JobShopSolution> createUndoMove(ScoreDirector<JobShopSolution> scoreDirector) {
        return new CriticalPairSwapMove(schedule, leftIndex, rightIndex);
    }

    @Override
    protected void doMoveOnGenuineVariables(ScoreDirector<JobShopSolution> scoreDirector) {
        List<Order> sequence = schedule.getOrderSequence();
        scoreDirector.beforeListVariableChanged(schedule, "orderSequence", leftIndex, rightIndex + 1);
        Collections.swap(sequence, leftIndex, rightIndex);
        scoreDirector.afterListVariableChanged(schedule, "orderSequence", leftIndex, rightIndex + 1);
    }

    @Override
    public Collection<?> getPlanningEntities() {
        return Collections.singletonList(schedule);
    }

    @Override
    public Collection<?> getPlanningValues() {
        List<Order> sequence = schedule.getOrderSequence();
        return List.of(sequence.get(leftIndex), sequence.get(rightIndex));
    }

    @Override
    public String toString() {
        return "CriticalPairSwap(" + leftIndex + " <-> " + rightIndex + ")";
    }
}
