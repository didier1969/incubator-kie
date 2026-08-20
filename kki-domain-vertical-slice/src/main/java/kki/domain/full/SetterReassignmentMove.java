package kki.domain.full;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.heuristic.move.AbstractMove;
import org.optaplanner.core.impl.heuristic.move.Move;

/**
 * Mouvement (6) de {@code CPT-KKI-010} — réaffectation du metteur en train.
 *
 * <p>
 * Le metteur est la ressource RARE du modèle : 242 metteurs portent 18 486 mises en train, soit
 * ~76 chacun, contre ~18 opérations par machine sur 1000 postes. Un metteur saturé immobilise des
 * machines qui, elles, sont libres — et {@code CPT-KKI-007} fait payer ces heures machine perdues
 * au coût horaire du poste. C'est donc un levier sur le terme dominant, pas un raffinement.
 *
 * <p>
 * La primitive {@code reassignSetter} existait depuis {@code REQ-KKI-029} mais n'avait jamais
 * d'enveloppe {@code Move} : elle n'était appelée que par une commande de phase exécutée une
 * seule fois. Un mouvement écrit mais jamais tiré n'est pas dans la recherche — c'est la leçon
 * de {@code REQ-KKI-031}, où le second mouvement du paradigme comptait 300 tirages contre
 * 825 820 échanges.
 */
public final class SetterReassignmentMove extends AbstractMove<JobShopSolution> {

    private final Schedule schedule;
    private final Operation operation;
    private final Setter target;

    public SetterReassignmentMove(Schedule schedule, Operation operation, Setter target) {
        this.schedule = schedule;
        this.operation = operation;
        this.target = target;
    }

    /**
     * La COMPÉTENCE est un mur, pas un surcoût.
     *
     * <p>
     * Contrairement à la technologie machine, qui se substitue vers le haut en payant un coût
     * horaire supérieur, un metteur qui ne sait pas régler cette machine ne le fera jamais. Le
     * refuser ici plutôt que le facturer change la nature de la contrainte : le solveur ne peut
     * pas l'acheter.
     */
    @Override
    public boolean isMoveDoable(ScoreDirector<JobShopSolution> scoreDirector) {
        return operation.getOrder().getFreezeLevel() != Order.FreezeLevel.HARD
                && target != operation.getSetter()
                && target.canSetUp(operation.getMachine());
    }

    /**
     * Construit AVANT que le mouvement ne soit appliqué — {@code AbstractMove.doMove} garantit cet
     * ordre — donc {@code operation.getSetter()} y désigne encore le metteur d'origine.
     */
    @Override
    protected AbstractMove<JobShopSolution> createUndoMove(
            ScoreDirector<JobShopSolution> scoreDirector) {
        return new SetterReassignmentMove(schedule, operation, operation.getSetter());
    }

    @Override
    protected void doMoveOnGenuineVariables(ScoreDirector<JobShopSolution> scoreDirector) {
        WorkcenterReassignmentMove.calculatorOf(scoreDirector).reassignSetter(operation, target);
    }

    /** Traduit ce mouvement pour la copie de solution d'un autre fil — voir REQ-KKI-036. */
    @Override
    public Move<JobShopSolution> rebase(ScoreDirector<JobShopSolution> destinationScoreDirector) {
        return new SetterReassignmentMove(destinationScoreDirector.lookUpWorkingObject(schedule),
                destinationScoreDirector.lookUpWorkingObject(operation),
                destinationScoreDirector.lookUpWorkingObject(target));
    }

    /**
     * Le {@link Schedule} est la seule entité de planification de la solution ; l'opération reste
     * un fait du problème. C'est donc lui que le mouvement déclare, comme les deux autres.
     */
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
        return "ReassignSetter(" + operation + " -> " + target + ")";
    }
}
