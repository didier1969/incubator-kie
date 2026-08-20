package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;

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
 * Les mouvements (6) METTEUR et (7) OUTILLAGE de CPT-KKI-010 — REQ-KKI-039.
 *
 * <p>
 * Ces deux ressources sont les plus rares du modèle : 242 metteurs portent ~76 mises en train
 * chacun, 120 exemplaires d'outillage ~64 emprunts, contre ~18 opérations par machine sur 1000
 * postes. Leurs primitives existaient depuis REQ-KKI-029, mais aucun {@code Move} ne les tirait :
 * elles étaient écrites sans être dans la recherche.
 *
 * <p>
 * Chaque test porte sur une manière précise dont ces mouvements peuvent être présents dans le
 * code sans l'être dans la boucle, ou être présents en produisant un plan faux.
 */
class ScarceResourceMoveTest {

    @BeforeEach
    void resetDomainParameters() {
        FullDataGenerator.reset();
        CriticalPairMoveIteratorFactory.SETTER_MOVES_EMITTED.set(0L);
        CriticalPairMoveIteratorFactory.TOOLING_MOVES_EMITTED.set(0L);
        CriticalPairMoveIteratorFactory.SWAPS_EMITTED.set(0L);
        CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.set(0L);
    }

    @Test
    void theSetterSamplerOnlyEverProposesACompetentSetter() {
        // La compétence est un MUR : reassignSetter LÈVE sur un metteur incompétent. Un
        // échantillonneur qui en proposerait un ferait tomber le solveur, pas dégrader le plan.
        JobShopSolution solution = FullDataGenerator.generate(600, 5L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        Random random = new Random(11L);
        int checked = 0;
        for (int draw = 0; draw < 600; draw++) {
            FullScoreCalculator.SetterReassignment candidate =
                    calculator.sampleOverloadedSetterReassignment(random);
            if (candidate == null) {
                continue;
            }
            checked++;
            assertTrue(candidate.target().canSetUp(candidate.operation().getMachine()),
                    candidate.target() + " ne sait pas régler " + candidate.operation().getMachine());
            assertTrue(candidate.target() != candidate.operation().getSetter(),
                    "réaffecter au metteur courant est un no-op exact");
            assertTrue(candidate.operation().getOrder().getFreezeLevel() != Order.FreezeLevel.HARD,
                    "un ordre à verrou dur ne change pas de metteur");
        }
        assertTrue(checked > 30, "trop peu de candidats pour que le test morde : " + checked);
    }

    @Test
    void theToolingSamplerOnlyEverProposesTheRequiredType() {
        // Deux exemplaires du même type sont interchangeables ; deux types différents ne le sont
        // pas, et reassignTooling lève. Même nature de garde que la compétence du metteur.
        JobShopSolution solution = FullDataGenerator.generate(600, 7L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        Random random = new Random(13L);
        int checked = 0;
        for (int draw = 0; draw < 600; draw++) {
            FullScoreCalculator.ToolingReassignment candidate =
                    calculator.sampleContendedToolingReassignment(random);
            if (candidate == null) {
                continue;
            }
            checked++;
            assertEquals(candidate.operation().getRequiredToolingType(), candidate.target().getType(),
                    "l'exemplaire proposé n'est pas du type exigé");
            assertTrue(candidate.target() != candidate.operation().getTooling(),
                    "réaffecter à l'exemplaire courant est un no-op exact");
        }
        assertTrue(checked > 10, "trop peu de candidats pour que le test morde : " + checked);
    }

    @Test
    void bothMovesAreExactlyUndoneByTheirOwnUndoMove() {
        // OptaPlanner évalue puis ANNULE chaque mouvement. Une annulation inexacte ferait dériver
        // le plan à chaque candidat rejeté, sans que le score le dise.
        JobShopSolution solution = FullDataGenerator.generate(400, 17L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);
        var before = calculator.calculateScore();

        Random random = new Random(19L);
        int applied = 0;
        for (int draw = 0; draw < 120; draw++) {
            FullScoreCalculator.SetterReassignment setterMove =
                    calculator.sampleOverloadedSetterReassignment(random);
            if (setterMove != null) {
                Setter origin = setterMove.operation().getSetter();
                calculator.reassignSetter(setterMove.operation(), setterMove.target());
                calculator.reassignSetter(setterMove.operation(), origin);
                applied++;
            }
            FullScoreCalculator.ToolingReassignment toolingMove =
                    calculator.sampleContendedToolingReassignment(random);
            if (toolingMove != null) {
                Tooling origin = toolingMove.operation().getTooling();
                calculator.reassignTooling(toolingMove.operation(), toolingMove.target());
                calculator.reassignTooling(toolingMove.operation(), origin);
                applied++;
            }
        }
        assertTrue(applied > 20, "trop peu d'allers-retours pour conclure : " + applied);
        assertEquals(before, calculator.calculateScore(),
                "après autant d'allers-retours, le plan doit être EXACTEMENT celui du départ");
        assertEquals(calculator.fullSweepScore(), calculator.calculateScore(),
                "et l'état incrémental doit rester d'accord avec l'oracle");
    }

    @Test
    void hardFrozenOrdersChangeNeitherSetterNorTooling() {
        // CPT-KKI-004 : le verrou dur est une IMMOBILISATION. Cela vaut pour la ressource autant
        // que pour la position — une opération lancée ne change ni de metteur ni d'outillage.
        JobShopSolution solution = FullDataGenerator.generate(600, 23L);
        Schedule schedule = solution.getScheduleList().get(0);
        Operation frozen = solution.getOperationList().stream()
                .filter(op -> op.getOrder().getFreezeLevel() == Order.FreezeLevel.HARD)
                .filter(op -> op.getRequiredToolingType() != Operation.NO_TOOLING)
                .findFirst()
                .orElseThrow();
        Setter otherSetter = solution.getSetterList().stream()
                .filter(setter -> setter != frozen.getSetter())
                .filter(setter -> setter.canSetUp(frozen.getMachine()))
                .findFirst()
                .orElseThrow();
        Tooling otherTooling = frozen.getCompatibleToolings().stream()
                .filter(tooling -> tooling != frozen.getTooling())
                .findFirst()
                .orElseThrow();

        assertFalse(new SetterReassignmentMove(schedule, frozen, otherSetter).isMoveDoable(null),
                "changer le metteur d'un ordre à verrou dur doit être refusé, pas facturé");
        assertFalse(new ToolingReassignmentMove(schedule, frozen, otherTooling).isMoveDoable(null),
                "changer l'outillage d'un ordre à verrou dur doit être refusé, pas facturé");
    }

    @Test
    void aRealSolverRunEmitsBothScarceResourceMovesAndKeepsTheScoreConsistent() throws Exception {
        // LE test qui décide. Deux mouvements écrits, compilés et testés unitairement mais jamais
        // TIRÉS laisseraient tous les autres tests verts sans rien changer à la recherche — c'est
        // exactement l'état dont REQ-KKI-031 avait dû sortir pour le second mouvement.
        JobShopSolution problem = FullDataGenerator.generate(400, 29L);
        JobShopSolution solved;
        try (SolverManager<JobShopSolution, Long> manager = SolverManager.create(solverConfig())) {
            solved = manager.solve(1L, problem).getFinalBestSolution();
        }

        assertTrue(CriticalPairMoveIteratorFactory.SETTER_MOVES_EMITTED.get() > 0,
                "le solveur n'a tiré AUCUNE réaffectation de metteur : le mouvement (6) n'est pas"
                        + " dans la boucle, quoi qu'en dise le reste");
        assertTrue(CriticalPairMoveIteratorFactory.TOOLING_MOVES_EMITTED.get() > 0,
                "le solveur n'a tiré AUCUN échange d'outillage : le mouvement (7) n'est pas dans"
                        + " la boucle");

        FullScoreCalculator oracle = new FullScoreCalculator();
        oracle.resetWorkingSolution(solved);
        assertEquals(oracle.fullSweepScore(), solved.getScore(),
                "le score rendu par le solveur ne décrit pas le plan qu'il rend");
    }

    @Test
    void aZeroScarceShareRestoresExactlyThePreviousBehaviour() {
        // Les campagnes A/B/C/D ont été mesurées SANS ces mouvements. Si la part nulle en tirait
        // ne serait-ce qu'un, aucune comparaison avec l'existant ne tiendrait plus.
        JobShopSolution problem = FullDataGenerator.generate(400, 31L);
        try (SolverManager<JobShopSolution, Long> manager =
                SolverManager.create(solverConfig(0.0))) {
            manager.solve(1L, problem).getFinalBestSolution();
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertEquals(0L, CriticalPairMoveIteratorFactory.SETTER_MOVES_EMITTED.get(),
                "part nulle : aucun mouvement de metteur ne doit être tiré");
        assertEquals(0L, CriticalPairMoveIteratorFactory.TOOLING_MOVES_EMITTED.get(),
                "part nulle : aucun échange d'outillage ne doit être tiré");
        assertTrue(CriticalPairMoveIteratorFactory.SWAPS_EMITTED.get() > 0,
                "et la recherche doit continuer à tirer les mouvements d'origine");
    }

    private static SolverConfig solverConfig() {
        return solverConfig(0.5);
    }

    private static SolverConfig solverConfig(double scarceShare) {
        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(FullScoreCalculator.class);

        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(3L);
        MoveIteratorFactoryConfig moves = new MoveIteratorFactoryConfig();
        moves.setMoveIteratorFactoryClass(CriticalPairMoveIteratorFactory.class);
        moves.setMoveIteratorFactoryCustomProperties(Map.of(
                "guided", "true",
                "reassignmentShare", "0.3",
                "scarceResourceShare", Double.toString(scarceShare)));
        LocalSearchPhaseConfig phase = new LocalSearchPhaseConfig();
        phase.setTerminationConfig(termination);
        phase.setMoveSelectorConfig(moves);

        SolverConfig config = new SolverConfig();
        config.setSolutionClass(JobShopSolution.class);
        config.setEntityClassList(List.of(Schedule.class));
        config.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        config.setPhaseConfigList(List.<PhaseConfig> of(phase));
        return config;
    }
}
