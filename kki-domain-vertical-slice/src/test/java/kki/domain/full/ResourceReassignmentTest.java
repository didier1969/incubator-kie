package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.heuristic.selector.move.factory.MoveIteratorFactoryConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.phase.PhaseConfig;
import org.optaplanner.core.config.phase.custom.CustomPhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

/**
 * M4 — le CÂBLAGE des décisions de ressource. `GUI-PRO-115` : un livrable n'est livré que s'il
 * est câblé, pas seulement écrit.
 *
 * <p>
 * `reassignMachine`, `reassignSetter` et `reassignTooling` étaient testées unitairement mais
 * jamais appelées par le solveur : du code correct que rien n'exécutait. Ce test lance un VRAI
 * solveur avec la phase de réaffectation entre deux phases de recherche, et vérifie les deux
 * choses qui peuvent mal tourner à ce joint :
 * <ul>
 * <li><b>la cohérence du score</b> — la commande change des FAITS sous le directeur de score ; si
 * celui-ci ne recalculait pas, le solveur conclurait sur un score qui ne décrit plus le plan ;</li>
 * <li><b>l'exécution réelle</b> — une commande qui n'accepterait jamais rien laisserait le test
 * vert tout en ne câblant rien du tout.</li>
 * </ul>
 */
class ResourceReassignmentTest {

    @Test
    void theCustomPhaseActuallyReassignsAndLeavesTheScoreConsistent() throws Exception {
        JobShopSolution problem = FullDataGenerator.generate(300, 103L);
        int savedAttempts = ResourceReassignmentPhaseCommand.attempts;
        try {
            ResourceReassignmentPhaseCommand.attempts = 60;
            ResourceReassignmentPhaseCommand.ACCEPTED_LAST_RUN = 0;

            JobShopSolution solved;
            try (SolverManager<JobShopSolution, Long> manager =
                    SolverManager.create(solverConfig())) {
                solved = manager.solve(1L, problem).getFinalBestSolution();
            }

            assertTrue(ResourceReassignmentPhaseCommand.ACCEPTED_LAST_RUN > 0,
                    "la phase n'a retenu AUCUNE réaffectation : le levier n'est pas exercé");

            // La vérification qui compte : le score sur lequel le solveur a conclu doit décrire
            // le plan qu'il rend. Un écart ici voudrait dire que la commande a modifié des faits
            // dans le dos du directeur de score.
            FullScoreCalculator oracle = new FullScoreCalculator();
            oracle.resetWorkingSolution(solved);
            assertEquals(oracle.fullSweepScore(), solved.getScore(),
                    "le score rendu par le solveur ne correspond pas au plan rendu");
        } finally {
            ResourceReassignmentPhaseCommand.attempts = savedAttempts;
        }
    }

    @Test
    void reassignmentsAreExactlyReversible() {
        // La descente stricte ANNULE tout ce qui n'améliore pas. Si l'annulation n'était pas
        // exacte, chaque essai refusé laisserait une trace et le plan dériverait sans que le
        // score le dise — la propagation étant, elle, correcte des deux côtés.
        JobShopSolution solution = FullDataGenerator.generate(200, 107L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);
        var before = calculator.calculateScore();

        Operation op = solution.getOperationList().stream()
                .filter(o -> o.getRequiredToolingType() != Operation.NO_TOOLING
                        && o.getCompatibleToolings().size() > 1)
                .findFirst()
                .orElseThrow();
        Machine originalMachine = op.getMachine();
        Setter originalSetter = op.getSetter();
        Tooling originalTooling = op.getTooling();

        Machine otherMachine = op.getCompatibleMachines().stream()
                .filter(m -> m != originalMachine).findFirst().orElseThrow();
        calculator.reassignMachine(op, otherMachine);
        calculator.reassignMachine(op, originalMachine);

        Setter otherSetter = solution.getSetterList().stream()
                .filter(s -> s != originalSetter && s.canSetUp(op.getMachine()))
                .findFirst().orElseThrow();
        calculator.reassignSetter(op, otherSetter);
        calculator.reassignSetter(op, originalSetter);

        Tooling otherTooling = op.getCompatibleToolings().stream()
                .filter(t -> t != originalTooling).findFirst().orElseThrow();
        calculator.reassignTooling(op, otherTooling);
        calculator.reassignTooling(op, originalTooling);

        assertEquals(before, calculator.calculateScore(),
                "un aller-retour sur les trois ressources doit rendre EXACTEMENT le plan de départ");
        assertEquals(calculator.fullSweepScore(), calculator.calculateScore(),
                "et l'état incrémental doit rester d'accord avec l'oracle");
    }

    private static SolverConfig solverConfig() {
        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(FullScoreCalculator.class);

        CustomPhaseConfig reassignment = new CustomPhaseConfig();
        reassignment.setCustomPhaseCommandClassList(List.of(ResourceReassignmentPhaseCommand.class));

        SolverConfig config = new SolverConfig();
        config.setSolutionClass(JobShopSolution.class);
        config.setEntityClassList(List.of(Schedule.class));
        config.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        config.setPhaseConfigList(List.<PhaseConfig>of(localSearch(1L), reassignment, localSearch(1L)));
        return config;
    }

    private static LocalSearchPhaseConfig localSearch(long seconds) {
        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(seconds);
        MoveIteratorFactoryConfig moves = new MoveIteratorFactoryConfig();
        moves.setMoveIteratorFactoryClass(CriticalPairMoveIteratorFactory.class);
        moves.setMoveIteratorFactoryCustomProperties(Map.of("guided", "true"));
        LocalSearchPhaseConfig phase = new LocalSearchPhaseConfig();
        phase.setTerminationConfig(termination);
        phase.setMoveSelectorConfig(moves);
        return phase;
    }
}
