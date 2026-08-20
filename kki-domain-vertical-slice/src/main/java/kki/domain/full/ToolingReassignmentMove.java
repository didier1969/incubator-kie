package kki.domain.full;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.heuristic.move.AbstractMove;
import org.optaplanner.core.impl.heuristic.move.Move;

/**
 * Mouvement (7) de {@code CPT-KKI-010} — échange d'exemplaire sur outillage partagé.
 *
 * <p>
 * L'outillage est la ressource la PLUS rare : 120 exemplaires pour 7 710 emprunts, soit ~64
 * chacun. Deux exemplaires du même type sont interchangeables ({@code CPT-KKI-009}) : déplacer
 * une mise en train d'un exemplaire saturé vers un exemplaire libre du même type ne change rien
 * au plan sinon l'attente supprimée. C'est un gain sans contrepartie quand il existe — ce qui est
 * rare parmi les dix mouvements, la plupart arbitrant entre deux coûts.
 *
 * <p>
 * L'exemplaire redevient libre à la FIN DE LA MISE EN TRAIN et non à la fin de l'usinage : la
 * file d'un outillage est donc plus courte que celle d'une machine à charge égale.
 */
public final class ToolingReassignmentMove extends AbstractMove<JobShopSolution> {

    private final Schedule schedule;
    private final Operation operation;
    private final Tooling target;

    public ToolingReassignmentMove(Schedule schedule, Operation operation, Tooling target) {
        this.schedule = schedule;
        this.operation = operation;
        this.target = target;
    }

    /**
     * Le TYPE est vérifié ici, pas supposé : deux exemplaires du même type sont interchangeables,
     * deux types différents ne le sont pas. Une opération qui n'emprunte aucun outillage n'a rien
     * à échanger.
     */
    @Override
    public boolean isMoveDoable(ScoreDirector<JobShopSolution> scoreDirector) {
        return operation.getOrder().getFreezeLevel() != Order.FreezeLevel.HARD
                && operation.getRequiredToolingType() != Operation.NO_TOOLING
                && target != operation.getTooling()
                && target.getType() == operation.getRequiredToolingType();
    }

    @Override
    protected AbstractMove<JobShopSolution> createUndoMove(
            ScoreDirector<JobShopSolution> scoreDirector) {
        return new ToolingReassignmentMove(schedule, operation, operation.getTooling());
    }

    @Override
    protected void doMoveOnGenuineVariables(ScoreDirector<JobShopSolution> scoreDirector) {
        WorkcenterReassignmentMove.calculatorOf(scoreDirector).reassignTooling(operation, target);
    }

    /** Traduit ce mouvement pour la copie de solution d'un autre fil — voir REQ-KKI-036. */
    @Override
    public Move<JobShopSolution> rebase(ScoreDirector<JobShopSolution> destinationScoreDirector) {
        return new ToolingReassignmentMove(destinationScoreDirector.lookUpWorkingObject(schedule),
                destinationScoreDirector.lookUpWorkingObject(operation),
                destinationScoreDirector.lookUpWorkingObject(target));
    }

    @Override
    public Collection<?> getPlanningEntities() {
        return Collections.singletonList(schedule);
    }

    @Override
    public Collection<?> getPlanningValues() {
        return List.of(target);
    }

    @Override
    public String toString() {
        return "ReassignTooling(" + operation + " -> " + target + ")";
    }
}
