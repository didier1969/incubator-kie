package kki.domain.solver;

import java.util.ArrayList;
import java.util.List;

import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import org.optaplanner.core.config.heuristic.selector.common.nearby.NearbySelectionConfig;
import org.optaplanner.core.config.heuristic.selector.common.nearby.NearbySelectionDistributionType;
import org.optaplanner.core.config.heuristic.selector.list.DestinationSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.MoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.generic.list.ListChangeMoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.generic.list.ListSwapMoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.value.ValueSelectorConfig;
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
    private static final int NEARBY_POOL_SIZE_DEFAULT = 50;

    private VerticalSliceRunner() {
    }

    public static void main(String[] args) throws InterruptedException, java.util.concurrent.ExecutionException {
        // arg[0] = nombre d'ordres (diagnostic d'echelle, REQ-KKI-006) ; defaut 5000.
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        // arg[1] = budget recherche locale en secondes pour l'appel "naive" UNIQUEMENT
        // (REQ-KKI-007, diagnostic sensibilite au budget temps) ; defaut LOCAL_SEARCH_SECONDS,
        // comportement inchange si absent.
        long naiveLsSeconds = args.length > 1 ? Long.parseLong(args[1]) : LOCAL_SEARCH_SECONDS;
        // arg[2] = taille de pool nearby (REQ-KKI-007 piste (d), sweep) ; defaut
        // NEARBY_POOL_SIZE_DEFAULT = la valeur mesuree a la session precedente, donc
        // comportement inchange si absent.
        int nearbyPoolSize = args.length > 2 ? Integer.parseInt(args[2]) : NEARBY_POOL_SIZE_DEFAULT;
        // arg[3] = sauter la CH diagnostic (REQ-KKI-007) : a N>=1000 elle ne termine pas
        // (O(N2) essais, cf. corps du REQ) et consomme CH_SECONDS par point de mesure pour
        // un resultat inutilise -- le bras ch_start en depend et est saute avec elle.
        boolean skipCh = args.length > 3 && Boolean.parseBoolean(args[3]);
        // arg[4] = borne en NOMBRE DE PAS au lieu du temps (0 = borne au temps, defaut).
        // Sert a comparer deux bras a nombre de mouvements egal : separe "qualite par
        // mouvement" de "mouvements par seconde" dans score(T) = produit des deux.
        long stepCountLimit = args.length > 4 ? Long.parseLong(args[4]) : 0L;
        VerticalSliceSolution unsolved = SyntheticDataGenerator.generate(orderCount, MACHINE_COUNT, 42L);
        System.out.printf("generated orders=%d operations=%d machines=%d%n",
                unsolved.getOrderList().size(), unsolved.getOperationList().size(), unsolved.getMachineList().size());

        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(VerticalSliceIncrementalScoreCalculator.class);

        VerticalSliceSolution chSolved =
                skipCh ? null : runConstructionHeuristicDiagnostic(unsolved, scoreDirectorFactoryConfig);

        Schedule naiveSchedule = new Schedule();
        naiveSchedule.setOrderSequence(new ArrayList<>(unsolved.getOrderList()));
        VerticalSliceSolution naiveStart = new VerticalSliceSolution(
                unsolved.getOrderList(), unsolved.getOperationList(), unsolved.getMachineList(), List.of(naiveSchedule));
        runLocalSearch(naiveStart, "naive", scoreDirectorFactoryConfig, naiveLsSeconds, false,
                nearbyPoolSize, stepCountLimit);

        // REQ-KKI-007 piste (d) : meme depart naif, meme budget, SEULE la selection de
        // mouvement change (nearby au lieu d'uniforme sur toute la sequence) --
        // comparable directement a "naive" ci-dessus. Signal d'acceptation AVANT tout :
        // mean_span_per_move doit chuter nettement (sinon la config est inerte, pas
        // "sans effet" -- verifier avant d'interpreter ls_ips/score, cf. corps
        // REQ-KKI-006/007).
        runLocalSearch(naiveStart, "naive_nearby", scoreDirectorFactoryConfig, naiveLsSeconds, true,
                nearbyPoolSize, stepCountLimit);

        // REQ-KKI-008 : pops_per_call sur un départ CH réel (structuré) vs le départ
        // naïf ci-dessus (ordre de génération, dépendances non structurées) — décide si
        // la pathologie de propagate() (1.58M pops/appel mesuré à N=5000 naïf) vient du
        // départ naïf spécifiquement ou est une propriété générale de l'algorithme. Ne
        // tourne que si CH a placé tout le monde (sinon assertWorkingSolutionInitialized
        // rejette, cf. commentaire de classe).
        if (chSolved != null
                && chSolved.getScheduleList().get(0).getOrderSequence().size() == chSolved.getOrderList().size()) {
            runLocalSearch(chSolved, "ch_start", scoreDirectorFactoryConfig, LOCAL_SEARCH_SECONDS, false,
                    nearbyPoolSize, stepCountLimit);
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
            ScoreDirectorFactoryConfig scoreDirectorFactoryConfig, long lsSeconds, boolean nearbySelection,
            int nearbyPoolSize, long stepCountLimit)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        SolverConfig lsConfig = new SolverConfig();
        lsConfig.setSolutionClass(VerticalSliceSolution.class);
        lsConfig.setEntityClassList(List.of(Schedule.class));
        lsConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        TerminationConfig localSearchTermination = new TerminationConfig();
        if (stepCountLimit > 0L) {
            // Borne en nombre de pas : les deux bras executent exactement le meme nombre de
            // mouvements, donc l'ecart de score mesure la QUALITE PAR MOUVEMENT seule (le
            // debit sort de l'equation). Exclusif de la borne au temps -- les cumuler
            // rendrait indeterminable laquelle a coupe.
            localSearchTermination.setStepCountLimit((int) stepCountLimit);
        } else {
            localSearchTermination.setSecondsSpentLimit(lsSeconds);
        }
        LocalSearchPhaseConfig localSearchPhaseConfig = new LocalSearchPhaseConfig();
        localSearchPhaseConfig.setTerminationConfig(localSearchTermination);
        String effectiveLabel = nearbySelection ? label + "_p" + nearbyPoolSize : label;
        if (nearbySelection) {
            localSearchPhaseConfig.setMoveSelectorConfig(buildNearbyMoveSelectorConfig(nearbyPoolSize));
        }
        lsConfig.setPhaseConfigList(List.of(localSearchPhaseConfig));

        VerticalSliceIncrementalScoreCalculator.CALCULATE_SCORE_CALLS.set(0);
        VerticalSliceIncrementalScoreCalculator.FIRST_CALL_NANOS.set(0L);
        VerticalSliceIncrementalScoreCalculator.PROPAGATION_NANOS.set(0L);
        VerticalSliceIncrementalScoreCalculator.PROPAGATE_POPS.set(0L);
        VerticalSliceIncrementalScoreCalculator.TOPOLOGICAL_INVERSIONS.set(0L);
        VerticalSliceIncrementalScoreCalculator.PROPAGATE_DIRTY_POPS.set(0L);
        VerticalSliceIncrementalScoreCalculator.MOVE_SPAN_TOTAL.set(0L);
        VerticalSliceIncrementalScoreCalculator.PROPAGATION_CALLS.set(0L);
        long lsStartNanos = System.nanoTime();
        VerticalSliceSolution lsSolved;
        try (SolverManager<VerticalSliceSolution, Long> lsManager = SolverManager.create(lsConfig)) {
            lsSolved = lsManager.solve(2L, start).getFinalBestSolution();
        }
        double lsSecondsElapsed = (System.nanoTime() - lsStartNanos) / 1_000_000_000.0;
        long lsCalls = VerticalSliceIncrementalScoreCalculator.CALCULATE_SCORE_CALLS.get();
        double lsIps = lsCalls / lsSecondsElapsed;
        long firstCallNanos = VerticalSliceIncrementalScoreCalculator.FIRST_CALL_NANOS.get();
        double setupSeconds = firstCallNanos == 0L ? -1.0 : (firstCallNanos - lsStartNanos) / 1_000_000_000.0;
        double propagationSeconds = VerticalSliceIncrementalScoreCalculator.PROPAGATION_NANOS.get() / 1_000_000_000.0;
        long propagatePops = VerticalSliceIncrementalScoreCalculator.PROPAGATE_POPS.get();
        double popsPerCall = lsCalls == 0 ? -1.0 : (double) propagatePops / lsCalls;
        long inversions = VerticalSliceIncrementalScoreCalculator.TOPOLOGICAL_INVERSIONS.get();
        long dirtyPops = VerticalSliceIncrementalScoreCalculator.PROPAGATE_DIRTY_POPS.get();
        double dirtyPerCall = lsCalls == 0 ? -1.0 : (double) dirtyPops / lsCalls;
        double noopPopPct = propagatePops == 0 ? -1.0 : 100.0 * (1.0 - (double) dirtyPops / propagatePops);
        long propagationCalls = VerticalSliceIncrementalScoreCalculator.PROPAGATION_CALLS.get();
        long moveSpanTotal = VerticalSliceIncrementalScoreCalculator.MOVE_SPAN_TOTAL.get();
        double meanSpanPerMove = propagationCalls == 0 ? -1.0 : (double) moveSpanTotal / propagationCalls;
        System.out.printf(
                "ls_done[%s] score=%s ls_seconds=%.2f ls_calculateScore_calls=%d ls_ips=%.1f setup_seconds_before_first_call=%.2f propagation_seconds=%.2f propagation_pct=%.1f propagate_pops=%d pops_per_call=%.1f topological_inversions=%d dirty_pops=%d dirty_per_call=%.1f noop_pop_pct=%.1f propagation_calls=%d mean_span_per_move=%.1f%n",
                effectiveLabel, lsSolved.getScore(), lsSecondsElapsed, lsCalls, lsIps, setupSeconds, propagationSeconds,
                100.0 * propagationSeconds / lsSecondsElapsed, propagatePops, popsPerCall, inversions,
                dirtyPops, dirtyPerCall, noopPopPct, propagationCalls, meanSpanPerMove);
    }

    /**
     * REQ-KKI-007 piste (d) : UnionMoveSelectorConfig(ListChange, ListSwap), tous
     * deux avec une destination/valeur secondaire choisie par NearbySelectionConfig
     * (PARABOLIC_DISTRIBUTION, taille = poolSize, passee en arg[2] du runner pour le
     * balayage) au lieu de la selection uniforme sur toute la sequence par defaut
     * d'OptaPlanner (mesure : mean_span_per_move ~= N/3 sans ceci, cf. corps
     * REQ-KKI-006/007). OrderPositionNearbyDistanceMeter mesure la distance via
     * xPosition[] LIVE (pas Order.getId(), qui ne suit l'ordre de sequence qu'au
     * tout premier instant naif).
     */
    private static MoveSelectorConfig buildNearbyMoveSelectorConfig(int poolSize) {
        // mimicSelectorRef obligatoire (verifie empiriquement, IllegalArgumentException
        // sans ceci : "A nearby's original value should always be the same as a value
        // selected earlier in the move") -- l'origine du nearby DOIT pointer par id vers
        // le value selector primaire du meme mouvement, pas un ValueSelectorConfig vide.
        ValueSelectorConfig changeValueSelectorConfig = new ValueSelectorConfig();
        changeValueSelectorConfig.setId("changeValue");
        ListChangeMoveSelectorConfig changeConfig = new ListChangeMoveSelectorConfig();
        changeConfig.setValueSelectorConfig(changeValueSelectorConfig);
        DestinationSelectorConfig destinationSelectorConfig = new DestinationSelectorConfig();
        destinationSelectorConfig.setNearbySelectionConfig(buildNearbySelectionConfig("changeValue", poolSize));
        changeConfig.setDestinationSelectorConfig(destinationSelectorConfig);

        ValueSelectorConfig swapValueSelectorConfig = new ValueSelectorConfig();
        swapValueSelectorConfig.setId("swapValue");
        ListSwapMoveSelectorConfig swapConfig = new ListSwapMoveSelectorConfig();
        swapConfig.setValueSelectorConfig(swapValueSelectorConfig);
        ValueSelectorConfig secondaryValueSelectorConfig = new ValueSelectorConfig();
        secondaryValueSelectorConfig.setNearbySelectionConfig(buildNearbySelectionConfig("swapValue", poolSize));
        swapConfig.setSecondaryValueSelectorConfig(secondaryValueSelectorConfig);

        List<MoveSelectorConfig> moveSelectorConfigList = new ArrayList<>();
        moveSelectorConfigList.add(changeConfig);
        moveSelectorConfigList.add(swapConfig);
        UnionMoveSelectorConfig unionConfig = new UnionMoveSelectorConfig();
        unionConfig.setMoveSelectorList(moveSelectorConfigList);
        return unionConfig;
    }

    private static NearbySelectionConfig buildNearbySelectionConfig(String mimicSelectorRef, int poolSize) {
        ValueSelectorConfig originValueSelectorConfig = new ValueSelectorConfig();
        originValueSelectorConfig.setMimicSelectorRef(mimicSelectorRef);
        NearbySelectionConfig nearbySelectionConfig = new NearbySelectionConfig();
        nearbySelectionConfig.setOriginValueSelectorConfig(originValueSelectorConfig);
        nearbySelectionConfig.setNearbyDistanceMeterClass(OrderPositionNearbyDistanceMeter.class);
        nearbySelectionConfig.setNearbySelectionDistributionType(NearbySelectionDistributionType.PARABOLIC_DISTRIBUTION);
        nearbySelectionConfig.setParabolicDistributionSizeMaximum(poolSize);
        return nearbySelectionConfig;
    }
}
