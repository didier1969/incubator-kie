package kki.domain.solver;

import java.util.ArrayList;
import java.util.List;

import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * REQ-KKI-006 (réécrit) : 5000 ordres, une seule variable de planification
 * (Schedule.orderSequence), SolverManager.
 *
 * CH et recherche locale sont mesurées comme deux expériences SÉPARÉES, pas
 * deux phases enchaînées d'un même solve :
 *
 * 1. "ch_*" : la CH réelle d'OptaPlanner (ConstructionHeuristicPhaseConfig),
 *    bornée en temps par sécurité (CH_SECONDS). Pour une liste-planning-
 *    variable, OptaPlanner fige le type de CH sur QueuedValuePlacer +
 *    ListChangeMoveSelector (constructionHeuristicType n'est PAS
 *    configurable ici — DefaultConstructionHeuristicPhaseFactory rejette
 *    tout type explicite dès qu'un ListVariableDescriptor existe, vérifié en
 *    lisant la source). Ce placer essaie TOUTES les positions déjà remplies
 *    pour choisir la meilleure insertion à chaque nouvel ordre : Σk ≈ N²/2
 *    essais assign+undo, donc calculateScore_calls croît en O(N²) par
 *    construction — confirmé empiriquement (N=200→400 : 20101→80201 appels,
 *    soit 4× pour 2× ordres, cohérent avec 200×100 et 400×200). Ce n'est pas
 *    un défaut du calculateur incrémental ; c'est un comportement fixe de la
 *    CH list-variable d'OptaPlanner dans cette version. Résultat rapporté
 *    tel quel, y compris quand CH_SECONDS coupe avant la fin (placed <
 *    total) — ce nombre sert seulement à caractériser la CH, PAS à nourrir
 *    la recherche locale ci-dessous.
 *
 * 2. "ls_*" : la recherche locale seule, sur un point de départ construit
 *    SANS passer par la CH d'OptaPlanner — tous les ordres sont insérés dans
 *    Schedule.orderSequence dans l'ordre de génération (placement naïf,
 *    O(N), aucune recherche de meilleure position). Nécessaire car
 *    DefaultLocalSearchPhase.phaseStarted appelle
 *    assertWorkingSolutionInitialized (vérifié en lisant la source) : une
 *    solution partiellement placée (CH coupée par CH_SECONDS) est REJETÉE
 *    au démarrage de la phase de recherche locale — cette version
 *    d'OptaPlanner n'a pas d'équivalent "allowsUnassignedValues" sur
 *    {@literal @}PlanningListVariable pour les listes (vérifié : l'annotation
 *    ne déclare que valueRangeProviderRefs). Le placement naïf garantit une
 *    solution valide à 100% sans dépendre d'une CH qui ne termine pas à
 *    grande échelle. Le score de départ est donc nettement pire que celui
 *    d'une CH aboutie ; c'est sans effet sur ls_ips (débit d'appels
 *    calculateScore, pas qualité de solution).
 */
public final class VerticalSliceRunner {

    private static final int MACHINE_COUNT = 1000;
    private static final long CH_SECONDS = 60L;
    private static final long LOCAL_SEARCH_SECONDS = 30L;

    private VerticalSliceRunner() {
    }

    public static void main(String[] args) throws InterruptedException, java.util.concurrent.ExecutionException {
        // arg[0] = nombre d'ordres (diagnostic d'echelle, REQ-KKI-006) ; defaut 5000.
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        VerticalSliceSolution unsolved = SyntheticDataGenerator.generate(orderCount, MACHINE_COUNT, 42L);
        System.out.printf("generated orders=%d operations=%d machines=%d%n",
                unsolved.getOrderList().size(), unsolved.getOperationList().size(), unsolved.getMachineList().size());

        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(VerticalSliceIncrementalScoreCalculator.class);

        VerticalSliceSolution chSolved = runConstructionHeuristicDiagnostic(unsolved, scoreDirectorFactoryConfig);

        Schedule naiveSchedule = new Schedule();
        naiveSchedule.setOrderSequence(new ArrayList<>(unsolved.getOrderList()));
        VerticalSliceSolution naiveStart = new VerticalSliceSolution(
                unsolved.getOrderList(), unsolved.getOperationList(), unsolved.getMachineList(), List.of(naiveSchedule));
        runLocalSearch(naiveStart, "naive", scoreDirectorFactoryConfig);

        // REQ-KKI-008 : pops_per_call sur un départ CH réel (structuré) vs le départ
        // naïf ci-dessus (ordre de génération, dépendances non structurées) — décide si
        // la pathologie de propagate() (1.58M pops/appel mesuré à N=5000 naïf) vient du
        // départ naïf spécifiquement ou est une propriété générale de l'algorithme. Ne
        // tourne que si CH a placé tout le monde (sinon assertWorkingSolutionInitialized
        // rejette, cf. commentaire de classe).
        if (chSolved.getScheduleList().get(0).getOrderSequence().size() == chSolved.getOrderList().size()) {
            runLocalSearch(chSolved, "ch_start", scoreDirectorFactoryConfig);
        }
    }

