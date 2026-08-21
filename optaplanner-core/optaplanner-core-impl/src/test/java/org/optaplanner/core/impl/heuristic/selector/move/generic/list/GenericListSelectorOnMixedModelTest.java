package kki.domain.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.calculator.EasyScoreCalculator;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.heuristic.selector.move.generic.list.ListSwapMoveSelectorConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.phase.PhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

/**
 * Les sélecteurs GÉNÉRIQUES de liste sur un modèle à deux classes d'entité — REQ-KKI-040.
 *
 * <p>
 * {@code MixedEntityClassesTest} prouve que le DESCRIPTEUR accepte le modèle mixte. Ce test porte
 * sur la marche suivante, et c'est elle qui décide si le catalogue de mouvements vient du produit
 * ou de chez nous : les fabriques de sélecteurs de liste appelaient
 * {@code getTheOnlyEntityDescriptor}, qui refuse de déduire dès qu'il y a plus d'une classe
 * d'entité — même quand une seule pouvait être visée.
 *
 * <p>
 * Un sélecteur de liste s'applique à une variable de liste. L'entité qui en déclare une n'est donc
 * pas une devinette. Le test passe par un VRAI solveur plutôt que par un montage manuel de
 * {@code HeuristicConfigPolicy} : c'est le chemin qu'emprunte une configuration réelle, et lui
 * seul prouve que la déduction tient de bout en bout.
 */
class GenericListSelectorOnMixedModelTest {

    @Test
    void aSolverRunsTheGenericListSwapOnASolutionWithTwoEntityClasses() throws Exception {
        MixedEntityClassesTest.Slot left = new MixedEntityClassesTest.Slot(0);
        MixedEntityClassesTest.Slot right = new MixedEntityClassesTest.Slot(1);
        MixedEntityClassesTest.Post post = new MixedEntityClassesTest.Post(0);
        MixedEntityClassesTest.MixedSolution problem = new MixedEntityClassesTest.MixedSolution(
                List.of(left, right), List.of(post),
                List.of(new MixedEntityClassesTest.Sequence(new java.util.ArrayList<>(List.of(left, right)))),
                List.of(new MixedEntityClassesTest.Assignment(post)));

        assertTrue(problem.getSequenceList().size() == 1 && problem.getAssignmentList().size() == 1,
                "le test ne mord que si la solution porte bien DEUX classes d'entité peuplées");

        MixedEntityClassesTest.MixedSolution solved;
        try (SolverManager<MixedEntityClassesTest.MixedSolution, Long> manager =
                SolverManager.create(solverConfig())) {
            solved = manager.solve(1L, problem).getFinalBestSolution();
        }

        assertNotNull(solved.getScore(),
                "le solveur doit tourner avec le listSwapMoveSelector GÉNÉRIQUE sur ce modèle."
                        + " Sans cela, chaque mouvement de liste doit être réécrit à la main alors"
                        + " qu'il appartient au produit");
        assertTrue(solved.getSequenceList().get(0).getSlots().size() == 2,
                "la liste doit rester complète après la recherche");
    }

    private static SolverConfig solverConfig() {
        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setEasyScoreCalculatorClass(MixedEasyScoreCalculator.class);

        TerminationConfig termination = new TerminationConfig();
        termination.setStepCountLimit(20);
        LocalSearchPhaseConfig phase = new LocalSearchPhaseConfig();
        phase.setTerminationConfig(termination);
        phase.setMoveSelectorConfig(new ListSwapMoveSelectorConfig());

        SolverConfig config = new SolverConfig();
        config.setSolutionClass(MixedEntityClassesTest.MixedSolution.class);
        config.setEntityClassList(
                List.of(MixedEntityClassesTest.Sequence.class, MixedEntityClassesTest.Assignment.class));
        config.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        config.setPhaseConfigList(List.<PhaseConfig> of(phase));
        return config;
    }

    /** Le score compte l'ordre de la liste : sans quoi aucun échange ne serait jamais retenu. */
    public static class MixedEasyScoreCalculator
            implements EasyScoreCalculator<MixedEntityClassesTest.MixedSolution, HardSoftLongScore> {

        @Override
        public HardSoftLongScore calculateScore(MixedEntityClassesTest.MixedSolution solution) {
            List<MixedEntityClassesTest.Slot> slots = solution.getSequenceList().get(0).getSlots();
            long penalty = 0L;
            for (int i = 0; i < slots.size(); i++) {
                penalty += (long) i * slots.get(i).getId();
            }
            return HardSoftLongScore.ofSoft(-penalty);
        }
    }
}
