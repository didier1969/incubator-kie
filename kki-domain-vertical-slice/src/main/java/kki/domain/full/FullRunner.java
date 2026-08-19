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

    /**
     * Graine de l'instance. Un verdict qui ne tient que sur une graine n'est pas un verdict :
     * c'est une observation sur un tirage.
     */
    public static long seed = 42L;

    /** Jeu de mouvements activé, pour décomposer le gain par incrément (A4). */
    public enum Variant {
        /** M1 seul — échange de deux positions X tirées au hasard. Référence REQ-KKI-012. */
        M1,
        /** M3 — mêmes échanges, mais guidés vers les arcs disjonctifs tendus. */
        M3,
        /**
         * M4 — M3, coupé en deux par une phase de réaffectation de ressource. C'est le CÂBLAGE
         * des mouvements (4) à (7) de CPT-KKI-010, que DEC-KKI-004 interdit d'exprimer comme des
         * variables de planification.
         */
        M4
    }

    public static void main(String[] args) throws Exception {
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 60L;
        Variant variant = args.length > 2 ? Variant.valueOf(args[2]) : Variant.M3;
        if (args.length > 3) {
            FullDataGenerator.levelDemandSkew = Double.parseDouble(args[3]);
        }

        JobShopSolution problem = FullDataGenerator.generate(orderCount, seed);
        System.out.printf("full_instance orders=%d operations=%d machines=%d setters=%d"
                + " toolings=%d level_skew=%.1f%n",
                problem.getOrderList().size(), problem.getOperationList().size(),
                problem.getMachineList().size(), problem.getSetterList().size(),
                problem.getToolingList().size(), FullDataGenerator.levelDemandSkew);

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
        System.out.print(oracle.coldSweep().describe("depart"));
        System.out.print(oracle.backwardSweep().describe("depart", startCost));
        System.out.print(oracle.latenessProfile("depart"));

        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(FullScoreCalculator.class);

        SolverConfig solverConfig = new SolverConfig();
        solverConfig.setSolutionClass(JobShopSolution.class);
        solverConfig.setEntityClassList(List.of(Schedule.class));
        solverConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        solverConfig.setPhaseConfigList(phasesOf(variant, seconds));

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

        oracle.resetWorkingSolution(solved);
        System.out.print(oracle.coldSweep().describe("arrivee"));
        System.out.print(oracle.backwardSweep()
                .describe("arrivee", -oracle.fullSweepScore().getSoftScore()));

        long endCost = -solved.getScore().getSoftScore();
        long calls = FullScoreCalculator.CALCULATE_SCORE_CALLS.get();
        long propagations = Math.max(1L, FullScoreCalculator.PROPAGATIONS.get());
        long dirty = FullScoreCalculator.DIRTY_OPERATIONS.get();
        long orderChanges = FullScoreCalculator.ORDER_COMPLETION_CHANGES.get();

        // ⚠️ `reduction_pct` ne regarde que le SOUPLE, et le score est hard/soft avec le dur
        // prioritaire. Un solveur qui échange du dur contre du souple — remettre en place des
        // ordres à verrou dur au prix d'un peu de retard — fait exactement son travail et
        // affiche pourtant une « dégradation ». Le verdict lisible est donc la comparaison des
        // DEUX composantes, pas d'une seule.
        long endHard = -solved.getScore().getHardScore();
        String verdict;
        if (endHard <= startHard && endCost <= startCost) {
            verdict = "IMPROVED_BOTH";
        } else if (endHard < startHard) {
            verdict = "TRADED_SOFT_FOR_HARD";
        } else if (endCost < startCost) {
            verdict = "TRADED_HARD_FOR_SOFT";
        } else {
            verdict = "WORSE_BOTH";
        }
        System.out.printf(
                "full_result variant=%s orders=%d seconds=%.2f dps=%.1f moves=%d "
                        + "start_cost_chf=%.0f end_cost_chf=%.0f soft_reduction_pct=%.2f "
                        + "hard_start=%d hard_end=%d hard_reduction_pct=%.2f verdict=%s "
                        + "dirty_per_move=%.1f order_changes_per_move=%.1f cost_relevant_pct=%.2f%n",
                variant, orderCount, elapsed, calls / elapsed, propagations,
                startCost / 100.0, endCost / 100.0,
                startCost == 0L ? 0.0 : 100.0 * (startCost - endCost) / (double) startCost,
                startHard, endHard,
                startHard == 0L ? 0.0 : 100.0 * (startHard - endHard) / (double) startHard,
                verdict,
                (double) dirty / propagations, (double) orderChanges / propagations,
                dirty == 0L ? 0.0 : 100.0 * orderChanges / dirty);
        if (variant == Variant.M4) {
            System.out.printf("reassignment attempts=%d accepted=%d%n",
                    ResourceReassignmentPhaseCommand.attempts,
                    ResourceReassignmentPhaseCommand.ACCEPTED_LAST_RUN);
        }
    }

    /**
     * M1 = échange X uniforme · M3 = échange X guidé vers les arcs tendus · M4 = M3 coupé par une
     * phase de réaffectation de ressource. M3 remplace M1 plutôt que de s'y ajouter : les deux
     * produisent le même type de mouvement, l'un au hasard et l'autre en sachant pourquoi.
     *
     * <p>
     * Le budget de temps est réparti à parts égales entre les phases de recherche de M4, pour que
     * la comparaison à M3 se fasse à budget total ÉGAL — sans quoi la phase de réaffectation
     * serait payée par du temps que M3 n'a pas eu.
     */
    private static List<org.optaplanner.core.config.phase.PhaseConfig> phasesOf(Variant variant,
            long seconds) {
        if (variant != Variant.M4) {
            return List.of(localSearchOf(variant, seconds));
        }
        org.optaplanner.core.config.phase.custom.CustomPhaseConfig reassignment =
                new org.optaplanner.core.config.phase.custom.CustomPhaseConfig();
        reassignment.setCustomPhaseCommandClassList(
                List.of(ResourceReassignmentPhaseCommand.class));
        return List.of(localSearchOf(Variant.M3, seconds / 2), reassignment,
                localSearchOf(Variant.M3, seconds - seconds / 2));
    }

    private static LocalSearchPhaseConfig localSearchOf(Variant variant, long seconds) {
        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(seconds);
        LocalSearchPhaseConfig localSearch = new LocalSearchPhaseConfig();
        localSearch.setTerminationConfig(termination);
        localSearch.setMoveSelectorConfig(switch (variant) {
            case M1 -> swapSelector(false);
            default -> swapSelector(true);
        });
        return localSearch;
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
