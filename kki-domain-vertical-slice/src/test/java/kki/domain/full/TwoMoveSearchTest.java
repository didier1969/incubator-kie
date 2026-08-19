package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.heuristic.selector.move.factory.MoveIteratorFactoryConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.phase.PhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

/**
 * Les DEUX mouvements du paradigme, dans la même boucle de recherche.
 *
 * <p>
 * Le second — déplacer une opération vers un autre workcenter compatible — avait été relégué dans
 * une phase exécutée une seule fois. Mesuré : 300 réaffectations contre 825 820 échanges de
 * position, soit 2 750 contre 1. Les tests qui suivent portent chacun sur une manière précise
 * dont ce mouvement peut être présent dans le code sans être présent dans la recherche.
 */
class TwoMoveSearchTest {

    @BeforeEach
    void resetDomainParameters() {
        FullDataGenerator.reset();
        CriticalPairMoveIteratorFactory.SWAPS_EMITTED.set(0L);
        CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.set(0L);
    }

    @Test
    void theSelectorActuallyEmitsBothMoveTypes() {
        // LE test qui décide. Un second mouvement écrit, compilé et testé unitairement mais
        // jamais tiré laisserait tous les autres tests verts sans rien changer à la recherche —
        // c'est exactement l'état dont on sort.
        JobShopSolution solution = FullDataGenerator.generate(400, 5L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        int swaps = 0;
        int reassignments = 0;
        Random random = new Random(7L);
        Schedule schedule = solution.getScheduleList().get(0);
        for (int draw = 0; draw < 400; draw++) {
            FullScoreCalculator.Reassignment candidate =
                    calculator.sampleOverloadedReassignment(random);
            if (candidate != null) {
                reassignments++;
            } else if (calculator.sampleTightAdjacentPair(random) != null) {
                swaps++;
            }
        }
        assertTrue(reassignments > 20,
                "le second mouvement doit être proposé régulièrement, tiré " + reassignments
                        + " fois sur 400");
        assertTrue(swaps > 0, "le premier mouvement doit rester proposé, tiré " + swaps + " fois");
        assertTrue(schedule.getOrderSequence().size() > 0);
    }

    @Test
    void theGuidedReassignmentAlwaysAimsAtALessLoadedWorkcenter() {
        // Un guidage qui ne guide pas produirait des mouvements presque toujours rejetés, et le
        // budget partirait en évaluations perdues. On vérifie la propriété, pas l'intention.
        JobShopSolution solution = FullDataGenerator.generate(500, 11L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        Random random = new Random(13L);
        int checked = 0;
        for (int draw = 0; draw < 500; draw++) {
            FullScoreCalculator.Reassignment candidate =
                    calculator.sampleOverloadedReassignment(random);
            if (candidate == null) {
                continue;
            }
            checked++;
            assertTrue(candidate.target() != candidate.operation().getMachine(),
                    "une réaffectation vers le poste courant est un no-op exact");
            assertTrue(candidate.target().canRun(candidate.operation().getRequiredTechnology(),
                    candidate.operation().getRequiredLevel()),
                    "la cible doit respecter la compatibilité ascendante");
            assertTrue(candidate.operation().getOrder().getFreezeLevel() != Order.FreezeLevel.HARD,
                    "un ordre à verrou dur ne se déplace pas, pas même de poste");
        }
        assertTrue(checked > 50, "trop peu de candidats pour que le test morde : " + checked);
    }

    @Test
    void theMoveIsExactlyUndoneByItsOwnUndoMove() {
        // OptaPlanner évalue puis ANNULE chaque mouvement. Une annulation inexacte ferait dériver
        // le plan à chaque candidat rejeté, sans que le score le dise — la propagation étant
        // correcte des deux côtés.
        JobShopSolution solution = FullDataGenerator.generate(300, 17L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);
        var before = calculator.calculateScore();

        Random random = new Random(19L);
        Schedule schedule = solution.getScheduleList().get(0);
        int applied = 0;
        for (int draw = 0; draw < 60; draw++) {
            FullScoreCalculator.Reassignment candidate =
                    calculator.sampleOverloadedReassignment(random);
            if (candidate == null) {
                continue;
            }
            WorkcenterReassignmentMove move =
                    new WorkcenterReassignmentMove(schedule, candidate.operation(),
                            candidate.target());
            Machine origin = candidate.operation().getMachine();
            calculator.reassignMachine(candidate.operation(), candidate.target());
            calculator.reassignMachine(candidate.operation(), origin);
            applied++;
            assertTrue(move.toString().contains("Reassign"));
        }
        assertTrue(applied > 10, "trop peu d'allers-retours pour conclure : " + applied);
        assertEquals(before, calculator.calculateScore(),
                "après autant d'allers-retours, le plan doit être EXACTEMENT celui du départ");
        assertEquals(calculator.fullSweepScore(), calculator.calculateScore(),
                "et l'état incrémental doit rester d'accord avec l'oracle");
    }

    @Test
    void hardFrozenOrdersAreNeverReassigned() {
        // CPT-KKI-004 : le verrou dur est une immobilisation, et cela vaut pour le POSTE autant
        // que pour la position. Une opération déjà lancée ne change pas de machine.
        JobShopSolution solution = FullDataGenerator.generate(600, 23L);
        Schedule schedule = solution.getScheduleList().get(0);
        Operation frozen = solution.getOperationList().stream()
                .filter(op -> op.getOrder().getFreezeLevel() == Order.FreezeLevel.HARD)
                .filter(op -> op.getCompatibleMachines().size() > 1)
                .findFirst()
                .orElseThrow();
        Machine other = frozen.getCompatibleMachines().stream()
                .filter(machine -> machine != frozen.getMachine())
                .findFirst()
                .orElseThrow();
        assertFalse(new WorkcenterReassignmentMove(schedule, frozen, other).isMoveDoable(null),
                "déplacer une opération d'un ordre à verrou dur doit être refusé, pas facturé");
    }

    @Test
    void aRealSolverRunKeepsTheScoreConsistentWithBothMovesEnabled() throws Exception {
        // Le différentiel, mais à travers le VRAI moteur : c'est lui qui construit les undo,
        // annule, et rejoue le mouvement retenu. Un défaut de ce cycle ne se voit pas en appelant
        // reassignMachine à la main.
        JobShopSolution problem = FullDataGenerator.generate(250, 29L);
        JobShopSolution solved;
        try (SolverManager<JobShopSolution, Long> manager = SolverManager.create(solverConfig())) {
            solved = manager.solve(1L, problem).getFinalBestSolution();
        }

        assertTrue(CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.get() > 0,
                "le solveur n'a tiré AUCUNE réaffectation : le second mouvement n'est pas dans la"
                        + " boucle, quoi qu'en dise le reste");
        assertTrue(CriticalPairMoveIteratorFactory.SWAPS_EMITTED.get() > 0,
                "le solveur n'a tiré aucun échange de position");

        FullScoreCalculator oracle = new FullScoreCalculator();
        oracle.resetWorkingSolution(solved);
        assertEquals(oracle.fullSweepScore(), solved.getScore(),
                "le score rendu par le solveur ne décrit pas le plan qu'il rend");
    }

    private static SolverConfig solverConfig() {
        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(FullScoreCalculator.class);

        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(3L);
        MoveIteratorFactoryConfig moves = new MoveIteratorFactoryConfig();
        moves.setMoveIteratorFactoryClass(CriticalPairMoveIteratorFactory.class);
        moves.setMoveIteratorFactoryCustomProperties(
                Map.of("guided", "true", "reassignmentShare", "0.5"));
        LocalSearchPhaseConfig phase = new LocalSearchPhaseConfig();
        phase.setTerminationConfig(termination);
        phase.setMoveSelectorConfig(moves);

        SolverConfig config = new SolverConfig();
        config.setSolutionClass(JobShopSolution.class);
        config.setEntityClassList(List.of(Schedule.class));
        config.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        config.setPhaseConfigList(List.<PhaseConfig>of(phase));
        return config;
    }
}
