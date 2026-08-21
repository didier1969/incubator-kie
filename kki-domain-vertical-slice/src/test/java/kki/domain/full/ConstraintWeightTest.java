package kki.domain.full;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.test.impl.score.buildin.hardsoftlong.HardSoftLongScoreVerifier;

/**
 * REQ-KKI-053 — chaque terme de coût est asserté ISOLÉMENT, avec l'outil du moteur.
 *
 * <p>
 * <b>Ce que cette classe rend possible et qui ne l'était pas.</b> Les 1 593 lignes de
 * {@code FullScoreCalculator} n'avaient aucun test isolant une contrainte : on vérifiait le score
 * TOTAL. Quand {@code REQ-KKI-043} a changé la propagation, rien ne disait quel terme avait bougé
 * — et un terme qui monte pendant qu'un autre descend passe inaperçu dans un total.
 *
 * <p>
 * <b>Pourquoi cette voie et pas {@code ConstraintVerifier}.</b> Ce dernier exige un
 * {@code ConstraintProvider}, donc Constraint Streams : fermé tant que notre scoring est un
 * calculateur incrémental écrit à la main. {@code HardSoftLongScoreVerifier} passe par
 * {@code getConstraintMatchTotalMap()}, qui s'est ouvert avec
 * {@code ConstraintMatchAwareIncrementalScoreCalculator}.
 *
 * <p>
 * <b>Ce que ces tests N'affirment pas.</b> Aucun poids n'est épinglé à une constante : les valeurs
 * dépendent de l'instance, et figer un chiffre d'instance en dur serait la faute que
 * {@code VIS-KKI-001} interdit. Ils assertent des RELATIONS que le modèle de coût impose —
 * chaque terme est son propre total mesuré, et le régime dit lequel domine.
 */
class ConstraintWeightTest {

    private HardSoftLongScoreVerifier<JobShopSolution> verifier;
    private JobShopSolution problem;
    private FullScoreCalculator.ColdSweep sweep;

    @BeforeEach
    void buildSmallInstance() {
        FullDataGenerator.reset();
        problem = FullDataGenerator.generate(120, 42L);
        FullRunner.applyStart(problem, FullRunner.Start.GEN);
        verifier = new HardSoftLongScoreVerifier<>(
                SolverFactory.create(FullRunner.solverConfigOf(FullRunner.Variant.M5, 1L)));
        FullScoreCalculator oracle = new FullScoreCalculator();
        oracle.resetWorkingSolution(problem);
        sweep = oracle.coldSweep();
    }

    /**
     * Chaque terme pèse EXACTEMENT ce que le balayage à froid lui impute.
     *
     * <p>
     * L'oracle et le moteur empruntent deux chemins distincts pour arriver au même nombre : le
     * balayage complet d'un côté, la décomposition exposée par le contrat de l'autre. Les faire
     * coïncider terme par terme est le seul moyen de savoir que la décomposition publiée dans
     * {@code cost_breakdown} décrit bien ce que le solveur optimise.
     */
    @Test
    void everyCostTermWeighsExactlyWhatTheColdSweepAttributesToIt() {
        verifier.assertSoftWeight(FullScoreCalculator.Constraints.SETTER, -sweep.setter(), problem);
        verifier.assertSoftWeight(FullScoreCalculator.Constraints.MACHINE_IDLE, -sweep.machineIdle(), problem);
        verifier.assertSoftWeight(FullScoreCalculator.Constraints.TARDINESS, -sweep.tardiness(), problem);
        verifier.assertSoftWeight(FullScoreCalculator.Constraints.EARLINESS, -sweep.earliness(), problem);
        verifier.assertSoftWeight(FullScoreCalculator.Constraints.SOFT_FREEZE, -sweep.softFreeze(), problem);
    }

    @Test
    void theHardTermWeighsExactlyWhatTheColdSweepAttributesToIt() {
        verifier.assertHardWeight(FullScoreCalculator.Constraints.HARD, sweep.hard(), problem);
    }
}
