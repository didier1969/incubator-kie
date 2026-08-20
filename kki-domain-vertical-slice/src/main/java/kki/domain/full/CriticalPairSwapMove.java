package kki.domain.full;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.heuristic.move.AbstractMove;
import org.optaplanner.core.impl.heuristic.move.Move;

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

    /**
     * CPT-KKI-004 — le verrou dur est une IMMOBILISATION, pas un surcoût : « @PlanningPin, jamais
     * déplaçable ». Un ordre déjà lancé ne bouge pas, point. Le refuser ici plutôt que le facturer
     * change la nature de la contrainte : le solveur ne peut plus l'acheter.
     */
    @Override
    public boolean isMoveDoable(ScoreDirector<JobShopSolution> scoreDirector) {
        if (leftIndex == rightIndex) {
            return false;
        }
        List<Order> sequence = schedule.getOrderSequence();
        return sequence.get(leftIndex).getFreezeLevel() != Order.FreezeLevel.HARD
                && sequence.get(rightIndex).getFreezeLevel() != Order.FreezeLevel.HARD;
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

    /**
     * Traduit ce mouvement pour la solution d'un AUTRE fil de résolution.
     *
     * <p>
     * En multi-thread chaque fil détient sa propre copie de la solution. Un mouvement construit
     * sur la copie A doit donc désigner le {@link Schedule} de la copie B avant d'y être appliqué,
     * sans quoi il modifie un objet qui n'appartient pas au fil qui l'évalue — c'est le même
     * défaut que le champ statique supprimé en {@code e5724217}, à l'échelle des fils.
     *
     * <p>
     * Les deux positions sont des ENTIERS : elles ne se traduisent pas, elles désignent le même
     * rang dans une liste de même contenu. Seul le Schedule doit l'être. C'est ce qui rend cette
     * traduction sûre alors que le contrat interdit de dépendre de l'ÉTAT des variables : on ne
     * lit ici aucune valeur de la liste.
     */
    @Override
    public Move<JobShopSolution> rebase(ScoreDirector<JobShopSolution> destinationScoreDirector) {
        return new CriticalPairSwapMove(destinationScoreDirector.lookUpWorkingObject(schedule),
                leftIndex, rightIndex);
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
