package kki.domain.full;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.heuristic.move.AbstractMove;
import org.optaplanner.core.impl.score.director.incremental.IncrementalScoreDirector;

/**
 * Le SECOND mouvement du paradigme : déplacer une opération vers un autre workcenter compatible.
 *
 * <p>
 * L'opérateur l'a posé dès le départ, à parité avec l'échange de position X : « seuls deux
 * mouvements sont autorisés, swap de position X pour les ordres, et swap d'une op sur une autre
 * machine compatible ». Il avait pourtant disparu de la recherche — relégué dans une phase
 * exécutée une seule fois, à raison de trois cents essais contre huit cent mille échanges X.
 *
 * <p>
 * <b>Pourquoi il peut être un mouvement, contrairement à ce que j'avais conclu.</b>
 * `DEC-KKI-004` établit qu'OptaPlanner ne fait pas coexister une variable-liste et une variable
 * simple. Cela interdit d'exprimer la machine comme {@code @PlanningVariable} — cela n'interdit
 * pas un {@link AbstractMove} qui modifie un fait et sait se défaire. La conclusion du premier au
 * second était la mienne, pas celle du moteur.
 *
 * <p>
 * <b>Pourquoi il est indispensable.</b> L'échange X change l'ordre de passage sur les postes ; il
 * ne déplace jamais une opération d'un poste vers un autre. Un poste surchargé le reste donc quel
 * que soit le budget de recherche — mesuré : quinze minutes font passer les postes au-dessus de
 * cent pour cent de 133 à 131. Rééquilibrer exige ce mouvement-ci et aucun autre.
 */
public final class WorkcenterReassignmentMove extends AbstractMove<JobShopSolution> {

    private final Schedule schedule;
    private final Operation operation;
    private final Machine target;

    public WorkcenterReassignmentMove(Schedule schedule, Operation operation, Machine target) {
        this.schedule = schedule;
        this.operation = operation;
        this.target = target;
    }

    /**
     * `CPT-KKI-004` — un ordre à verrou dur est IMMOBILISÉ, pas pénalisé. Cela vaut pour son poste
     * autant que pour sa position : déplacer une opération déjà lancée sur une autre machine est
     * aussi impossible que de la déplacer dans le temps.
     *
     * <p>
     * La compatibilité est vérifiée ici plutôt que laissée à {@code reassignMachine}, qui lève :
     * un mouvement non réalisable doit être écarté, pas provoquer une exception au milieu d'un
     * pas de recherche.
     */
    @Override
    public boolean isMoveDoable(ScoreDirector<JobShopSolution> scoreDirector) {
        return operation.getOrder().getFreezeLevel() != Order.FreezeLevel.HARD
                && target != operation.getMachine()
                && target.canRun(operation.getRequiredTechnology(), operation.getRequiredLevel());
    }

    /**
     * Construit AVANT que le mouvement ne soit appliqué — {@code AbstractMove.doMove} garantit cet
     * ordre — donc {@code operation.getMachine()} y désigne encore le poste d'origine. C'est ce
     * qui rend l'inverse exact sans mémoriser d'état ailleurs.
     */
    @Override
    protected AbstractMove<JobShopSolution> createUndoMove(
            ScoreDirector<JobShopSolution> scoreDirector) {
        return new WorkcenterReassignmentMove(schedule, operation, operation.getMachine());
    }

    /**
     * Le calculateur se demande AU DIRECTEUR DE SCORE qui exécute ce mouvement, et jamais à un
     * champ statique.
     *
     * <p>
     * L'ancienne version lisait {@code FullScoreCalculator.LIVE}. Sous
     * {@code EnvironmentMode.FULL_ASSERT} le moteur tient DEUX directeurs de score — celui de
     * travail et celui qui recalcule à partir de rien — et chacun écrit {@code LIVE = this} en
     * s'initialisant. Un champ statique ne désigne qu'un objet : le mouvement s'appliquait donc
     * sur la solution de l'autre directeur. Mesuré, pas supposé : le détecteur du moteur a rendu
     * {@code Score corruption (25307413soft)} sur un {@code Reassign(Op100@M253) -> M253} dont la
     * source et la cible étaient le même poste — un no-op que {@code isMoveDoable} avait bien
     * refusé, mais sur l'autre solution.
     */
    @Override
    protected void doMoveOnGenuineVariables(ScoreDirector<JobShopSolution> scoreDirector) {
        calculatorOf(scoreDirector).reassignMachine(operation, target);
    }

    /** Le calculateur incrémental du directeur PASSÉ EN ARGUMENT, sans état partagé. */
    static FullScoreCalculator calculatorOf(ScoreDirector<JobShopSolution> scoreDirector) {
        if (!(scoreDirector instanceof IncrementalScoreDirector<JobShopSolution, ?> incremental)) {
            throw new IllegalStateException(
                    "ce mouvement exige un directeur de score incrémental, reçu " + scoreDirector);
        }
        return (FullScoreCalculator) incremental.getIncrementalScoreCalculator();
    }

    /**
     * Le {@link Schedule} est la seule entité de planification de la solution — l'opération, elle,
     * reste un fait ({@code DEC-KKI-004}). C'est donc lui que le mouvement déclare, exactement
     * comme l'échange de position X.
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
        return "Reassign(" + operation + " -> " + target + ")";
    }
}
