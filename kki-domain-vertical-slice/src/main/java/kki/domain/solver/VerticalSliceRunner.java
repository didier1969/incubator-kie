package kki.domain.solver;

import java.util.List;

import org.optaplanner.core.api.solver.SolverJob;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

import kki.domain.Order;
import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * REQ-KKI-006 (réécrit) : 5000 ordres, une seule variable de planification
 * (Schedule.orderSequence), SolverManager.
 */
public final class VerticalSliceRunner {

    private static final int MACHINE_COUNT = 1000;
    private static final long LOCAL_SEARCH_SECONDS = 30L;

    private VerticalSliceRunner() {
    }

    public static void main(String[] args) throws InterruptedException, java.util.concurrent.ExecutionException {
        // arg[0] = nombre d'ordres (diagnostic d'echelle, REQ-KKI-006) ; defaut 5000.
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        VerticalSliceSolution unsolved = SyntheticDataGenerator.generate(orderCount, MACHINE_COUNT, 42L);
        System.out.printf("generated orders=%d operations=%d machines=%d%n",
                unsolved.getOrderList().size(), unsolved.getOperationList().size(), unsolved.getMachineList().size());

        SolverConfig solverConfig = new SolverConfig();
        solverConfig.setSolutionClass(VerticalSliceSolution.class);
        solverConfig.setEntityClassList(List.of(Order.class, Schedule.class));

        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(VerticalSliceIncrementalScoreCalculator.class);
        solverConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);

        TerminationConfig localSearchTermination = new TerminationConfig();
        localSearchTermination.setSecondsSpentLimit(LOCAL_SEARCH_SECONDS);
        LocalSearchPhaseConfig localSearchPhaseConfig = new LocalSearchPhaseConfig();
        localSearchPhaseConfig.setTerminationConfig(localSearchTermination);

        solverConfig.setPhaseConfigList(List.of(new ConstructionHeuristicPhaseConfig(), localSearchPhaseConfig));

        long startNanos = System.nanoTime();
        VerticalSliceIncrementalScoreCalculator.CALCULATE_SCORE_CALLS.set(0);

        try (SolverManager<VerticalSliceSolution, Long> solverManager = SolverManager.create(solverConfig)) {
            SolverJob<VerticalSliceSolution, Long> job = solverManager.solve(1L, unsolved);
            VerticalSliceSolution solved = job.getFinalBestSolution();

            double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            long calls = VerticalSliceIncrementalScoreCalculator.CALCULATE_SCORE_CALLS.get();
            double ips = calls / elapsedSeconds;

            long placed = solved.getScheduleList().get(0).getOrderSequence().size();
            System.out.printf("solved score=%s elapsed_s=%.2f calculateScore_calls=%d ips=%.1f placed_orders=%d/%d%n",
                    solved.getScore(), elapsedSeconds, calls, ips, placed, solved.getOrderList().size());
        }
    }
}