    private static VerticalSliceSolution runConstructionHeuristicDiagnostic(VerticalSliceSolution unsolved,
            ScoreDirectorFactoryConfig scoreDirectorFactoryConfig) throws InterruptedException, java.util.concurrent.ExecutionException {
        SolverConfig chConfig = new SolverConfig();
        chConfig.setSolutionClass(VerticalSliceSolution.class);
        chConfig.setEntityClassList(List.of(Schedule.class));
        chConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        TerminationConfig chTermination = new TerminationConfig();
        chTermination.setSecondsSpentLimit(CH_SECONDS);
        ConstructionHeuristicPhaseConfig chPhaseConfig = new ConstructionHeuristicPhaseConfig();
        chPhaseConfig.setTerminationConfig(chTermination);
        chConfig.setPhaseConfigList(List.of(chPhaseConfig));

        VerticalSliceIncrementalScoreCalculator.CALCULATE_SCORE_CALLS.set(0);
        long chStartNanos = System.nanoTime();
        VerticalSliceSolution chSolved;
        try (SolverManager<VerticalSliceSolution, Long> chManager = SolverManager.create(chConfig)) {
            chSolved = chManager.solve(1L, unsolved).getFinalBestSolution();
        }
        double chSeconds = (System.nanoTime() - chStartNanos) / 1_000_000_000.0;
        long chCalls = VerticalSliceIncrementalScoreCalculator.CALCULATE_SCORE_CALLS.get();
        long chPlaced = chSolved.getScheduleList().get(0).getOrderSequence().size();
        System.out.printf("ch_done score=%s ch_seconds=%.2f ch_calculateScore_calls=%d placed_orders=%d/%d%n",
                chSolved.getScore(), chSeconds, chCalls, chPlaced, chSolved.getOrderList().size());
        return chSolved;
    }

    private static void runLocalSearch(VerticalSliceSolution start, String label,
            ScoreDirectorFactoryConfig scoreDirectorFactoryConfig) throws InterruptedException, java.util.concurrent.ExecutionException {
        SolverConfig lsConfig = new SolverConfig();
        lsConfig.setSolutionClass(VerticalSliceSolution.class);
        lsConfig.setEntityClassList(List.of(Schedule.class));
        lsConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        TerminationConfig localSearchTermination = new TerminationConfig();
        localSearchTermination.setSecondsSpentLimit(LOCAL_SEARCH_SECONDS);
        LocalSearchPhaseConfig localSearchPhaseConfig = new LocalSearchPhaseConfig();
        localSearchPhaseConfig.setTerminationConfig(localSearchTermination);
        lsConfig.setPhaseConfigList(List.of(localSearchPhaseConfig));

        VerticalSliceIncrementalScoreCalculator.CALCULATE_SCORE_CALLS.set(0);
        VerticalSliceIncrementalScoreCalculator.FIRST_CALL_NANOS.set(0L);
        VerticalSliceIncrementalScoreCalculator.PROPAGATION_NANOS.set(0L);
        VerticalSliceIncrementalScoreCalculator.PROPAGATE_POPS.set(0L);
        long lsStartNanos = System.nanoTime();
        VerticalSliceSolution lsSolved;
        try (SolverManager<VerticalSliceSolution, Long> lsManager = SolverManager.create(lsConfig)) {
            lsSolved = lsManager.solve(2L, start).getFinalBestSolution();
        }
        double lsSeconds = (System.nanoTime() - lsStartNanos) / 1_000_000_000.0;
        long lsCalls = VerticalSliceIncrementalScoreCalculator.CALCULATE_SCORE_CALLS.get();
        double lsIps = lsCalls / lsSeconds;
        long firstCallNanos = VerticalSliceIncrementalScoreCalculator.FIRST_CALL_NANOS.get();
        double setupSeconds = firstCallNanos == 0L ? -1.0 : (firstCallNanos - lsStartNanos) / 1_000_000_000.0;
        double propagationSeconds = VerticalSliceIncrementalScoreCalculator.PROPAGATION_NANOS.get() / 1_000_000_000.0;
        long propagatePops = VerticalSliceIncrementalScoreCalculator.PROPAGATE_POPS.get();
        double popsPerCall = lsCalls == 0 ? -1.0 : (double) propagatePops / lsCalls;
        System.out.printf(
                "ls_done[%s] score=%s ls_seconds=%.2f ls_calculateScore_calls=%d ls_ips=%.1f setup_seconds_before_first_call=%.2f propagation_seconds=%.2f propagation_pct=%.1f propagate_pops=%d pops_per_call=%.1f%n",
                label, lsSolved.getScore(), lsSeconds, lsCalls, lsIps, setupSeconds, propagationSeconds,
                100.0 * propagationSeconds / lsSeconds, propagatePops, popsPerCall);
    }
}
