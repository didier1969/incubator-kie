package kki.domain.full;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.heuristic.selector.move.MoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.factory.MoveIteratorFactoryConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

/**
 * Mesure sur le domaine COMPLET : débit et réduction de coût, à l'échelle réelle.
 *
 * <p>
 * Le seul mouvement configuré est le <b>swap de position X</b> (M1 de CPT-KKI-012). Ce n'est pas
 * une restriction de commodité : le concept autorise le swap, pas le relocate, et un relocate
 * décalerait toute la plage d'ordres entre les deux positions au lieu de deux ordres — ce qui
 * changerait la nature du mouvement mesuré.
 *
 * <pre>
 *   java ... kki.domain.full.FullRunner [ordres] [secondes]   défaut : 5000 60
 * </pre>
 */
public final class FullRunner {

    private FullRunner() {
    }

    /** Jeu de mouvements activé, pour décomposer le gain par incrément (A4). */
    public enum Variant {
        /** M1 seul — échange de deux positions X tirées au hasard. Référence REQ-KKI-012. */
        M1,
        /** M3 — mêmes échanges, mais guidés vers les arcs disjonctifs tendus. */
        M3
    }

    public static void main(String[] args) throws Exception {
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 60L;
        Variant variant = args.length > 2 ? Variant.valueOf(args[2]) : Variant.M3;

        JobShopSolution problem = FullDataGenerator.generate(orderCount, 42L);
        System.out.printf("full_instance orders=%d operations=%d machines=%d%n",
                problem.getOrderList().size(), problem.getOperationList().size(),
                problem.getMachineList().size());

        FullScoreCalculator oracle = new FullScoreCalculator();
        oracle.resetWorkingSolution(problem);
        long randomOrderCost = -oracle.fullSweepScore().getSoftScore();

        // RÉFÉRENCE HONNÊTE : « plus urgent d'abord ». Mesurer une réduction contre un ordre de
        // génération aléatoire flatterait le solveur d'un gain qu'un planificateur obtient à la
        // main en triant sa liste. C'est contre CETTE référence que la valeur se juge.
        problem.getScheduleList().get(0).getOrderSequence()
                .sort(Comparator.comparingLong(Order::getDueEpochSec));
        oracle.resetWorkingSolution(problem);
        long startCost = -oracle.fullSweepScore().getSoftScore();
        long startHard = -oracle.fullSweepScore().getHardScore();
        System.out.printf("full_baseline random_order_chf=%.0f earliest_due_date_chf=%.0f edd_gain_pct=%.2f%n",
                randomOrderCost / 100.0, startCost / 100.0,
                100.0 * (randomOrderCost - startCost) / (double) randomOrderCost);

        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(FullScoreCalculator.class);

        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(seconds);
        LocalSearchPhaseConfig localSearch = new LocalSearchPhaseConfig();
        localSearch.setTerminationConfig(termination);
        localSearch.setMoveSelectorConfig(moveSelectorOf(variant));

        SolverConfig solverConfig = new SolverConfig();
        solverConfig.setSolutionClass(JobShopSolution.class);
        solverConfig.setEntityClassList(List.of(Schedule.class));
        solverConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        solverConfig.setPhaseConfigList(List.of(localSearch));

        FullScoreCalculator.CALCULATE_SCORE_CALLS.set(0L);
        FullScoreCalculator.DIRTY_OPERATIONS.set(0L);
        FullScoreCalculator.ORDER_COMPLETION_CHANGES.set(0L);
        FullScoreCalculator.PROPAGATIONS.set(0L);

        long startNanos = System.nanoTime();
        JobShopSolution solved;
        try (SolverManager<JobShopSolution, Long> manager = SolverManager.create(solverConfig)) {
            solved = manager.solve(1L, problem).getFinalBestSolution();
        }
        double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        long endCost = -solved.getScore().getSoftScore();
        long calls = FullScoreCalculator.CALCULATE_SCORE_CALLS.get();
        long propagations = Math.max(1L, FullScoreCalculator.PROPAGATIONS.get());
        long dirty = FullScoreCalculator.DIRTY_OPERATIONS.get();
        long orderChanges = FullScoreCalculator.ORDER_COMPLETION_CHANGES.get();

        System.out.printf(
                "full_result variant=%s orders=%d seconds=%.2f dps=%.1f moves=%d "
                        + "start_cost_chf=%.0f end_cost_chf=%.0f reduction_pct=%.2f "
                        + "hard_start=%d hard_end=%d "
                        + "dirty_per_move=%.1f order_changes_per_move=%.1f cost_relevant_pct=%.2f%n",
                variant, orderCount, elapsed, calls / elapsed, propagations,
                startCost / 100.0, endCost / 100.0,
                startCost == 0L ? 0.0 : 100.0 * (startCost - endCost) / (double) startCost,
                startHard, -solved.getScore().getHardScore(),
                (double) dirty / propagations, (double) orderChanges / propagations,
                dirty == 0L ? 0.0 : 100.0 * orderChanges / dirty);
    }

    /**
     * M1 = échange X uniforme · M2 = changement de machine compatible · M3 = échange X guidé vers
     * les arcs tendus. M3 remplace M1 plutôt que de s'y ajouter : les deux produisent le même type
     * de mouvement, l'un au hasard et l'autre en sachant pourquoi.
     */
    private static MoveSelectorConfig<?> moveSelectorOf(Variant variant) {
        return switch (variant) {
            case M1 -> swapSelector(false);
            case M3 -> swapSelector(true);
        };
    }

    /**
     * L'échange X passe par notre propre fabrique dans les deux modes. Avec deux classes
     * d'entités, le {@code ListSwapMoveSelector} d'OptaPlanner ne sait plus déduire l'entité à
     * laquelle il s'applique — mais surtout, comparer M1 et M3 exige que seul le CHOIX de la
     * paire diffère, pas la mécanique du mouvement.
     */
    private static MoveSelectorConfig<?> swapSelector(boolean guided) {
        MoveIteratorFactoryConfig config = new MoveIteratorFactoryConfig();
        config.setMoveIteratorFactoryClass(CriticalPairMoveIteratorFactory.class);
        config.setMoveIteratorFactoryCustomProperties(Map.of("guided", Boolean.toString(guided)));
        return config;
    }

}
