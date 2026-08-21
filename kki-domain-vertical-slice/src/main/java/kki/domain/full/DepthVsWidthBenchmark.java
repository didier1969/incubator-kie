package kki.domain.full;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.optaplanner.benchmark.api.PlannerBenchmark;
import org.optaplanner.benchmark.api.PlannerBenchmarkFactory;
import org.optaplanner.benchmark.config.PlannerBenchmarkConfig;
import org.optaplanner.benchmark.config.ProblemBenchmarksConfig;
import org.optaplanner.benchmark.config.SolverBenchmarkConfig;
import org.optaplanner.benchmark.config.statistic.ProblemStatisticType;

/**
 * REQ-KKI-036 volet C, repris par la COURBE : le débit ne rend rien parce que le problème est
 * épuisé, ou parce que la recherche est inefficace ?
 *
 * <p>
 * <b>La question, et pourquoi deux points ne peuvent pas y répondre.</b> Le volet C a mesuré
 * ×5,7 de débit pour 0 % de gain de coût, et en a conclu « largeur contre profondeur ». C'était
 * une INFÉRENCE : deux coûts finaux ne disent pas où le débit est parti.
 *
 * <p>
 * Deux causes possibles, aux conséquences opposées :
 * <ul>
 * <li><b>Épuisement</b> — la recherche a convergé, plus aucune évaluation ne peut aider. Le
 * débit est alors sans valeur, et toute la pile d'accélération (SIMD, FFM, GPU) est vaine sur
 * ce problème.</li>
 * <li><b>Inefficacité</b> — la recherche progresse encore, mais les évaluations
 * supplémentaires sont dépensées en largeur au lieu de profondeur. Le débit garde alors de la
 * valeur, à condition de corriger la recherche d'abord.</li>
 * </ul>
 *
 * <p>
 * <b>Ce que la courbe tranche et que deux points ne tranchaient pas.</b> {@code BEST_SCORE} au
 * cours du temps montre la PENTE en fin de run : plate, la recherche est épuisée ; encore
 * descendante, elle ne l'est pas. {@code STEP_SCORE} et {@code MOVE_COUNT_PER_STEP} disent où
 * est parti le débit — plus de PAS, ou plus de mouvements PAR pas.
 *
 * <p>
 * Indice déjà en main : à configuration et graine identiques, passer de 120 s à 900 s fait
 * chuter le coût de 3,029e12 à 1,488e12, soit −50,9 % pour ×7,5 de budget. Une recherche
 * épuisée ne fait pas ça. Mais un indice n'est pas une courbe.
 *
 * <p>
 * Usage : {@code DepthVsWidthBenchmark <ordres> <secondes> <graine>}
 */
public final class DepthVsWidthBenchmark {

    private DepthVsWidthBenchmark() {
    }

    public static void main(String[] args) {
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 900L;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42L;

        JobShopSolution problem = FullDataGenerator.generate(orderCount, seed);
        FullRunner.applyStart(problem, FullRunner.Start.GEN);
        System.out.printf("depth_width_instance orders=%d seconds=%d seed=%d%n",
                orderCount, seconds, seed);

        PlannerBenchmarkConfig config = new PlannerBenchmarkConfig();
        config.setName("profondeur-contre-largeur");
        config.setBenchmarkDirectory(new File("target/depth-width"));
        config.setParallelBenchmarkCount("1");
        config.setWarmUpMillisecondsSpentLimit(0L);

        ProblemBenchmarksConfig statistics = new ProblemBenchmarksConfig();
        statistics.setProblemStatisticTypeList(List.of(
                ProblemStatisticType.BEST_SCORE,
                ProblemStatisticType.STEP_SCORE,
                ProblemStatisticType.MOVE_COUNT_PER_STEP,
                ProblemStatisticType.SCORE_CALCULATION_SPEED));
        SolverBenchmarkConfig inherited = new SolverBenchmarkConfig();
        inherited.setProblemBenchmarksConfig(statistics);
        config.setInheritedSolverBenchmarkConfig(inherited);

        List<SolverBenchmarkConfig> arms = new ArrayList<>();
        for (String threads : List.of("1", "8")) {
            SolverBenchmarkConfig arm = new SolverBenchmarkConfig();
            arm.setName(threads + "-fil" + ("1".equals(threads) ? "" : "s"));
            var solverConfig = FullRunner.solverConfigOf(FullRunner.Variant.M5, seconds);
            solverConfig.setMoveThreadCount(threads);
            arm.setSolverConfig(solverConfig);
            arms.add(arm);
        }
        config.setSolverBenchmarkConfigList(arms);

        PlannerBenchmark benchmark = PlannerBenchmarkFactory.create(config)
                .buildPlannerBenchmark(problem);
        System.out.printf("depth_width_report %s%n", benchmark.benchmark().getAbsolutePath());
    }
}
