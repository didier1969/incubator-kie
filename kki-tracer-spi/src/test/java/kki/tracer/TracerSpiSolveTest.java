package kki.tracer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.solver.SolverJob;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

/**
 * REQ-KKI-001 acceptance criterion: "solveur custom resout une instance
 * jouet et termine" — via SolverManager (async, service-oriented API),
 * not the blocking Solver API: SolverManager is the integration shape
 * that actually fits PIL-KKI-004 (continuous planning, addProblemChange),
 * confirmed as what optaplanner-quarkus wires up (DEC-KKI, SolverManagerConfig
 * bean in OptaPlannerProcessor#recordAndRegisterBeans) even though this
 * prototype stays plain-Java, no Quarkus dependency, per GUI-PRO-025.
 */
class TracerSpiSolveTest {

    @Test
    void solverManagerBuildsAndTerminatesOnToyInstance() throws InterruptedException, ExecutionException {
        SolverConfig solverConfig = new SolverConfig();
        solverConfig.setSolutionClass(TaskAssignmentSolution.class);
        solverConfig.setEntityClassList(Collections.singletonList(Task.class));

        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(TaskAssignmentIncrementalScoreCalculator.class);
        solverConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);

        solverConfig.setPhaseConfigList(List.of(new ConstructionHeuristicPhaseConfig()));

        TerminationConfig terminationConfig = new TerminationConfig();
        terminationConfig.setSecondsSpentLimit(5L);
        solverConfig.setTerminationConfig(terminationConfig);

        try (SolverManager<TaskAssignmentSolution, Long> solverManager = SolverManager.create(solverConfig)) {
            TaskAssignmentSolution unsolved = toyInstance();
            SolverJob<TaskAssignmentSolution, Long> solverJob = solverManager.solve(1L, unsolved);
            TaskAssignmentSolution solved = solverJob.getFinalBestSolution();

            assertNotNull(solved.getScore());
            for (Task task : solved.getTaskList()) {
                assertNotNull(task.getMachine(), "every task must end up assigned by construction heuristic");
            }
            assertTrue(solved.getScore().hardScore() >= -1000, "sanity bound, not a tuned quality bar");
        }
    }

    private static TaskAssignmentSolution toyInstance() {
        List<Machine> machines = new ArrayList<>();
        for (long i = 0; i < 5; i++) {
            machines.add(new Machine(i, 20));
        }
        List<Task> tasks = new ArrayList<>();
        for (long i = 0; i < 20; i++) {
            Map<Machine, Long> costByMachine = new HashMap<>();
            for (Machine machine : machines) {
                costByMachine.put(machine, (i * 7 + machine.getId() * 3) % 11);
            }
            tasks.add(new Task(i, 3 + (i % 4), costByMachine));
        }
        return new TaskAssignmentSolution(machines, tasks);
    }
}
