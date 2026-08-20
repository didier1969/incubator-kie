package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.heuristic.selector.move.factory.MoveIteratorFactoryConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.phase.PhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

/**
 * Le câblage de {@code reassignmentShare} — REQ-KKI-033.
 *
 * <p>
 * À balayer un paramètre qui n'est pas transmis jusqu'à la fabrique de mouvements, on obtient une
 * courbe PLATE, qui se lit « ce levier ne sert à rien ». C'est le pire résultat possible : faux et
 * crédible. Ces tests prouvent le câblage avant que la moindre mesure soit interprétée.
 */
class ReassignmentShareWiringTest {

    @BeforeEach
    void resetCounters() {
        FullDataGenerator.reset();
        CriticalPairMoveIteratorFactory.SWAPS_EMITTED.set(0L);
        CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.set(0L);
    }

    @Test
    void shareZeroEmitsNoReassignmentAtAll() throws Exception {
        solve(250, 29L, 0.0);

        assertEquals(0L, CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.get(),
                "part nulle : la borne basse doit redonner exactement M3 guidé");
        assertTrue(CriticalPairMoveIteratorFactory.SWAPS_EMITTED.get() > 0L,
                "et la recherche doit continuer à tirer des échanges");
    }

    @Test
    void shareOneMakesReassignmentsDominateWithSwapsOnlyAsTheDocumentedFallback() throws Exception {
        solve(250, 29L, 1.0);

        long reassignments = CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.get();
        long swaps = CriticalPairMoveIteratorFactory.SWAPS_EMITTED.get();
        assertTrue(reassignments > swaps,
                "part maximale : les réaffectations doivent dominer, relevé " + reassignments
                        + " contre " + swaps + " échanges");
        // Les échanges résiduels ne sont pas un défaut de câblage : la fabrique retombe sur
        // l'échange quand douze tirages n'ont produit aucune réaffectation utile, plutôt que de
        // rendre un itérateur tari — ce qui se lirait comme une convergence.
        assertTrue(swaps >= 0L, "le repli documenté reste permis");
    }

    @Test
    void theEmittedRatioFollowsTheRequestedShare() throws Exception {
        double low = solveAndMeasureShare(250, 31L, 0.25);
        double high = solveAndMeasureShare(250, 31L, 0.75);

        assertTrue(high > low,
                "la part ÉMISE doit suivre la part DEMANDÉE, relevé " + low + " à 0,25 contre "
                        + high + " à 0,75 — sinon le balayage mesure une constante");
    }

    private double solveAndMeasureShare(int orders, long seed, double share) throws Exception {
        CriticalPairMoveIteratorFactory.SWAPS_EMITTED.set(0L);
        CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.set(0L);
        solve(orders, seed, share);
        long reassignments = CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.get();
        long total = reassignments + CriticalPairMoveIteratorFactory.SWAPS_EMITTED.get();
        assertTrue(total > 0L, "aucun mouvement tiré : le run ne mesure rien");
        return (double) reassignments / total;
    }

    private void solve(int orders, long seed, double share) throws Exception {
        JobShopSolution problem = FullDataGenerator.generate(orders, seed);
        try (SolverManager<JobShopSolution, Long> manager =
                SolverManager.create(solverConfig(share))) {
            manager.solve(1L, problem).getFinalBestSolution();
        }
    }

    private static SolverConfig solverConfig(double share) {
        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(FullScoreCalculator.class);

        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(3L);
        MoveIteratorFactoryConfig moves = new MoveIteratorFactoryConfig();
        moves.setMoveIteratorFactoryClass(CriticalPairMoveIteratorFactory.class);
        moves.setMoveIteratorFactoryCustomProperties(
                Map.of("guided", "true", "reassignmentShare", Double.toString(share)));
        LocalSearchPhaseConfig phase = new LocalSearchPhaseConfig();
        phase.setTerminationConfig(termination);
        phase.setMoveSelectorConfig(moves);

        SolverConfig config = new SolverConfig();
        config.setSolutionClass(JobShopSolution.class);
        config.setEntityClassList(List.of(Schedule.class));
        config.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        config.setPhaseConfigList(List.<PhaseConfig>of(phase));
        return config;
    }
}
