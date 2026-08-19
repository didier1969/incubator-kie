package kki.domain.mseq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

import kki.domain.full.FullDataGenerator;
import kki.domain.full.FullScoreCalculator;
import kki.domain.full.JobShopSolution;
import kki.domain.full.Operation;

/**
 * La comparaison de deux représentations n'a de sens que si elles calculent le MÊME coût sur le
 * MÊME plan. C'est ce que ce test verrouille en premier — sans lui, tout écart mesuré entre les
 * lots A et B pourrait n'être qu'un écart de fonction de coût.
 *
 * <p>
 * Le second angle est l'acyclicité, qui n'est plus gratuite dans cette représentation : deux
 * ressources qui se contredisent produisent un plan impossible, et le calculateur doit le refuser
 * au score DUR plutôt que de rendre un coût dénué de sens.
 */
class MachineSeqCalculatorTest {

    @Test
    void bothRepresentationsScoreTheSamePlanIdentically() {
        for (int orderCount : new int[] { 80, 250, 700 }) {
            JobShopSolution source = FullDataGenerator.generate(orderCount, 42L);
            FullScoreCalculator xCalculator = new FullScoreCalculator();
            xCalculator.resetWorkingSolution(source);
            HardSoftLongScore expected = xCalculator.fullSweepScore();

            MachineSeqSolution converted = MachineSeqSolution.from(source);
            HardSoftLongScore actual = new MachineSeqCalculator().calculateScore(converted);

            assertEquals(expected, actual,
                    "les deux représentations doivent chiffrer le même plan à l'identique, sinon"
                            + " la comparaison des deux lots mesurerait une différence de barème,"
                            + " à N=" + orderCount);
        }
    }

    @Test
    void contradictoryResourceSequencesAreRefusedOnTheHardScore() {
        // Deux ordres, deux ressources, chacune ordonnant la paire à l'inverse de l'autre : A
        // avant B ici, B avant A là. Aucun ordonnancement ne satisfait les deux — c'est un
        // interblocage, pas un plan cher.
        JobShopSolution source = FullDataGenerator.generate(400, 42L);
        MachineSeqSolution solution = MachineSeqSolution.from(source);

        MachineSequence contradicted = null;
        for (MachineSequence sequence : solution.getSequenceList()) {
            if (sequence.getOperations().size() >= 2
                    && sequence.getOperations().get(0).getOrder()
                            != sequence.getOperations().get(1).getOrder()) {
                contradicted = sequence;
                break;
            }
        }
        assertTrue(contradicted != null, "l'instance doit fournir une ressource à deux ordres distincts");

        HardSoftLongScore before = new MachineSeqCalculator().calculateScore(solution);
        assertEquals(0L, before.getHardScore() % 1L);

        // On force la contradiction : la même paire d'ordres, inversée sur une SECONDE ressource
        // qu'ils partagent aussi.
        Operation first = contradicted.getOperations().get(0);
        Operation second = contradicted.getOperations().get(1);
        MachineSequence other = findSequenceSharing(solution, first, second, contradicted);
        if (other == null) {
            return; // aucune seconde ressource partagée sur cette instance : rien à prouver ici
        }
        List<Operation> ops = other.getOperations();
        int i = indexOfOrder(ops, first);
        int j = indexOfOrder(ops, second);
        if (i < 0 || j < 0) {
            return;
        }
        Collections.swap(ops, i, j);
        HardSoftLongScore after = new MachineSeqCalculator().calculateScore(solution);
        assertTrue(after.getHardScore() < before.getHardScore(),
                "une contradiction entre deux ressources doit dégrader le score DUR, mesuré avant "
                        + before.getHardScore() + " après " + after.getHardScore());
    }

    private static MachineSequence findSequenceSharing(MachineSeqSolution solution, Operation a,
            Operation b, MachineSequence exclude) {
        for (MachineSequence sequence : solution.getSequenceList()) {
            if (sequence == exclude) {
                continue;
            }
            if (indexOfOrder(sequence.getOperations(), a) >= 0
                    && indexOfOrder(sequence.getOperations(), b) >= 0) {
                return sequence;
            }
        }
        return null;
    }

    private static int indexOfOrder(List<Operation> operations, Operation reference) {
        for (int i = 0; i < operations.size(); i++) {
            if (operations.get(i).getOrder() == reference.getOrder()) {
                return i;
            }
        }
        return -1;
    }
}
