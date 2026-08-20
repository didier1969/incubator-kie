package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Le départ du banc — REQ-KKI-032.
 *
 * <p>
 * Le runner triait la séquence par date due AVANT toute mesure, sans que ce soit un choix : le
 * commentaire justifiait le tri par « une référence honnête », alors que REQ-KKI-030 a mesuré que
 * ce même tri rend le plan plusieurs fois plus CHER que l'ordre de génération. Une réduction se
 * juge contre son départ ; un départ subi rend le pourcentage vrai et sa lecture fausse.
 */
class StartReferenceTest {

    @BeforeEach
    void resetDomainParameters() {
        FullDataGenerator.reset();
    }

    @Test
    void theEarliestDueDateStartOrdersTheSequenceByDueDate() {
        JobShopSolution problem = FullDataGenerator.generate(400, 3L);

        FullRunner.applyStart(problem, FullRunner.Start.EDD);

        List<Order> sequence = problem.getScheduleList().get(0).getOrderSequence();
        for (int index = 1; index < sequence.size(); index++) {
            assertTrue(sequence.get(index - 1).getDueEpochSec() <= sequence.get(index)
                    .getDueEpochSec(),
                    "la position " + index + " casse l'ordre « plus urgent d'abord »");
        }
    }

    @Test
    void theGenerationStartTouchesNothing() {
        // Le piège serait un GEN qui « ne trie pas » mais reconstruit quand même la séquence :
        // on mesurerait alors un troisième ordre, sans que rien ne le dise.
        JobShopSolution problem = FullDataGenerator.generate(400, 3L);
        List<Order> before = List.copyOf(problem.getScheduleList().get(0).getOrderSequence());

        FullRunner.applyStart(problem, FullRunner.Start.GEN);

        assertEquals(before, problem.getScheduleList().get(0).getOrderSequence(),
                "GEN doit rendre le carnet exactement dans l'ordre où le générateur l'a produit");
    }

    @Test
    void theTwoStartsAreTwoDifferentPlansAndTheirCostsAreComparable() {
        // Les deux coûts sont pris sur la MÊME instance et la même graine : c'est la seule
        // manière dont l'écart mesure l'ordre et non le tirage.
        JobShopSolution problem = FullDataGenerator.generate(600, 5L);
        FullScoreCalculator oracle = new FullScoreCalculator();

        oracle.resetWorkingSolution(problem);
        long generationCost = -oracle.fullSweepScore().getSoftScore();
        FullRunner.applyStart(problem, FullRunner.Start.EDD);
        oracle.resetWorkingSolution(problem);
        long earliestDueDateCost = -oracle.fullSweepScore().getSoftScore();

        assertTrue(generationCost > 0L, "un carnet sans coût ne mesure rien");
        assertTrue(earliestDueDateCost != generationCost,
                "les deux départs rendent le même coût : le tri n'a pas eu lieu, ou il est neutre"
                        + " — dans les deux cas la comparaison de REQ-KKI-030 ne veut plus rien"
                        + " dire");
    }
}
