package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.constraint.ConstraintMatch;
import org.optaplanner.core.api.score.constraint.ConstraintMatchTotal;

/**
 * REQ-KKI-053 — la décomposition du coût est-elle EXACTE, ou seulement plausible ?
 *
 * <p>
 * {@code cost_breakdown} publie cinq termes souples et un dur à chaque run depuis des semaines.
 * Rien ne vérifiait que leur somme fasse le score. Maintenant que le moteur les expose sous
 * {@code ConstraintMatchTotal}, l'invariant devient testable — et il l'est ici sur DEUX niveaux :
 * l'agrégat, et l'imputation ordre par ordre qui le compose.
 */
class ConstraintBreakdownTest {

    private FullScoreCalculator calculator;
    private JobShopSolution problem;

    @BeforeEach
    void buildSmallInstance() {
        FullDataGenerator.reset();
        problem = FullDataGenerator.generate(120, 42L);
        calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(problem);
    }

    /** Si la somme des contraintes n'est pas le score, la décomposition ment. */
    @Test
    void theConstraintTotalsSumToTheScore() {
        Collection<ConstraintMatchTotal<HardSoftLongScore>> totals =
                calculator.getConstraintMatchTotals();

        long soft = 0L;
        long hard = 0L;
        for (ConstraintMatchTotal<HardSoftLongScore> total : totals) {
            soft += total.getScore().getSoftScore();
            hard += total.getScore().getHardScore();
        }

        HardSoftLongScore expected = calculator.fullSweepScore();
        assertEquals(expected.getSoftScore(), soft,
                "la somme des termes souples doit être le score souple");
        assertEquals(expected.getHardScore(), hard,
                "la somme des termes durs doit être le score dur");
    }

    /**
     * L'invariant qui compte le plus : l'imputation par ordre RECOMPOSE l'agrégat.
     *
     * <p>
     * Un balayage qui totaliserait juste tout en imputant faux passerait le test précédent. Ici
     * chaque terme est vérifié contre la somme de ses parts, ce qui interdit une imputation
     * approchée.
     */
    @Test
    void everyPerOrderAttributionRecomposesItsAggregate() {
        FullScoreCalculator.Attribution attribution =
                FullScoreCalculator.Attribution.of(problem.getOrderList().size() + 1);
        FullScoreCalculator.ColdSweep sweep = calculator.coldSweep(attribution);

        assertEquals(sweep.setter(), sum(attribution.setter()), "metteur");
        assertEquals(sweep.machineIdle(), sum(attribution.machineIdle()), "machine à l'arrêt");
        assertEquals(sweep.tardiness(), sum(attribution.tardiness()), "retard");
        assertEquals(sweep.earliness(), sum(attribution.earliness()), "avance");
        assertEquals(sweep.softFreeze(), sum(attribution.softFreeze()), "écart au plan publié");
        assertEquals(sweep.hard(), sum(attribution.hard()), "violations dures");
    }

    /**
     * Le balayage sans imputation doit rendre exactement le même score qu'avec.
     *
     * <p>
     * {@code fullSweepScore()} est appelé à CHAQUE mouvement sous FULL_ASSERT et passe
     * {@code null} pour ne rien allouer. Si les deux chemins divergeaient, le mode d'assertion
     * du moteur vérifierait autre chose que ce que le banc mesure.
     */
    @Test
    void theCheapPathAndTheAttributedPathAgree() {
        FullScoreCalculator.ColdSweep plain = calculator.coldSweep();
        FullScoreCalculator.ColdSweep attributed = calculator.coldSweep(
                FullScoreCalculator.Attribution.of(problem.getOrderList().size() + 1));
        assertEquals(plain.score(), attributed.score(),
                "le chemin sans imputation et le chemin imputé doivent donner le même score");
    }

    /** Une imputation utile désigne quelqu'un : au moins un ordre porte du retard. */
    @Test
    void theAttributionActuallyNamesOrders() {
        Collection<ConstraintMatchTotal<HardSoftLongScore>> totals =
                calculator.getConstraintMatchTotals();
        long named = totals.stream()
                .flatMap(total -> total.getConstraintMatchSet().stream())
                .map(ConstraintMatch::getJustificationList)
                .filter(justifications -> !justifications.isEmpty())
                .count();
        assertTrue(named > 0,
                "sans justification par ordre, l'indictment ne désigne personne et n'explique rien");
    }

    private static long sum(long[] values) {
        long total = 0L;
        for (long value : values) {
            total += value;
        }
        return total;
    }
}
