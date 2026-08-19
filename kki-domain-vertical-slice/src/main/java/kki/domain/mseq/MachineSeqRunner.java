package kki.domain.mseq;

import java.util.Comparator;
import java.util.List;

import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.heuristic.selector.move.MoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.factory.MoveIteratorFactoryConfig;
import org.optaplanner.core.config.heuristic.selector.move.generic.list.ListChangeMoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.generic.list.ListSwapMoveSelectorConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

import kki.domain.full.FullDataGenerator;
import kki.domain.full.FullScoreCalculator;
import kki.domain.full.JobShopSolution;
import kki.domain.full.Order;

/**
 * Lot B — mesure de la représentation par séquence machine, sur la MÊME instance, le MÊME budget
 * et la MÊME référence que le lot A. Sans cela les deux mesures ne se compareraient pas.
 *
 * <p>
 * Les deux mouvements sont ici natifs et n'ont plus besoin d'artifice :
 * <ul>
 * <li><b>M3</b> — {@code ListSwapMove} entre deux positions d'une même file de ressource :
 * l'inversion d'arc disjonctif au sens strict ;</li>
 * <li><b>M2</b> — {@code ListChangeMove} d'une ressource vers une autre : la substitution machine,
 * que la représentation X ne pouvait pas câbler.</li>
 * </ul>
 */
public final class MachineSeqRunner {

    private MachineSeqRunner() {
    }

    public static void main(String[] args) throws Exception {
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 300L;
        // GENERIC = echanges et deplacements quelconques ; ADJACENT = M3 au sens strict.
        String moveSet = args.length > 2 ? args[2] : "ADJACENT";

        // Même instance, même tri de départ que le lot A : les deux représentations partent du
        // même plan, sinon la comparaison ne mesurerait que la différence de point de départ.
        JobShopSolution source = FullDataGenerator.generate(orderCount, 42L);
        source.getScheduleList().get(0).getOrderSequence()
                .sort(Comparator.comparingLong(Order::getDueEpochSec));
        MachineSeqSolution problem = MachineSeqSolution.from(source);

        FullScoreCalculator xOracle = new FullScoreCalculator();
        xOracle.resetWorkingSolution(source);
        long xStartCost = -xOracle.fullSweepScore().getSoftScore();
        long startCost = -new MachineSeqCalculator().calculateScore(problem).getSoftScore();
        System.out.printf(
                "mseq_start orders=%d operations=%d machines=%d start_cost_chf=%.0f x_repr_same_plan_chf=%.0f delta_pct=%.4f%n",
                orderCount, problem.getOperationList().size(), problem.getMachineList().size(),
                startCost / 100.0, xStartCost / 100.0,
                100.0 * (startCost - xStartCost) / (double) xStartCost);

        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setEasyScoreCalculatorClass(MachineSeqCalculator.class);

        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(seconds);
        LocalSearchPhaseConfig localSearch = new LocalSearchPhaseConfig();
        localSearch.setTerminationConfig(termination);
        localSearch.setMoveSelectorConfig(moveSelector(moveSet));

        SolverConfig solverConfig = new SolverConfig();
        solverConfig.setSolutionClass(MachineSeqSolution.class);
        solverConfig.setEntityClassList(List.of(MachineSequence.class));
        solverConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        solverConfig.setPhaseConfigList(List.of(localSearch));

        MachineSeqCalculator.CALCULATE_SCORE_CALLS.set(0L);
        MachineSeqCalculator.CYCLIC_EVALUATIONS.set(0L);

        long startNanos = System.nanoTime();
        MachineSeqSolution solved;
        try (SolverManager<MachineSeqSolution, Long> manager = SolverManager.create(solverConfig)) {
            solved = manager.solve(1L, problem).getFinalBestSolution();
        }
        double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        long endCost = -solved.getScore().getSoftScore();
        long calls = MachineSeqCalculator.CALCULATE_SCORE_CALLS.get();
        long cyclic = MachineSeqCalculator.CYCLIC_EVALUATIONS.get();
        System.out.printf(
                "mseq_result moveset=%s orders=%d seconds=%.2f dps=%.1f start_cost_chf=%.0f end_cost_chf=%.0f "
                        + "reduction_pct=%.2f hard_end=%d cyclic_evaluations=%d cyclic_pct=%.2f%n",
                moveSet, orderCount, elapsed, calls / elapsed, startCost / 100.0, endCost / 100.0,
                startCost == 0L ? 0.0 : 100.0 * (startCost - endCost) / (double) startCost,
                -solved.getScore().getHardScore(), cyclic,
                calls == 0L ? 0.0 : 100.0 * cyclic / calls);
    }

    /**
     * GENERIC — échange et déplacement quelconques, tels qu'OptaPlanner les propose. Mesuré :
     * 58,7 % de ces mouvements produisent un plan impossible.
     * ADJACENT — M3 au sens strict, deux opérations consécutives d'une même file, plus le
     * déplacement inter-files pour M2.
     */
    private static MoveSelectorConfig<?> moveSelector(String moveSet) {
        if ("GENERIC".equals(moveSet)) {
            return new UnionMoveSelectorConfig().withMoveSelectors(
                    new ListSwapMoveSelectorConfig(), new ListChangeMoveSelectorConfig());
        }
        MoveIteratorFactoryConfig adjacent = new MoveIteratorFactoryConfig();
        adjacent.setMoveIteratorFactoryClass(AdjacentSwapMoveIteratorFactory.class);
        return new UnionMoveSelectorConfig()
                .withMoveSelectors(adjacent, new ListChangeMoveSelectorConfig());
    }
}
