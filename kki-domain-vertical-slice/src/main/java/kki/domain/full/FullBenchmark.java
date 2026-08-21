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
import org.optaplanner.benchmark.config.statistic.SingleStatisticType;


/**
 * REQ-KKI-037 — le banc rend des COURBES, pas des points.
 *
 * <p>
 * <b>Ce qui rend ce fichier nécessaire.</b> Toutes les campagnes de ce projet mesurent un coût
 * atteint à une abscisse unique. Il a fallu quatre campagnes et une inversion de verdict pour
 * apprendre qu'à 120 s Hill Climbing gagne et qu'à 900 s il est troisième — une seule courbe
 * l'aurait montré du premier coup. La question « quel budget choisir » ne se pose plus quand on
 * dispose de la courbe : on la lit à l'abscisse voulue.
 *
 * <p>
 * <b>Ce que le module amont fournit déjà.</b> {@code BEST_SCORE} donne le coût au cours du temps,
 * {@code SCORE_CALCULATION_SPEED} le débit au cours du temps — et ce second graphique lit
 * DIRECTEMENT les épisodes de famine d'une machine partagée, ce qu'aucun chiffre final ne peut
 * faire. {@code STEP_SCORE} et {@code MOVE_COUNT_PER_STEP} disent si le solveur progresse par
 * petits pas nombreux ou par grands pas rares. Le rapport HTML est produit par le module.
 *
 * <p>
 * <b>Le blocage supposé n'existait pas.</b> {@code REQ-KKI-037} posait qu'il manquait un
 * {@code SolutionFileIO} sérialisant le descripteur d'instance. Faux :
 * {@link PlannerBenchmarkFactory#buildPlannerBenchmark(Object...)} accepte des instances EN
 * MÉMOIRE. Ni format de fichier, ni sérialisation, ni descripteur à versionner.
 *
 * <p>
 * <b>Pourquoi plusieurs bras tiennent dans une même JVM</b> alors que les réglages du banc sont
 * des champs statiques : {@link FullRunner#solverConfigOf} les lit à la CONSTRUCTION et les gèle
 * dans la configuration produite. Faire varier un statique entre deux constructions produit donc
 * bien deux bras distincts.
 *
 * <p>
 * Usage : {@code FullBenchmark <ordres> <secondes> <graine> [jours ouvrés metteur]}
 */
public final class FullBenchmark {

    private FullBenchmark() {
    }

    /** Un bras : un nom, et le réglage qu'il fait varier. */
    private record Arm(String name, String acceptorType, Integer acceptorSize, double reassignmentShare) {
    }

    public static void main(String[] args) {
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 900L;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42L;
        if (args.length > 3) {
            FullDataGenerator.setterWorkingDays = Integer.parseInt(args[3]);
        }

        // L'instance est construite UNE fois et partagée par tous les bras : la génération est
        // déterministe à graine fixée, donc tous mesurent exactement le même problème.
        JobShopSolution problem = FullDataGenerator.generate(orderCount, seed);
        FullRunner.applyStart(problem, FullRunner.Start.GEN);
        System.out.printf("benchmark_instance orders=%d seconds=%d seed=%d setter_days=%d%n",
                orderCount, seconds, seed, FullDataGenerator.setterWorkingDays);

        List<Arm> arms = List.of(
                new Arm("lahc5-share1.0", "LATE_ACCEPTANCE", 5, 1.0),
                new Arm("lahc50-share1.0", "LATE_ACCEPTANCE", 50, 1.0),
                new Arm("hill-share1.0", "HILL_CLIMBING", null, 1.0),
                new Arm("lahc400-defaut-moteur", "LATE_ACCEPTANCE", 400, 1.0),
                new Arm("lahc5-share0.5", "LATE_ACCEPTANCE", 5, 0.5));

        PlannerBenchmarkConfig benchmarkConfig = new PlannerBenchmarkConfig();
        benchmarkConfig.setName("kki-courbe-acceptor");
        benchmarkConfig.setBenchmarkDirectory(new File("target/benchmark"));
        // SÉRIEL, sans exception. La machine porte une charge externe permanente (load 20 sur
        // 16 cœurs, mesuré le 2026-08-21) : lancer des bras en parallèle les ferait se disputer
        // le cache et pourrait INVERSER un classement.
        benchmarkConfig.setParallelBenchmarkCount("1");
        // Le défaut hérité est 30 ms, pas 0. Un préchauffage non mesuré change le milieu de
        // mesure sans que personne ne l'ait choisi : on l'épingle.
        benchmarkConfig.setWarmUpMillisecondsSpentLimit(0L);

        ProblemBenchmarksConfig statistics = new ProblemBenchmarksConfig();
        statistics.setProblemStatisticTypeList(List.of(
                ProblemStatisticType.BEST_SCORE,
                ProblemStatisticType.SCORE_CALCULATION_SPEED,
                ProblemStatisticType.STEP_SCORE,
                ProblemStatisticType.MOVE_COUNT_PER_STEP));
        // REQ-KKI-053 — depuis que FullScoreCalculator expose ses contraintes, la courbe peut
        // dire QUELLE contrainte baisse, et pas seulement que le coût baisse. C'est la question
        // « quel terme domine » qui a commandé chaque discussion de régime de ce projet :
        // retard 2,5e13 contre metteur 3,96e07, six ordres de grandeur d'écart.
        statistics.setSingleStatisticTypeList(List.of(
                SingleStatisticType.CONSTRAINT_MATCH_TOTAL_BEST_SCORE));
        SolverBenchmarkConfig inherited = new SolverBenchmarkConfig();
        inherited.setProblemBenchmarksConfig(statistics);
        benchmarkConfig.setInheritedSolverBenchmarkConfig(inherited);

        String savedType = FullRunner.acceptorType;
        Integer savedSize = FullRunner.acceptorSize;
        double savedShare = FullRunner.reassignmentShare;
        List<SolverBenchmarkConfig> solverBenchmarks = new ArrayList<>();
        try {
            for (Arm arm : arms) {
                FullRunner.acceptorType = arm.acceptorType();
                FullRunner.acceptorSize = arm.acceptorSize();
                FullRunner.reassignmentShare = arm.reassignmentShare();
                SolverBenchmarkConfig solverBenchmark = new SolverBenchmarkConfig();
                solverBenchmark.setName(arm.name());
                solverBenchmark.setSolverConfig(
                        FullRunner.solverConfigOf(FullRunner.Variant.M5, seconds));
                solverBenchmarks.add(solverBenchmark);
            }
        } finally {
            FullRunner.acceptorType = savedType;
            FullRunner.acceptorSize = savedSize;
            FullRunner.reassignmentShare = savedShare;
        }
        benchmarkConfig.setSolverBenchmarkConfigList(solverBenchmarks);

        PlannerBenchmark benchmark = PlannerBenchmarkFactory.create(benchmarkConfig)
                .buildPlannerBenchmark(problem);
        File report = benchmark.benchmark();
        System.out.printf("benchmark_report %s%n", report.getAbsolutePath());
    }
}
