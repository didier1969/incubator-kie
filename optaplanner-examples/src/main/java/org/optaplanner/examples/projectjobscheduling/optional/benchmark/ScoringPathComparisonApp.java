package org.optaplanner.examples.projectjobscheduling.optional.benchmark;

import java.io.File;
import java.util.List;

import org.optaplanner.benchmark.api.PlannerBenchmarkFactory;
import org.optaplanner.benchmark.config.PlannerBenchmarkConfig;
import org.optaplanner.benchmark.config.ProblemBenchmarksConfig;
import org.optaplanner.benchmark.config.SolverBenchmarkConfig;
import org.optaplanner.benchmark.config.statistic.ProblemStatisticType;
import org.optaplanner.core.api.score.stream.ConstraintStreamImplType;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.optaplanner.examples.projectjobscheduling.persistence.ProjectJobSchedulingSolutionFileIO;
import org.optaplanner.examples.projectjobscheduling.score.ProjectJobSchedulingConstraintProvider;

/**
 * Les DEUX voies de scoring d'OptaPlanner, sur le MÊME problème, en deux bras d'un benchmark.
 *
 * <p>
 * <b>La question, et pourquoi elle est stratégique.</b> Un calculateur incrémental écrit à la main
 * est la voie historique ; Constraint Streams est celle qu'a prise le produit. Un projet qui écrit
 * son propre calculateur est donc peut-être à côté de la voie du produit — ou peut-être plus
 * rapide qu'elle. Personne ne l'avait vérifié, et l'affaire se décidait par conviction.
 *
 * <p>
 * <b>Pourquoi cet exemple et pas un domaine maison.</b> {@code projectjobscheduling} porte les deux
 * implémentations pour le MÊME problème — {@code ProjectJobSchedulingConstraintProvider} et
 * {@code ProjectJobSchedulingIncrementalScoreCalculator} — et son
 * {@code projectJobSchedulingSolverConfig.xml} garde même la seconde en commentaire à côté de la
 * première. Poser la question ici ne coûte qu'une configuration ; la poser sur un domaine maison
 * coûterait la réécriture de tout son scoring, c'est-à-dire précisément la décision qu'on cherche
 * à éclairer.
 *
 * <p>
 * Précédences plus ressources partagées : c'est la forme d'un atelier job-shop, donc le résultat
 * s'y transpose bien mieux qu'un problème d'affectation pure.
 *
 * <p>
 * <b>Ce que le rapport dira, et ce qu'il ne dira pas.</b> {@code BEST_SCORE} donne le coût atteint
 * au cours du temps — la comparaison qui décide. {@code SCORE_CALCULATION_SPEED} donne le débit,
 * qui explique mais ne valide pas : un bon algorithme le fait volontairement baisser. Les deux
 * bras partagent l'instance, le budget et la graine ; seul le
 * {@link ScoreDirectorFactoryConfig} diffère.
 *
 * <p>
 * Usage : {@code ScoringPathComparisonApp [secondes par bras] [fichier de données…]}
 */
public class ScoringPathComparisonApp {

    private static final File DATA_DIRECTORY = new File("data/projectjobscheduling/unsolved");

    public static void main(String[] args) {
        long seconds = args.length > 0 ? Long.parseLong(args[0]) : 60L;
        List<File> problems = args.length > 1
                ? List.of(args).subList(1, args.length).stream().map(File::new).toList()
                : List.of(new File(DATA_DIRECTORY, "A-4.json"), new File(DATA_DIRECTORY, "A-10.json"));

        PlannerBenchmarkConfig benchmarkConfig = new PlannerBenchmarkConfig();
        benchmarkConfig.setName("scoring-path-comparison");
        benchmarkConfig.setBenchmarkDirectory(new File("target/scoring-path-benchmark"));
        // Sériel : la machine de mesure est partagée, et deux bras concurrents se disputeraient
        // le cache au point de pouvoir inverser le classement qu'on cherche à établir.
        benchmarkConfig.setParallelBenchmarkCount("1");
        // Le défaut hérité est 30 ms — un préchauffage que personne n'a choisi et qui change le
        // milieu de mesure. On l'épingle plutôt que d'en hériter.
        benchmarkConfig.setWarmUpMillisecondsSpentLimit(0L);

        ProblemBenchmarksConfig problemBenchmarks = new ProblemBenchmarksConfig();
        problemBenchmarks.setSolutionFileIOClass(ProjectJobSchedulingSolutionFileIO.class);
        problemBenchmarks.setInputSolutionFileList(problems);
        problemBenchmarks.setProblemStatisticTypeList(List.of(
                ProblemStatisticType.BEST_SCORE,
                ProblemStatisticType.SCORE_CALCULATION_SPEED));

        SolverBenchmarkConfig inherited = new SolverBenchmarkConfig();
        inherited.setProblemBenchmarksConfig(problemBenchmarks);
        benchmarkConfig.setInheritedSolverBenchmarkConfig(inherited);

        // TROIS bras, pas deux. Ne pas nommer l'implémentation de Constraint Streams revient à
        // mesurer DROOLS : quand les deux sont au classpath et que rien n'est demandé,
        // ScoreDirectorFactoryFactory enregistre Drools et saute Bavet — « Drools is the default
        // and already registered ». Or c'est BAVET qui porte la question stratégique, Drools
        // étant la voie héritée. Comparer sans le dire aurait produit un verdict juste sur une
        // question que personne ne pose.
        ScoreDirectorFactoryConfig drools = new ScoreDirectorFactoryConfig();
        drools.setConstraintProviderClass(ProjectJobSchedulingConstraintProvider.class);
        drools.setConstraintStreamImplType(ConstraintStreamImplType.DROOLS);

        ScoreDirectorFactoryConfig streams = new ScoreDirectorFactoryConfig();
        streams.setConstraintProviderClass(ProjectJobSchedulingConstraintProvider.class);
        streams.setConstraintStreamImplType(ConstraintStreamImplType.BAVET);

        ScoreDirectorFactoryConfig handWritten = new ScoreDirectorFactoryConfig();
        handWritten.setIncrementalScoreCalculatorClass(
                org.optaplanner.examples.projectjobscheduling.optional.score.ProjectJobSchedulingIncrementalScoreCalculator.class);

        benchmarkConfig.setSolverBenchmarkConfigList(List.of(
                arm("cs-bavet", streams, seconds),
                arm("cs-drools", drools, seconds),
                arm("incremental-ecrit-main", handWritten, seconds)));

        File report = PlannerBenchmarkFactory.create(benchmarkConfig).buildPlannerBenchmark()
                .benchmark();
        System.out.printf("scoring_path_report %s%n", report.getAbsolutePath());
    }

    /** Deux bras identiques en tout, sauf la fabrique de directeur de score. */
    private static SolverBenchmarkConfig arm(String name, ScoreDirectorFactoryConfig scoring,
            long seconds) {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource(
                "org/optaplanner/examples/projectjobscheduling/projectJobSchedulingSolverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(scoring);
        solverConfig.setTerminationConfig(new TerminationConfig().withSecondsSpentLimit(seconds));
        SolverBenchmarkConfig benchmark = new SolverBenchmarkConfig();
        benchmark.setName(name);
        benchmark.setSolverConfig(solverConfig);
        return benchmark;
    }
}
