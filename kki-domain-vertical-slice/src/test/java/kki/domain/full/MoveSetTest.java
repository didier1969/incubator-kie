package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Les trois mouvements de CPT-KKI-012, chacun sur son angle de faiblesse.
 *
 * <p>
 * M2 introduit une seconde variable de décision : le risque est que la propagation oublie l'une
 * des deux ressources concernées. Seul un différentiel MÊLANT les deux types de mouvements le
 * détecte — un différentiel qui ne ferait que des échanges X passerait à côté.
 *
 * <p>
 * M3 est un sélecteur restrictif : son risque n'est pas de se tromper mais de ne rien émettre.
 * Un itérateur tari se lirait comme une convergence, c'est-à-dire comme un succès.
 */
class MoveSetTest {

    @Test
    void incrementalScoreSurvivesMixedSwapsAndMachineChanges() {
        JobShopSolution solution = FullDataGenerator.generate(150, 23L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        List<Order> sequence = solution.getScheduleList().get(0).getOrderSequence();
        List<Operation> operations = solution.getOperationList();
        Random random = new Random(29L);

        for (int move = 0; move < 120; move++) {
            if (random.nextBoolean()) {
                int a = random.nextInt(sequence.size());
                int b = random.nextInt(sequence.size());
                if (a == b) {
                    continue;
                }
                int from = Math.min(a, b);
                int to = Math.max(a, b) + 1;
                calculator.beforeListVariableChanged(null, "orderSequence", from, to);
                Collections.swap(sequence, a, b);
                calculator.afterListVariableChanged(null, "orderSequence", from, to);
            } else {
                Operation op = operations.get(random.nextInt(operations.size()));
                List<Machine> candidates = op.getCompatibleMachines();
                Machine target = candidates.get(random.nextInt(candidates.size()));
                calculator.beforeVariableChanged(op, "machine");
                op.setMachine(target);
                calculator.afterVariableChanged(op, "machine");
            }
            assertEquals(calculator.fullSweepScore(), calculator.calculateScore(),
                    "divergence au mouvement " + move);
        }
    }

    @Test
    void machineSubstitutionNeverBreaksAscendingCompatibility() {
        JobShopSolution solution = FullDataGenerator.generate(300, 31L);
        for (Operation op : solution.getOperationList()) {
            for (Machine candidate : op.getCompatibleMachines()) {
                assertTrue(candidate.canRun(op.getRequiredTechnology(), op.getRequiredLevel()),
                        "la plage de valeurs de " + op + " propose " + candidate
                                + ", incompatible : la substitution ne doit jamais pouvoir descendre"
                                + " sous le niveau requis");
            }
            assertTrue(op.getCompatibleMachines().contains(op.getMachine()),
                    "la machine assignée doit appartenir à sa propre plage de valeurs");
        }
    }

    @Test
    void ascendingCompatibilityOffersTheUpperLevelsAndNeverTheLowerOnes() {
        JobShopSolution solution = FullDataGenerator.generate(200, 37L);
        Operation lowLevel = solution.getOperationList().stream()
                .filter(op -> op.getRequiredLevel() == 0)
                .findFirst()
                .orElseThrow();
        // Niveau 0 : toute la technologie est éligible, soit 10 niveaux × 20 machines.
        assertEquals(200, lowLevel.getCompatibleMachines().size(),
                "une opération de niveau 0 doit pouvoir monter sur toute son échelle");
        assertTrue(lowLevel.getCompatibleMachines().stream()
                .allMatch(m -> m.getTechnology() == lowLevel.getRequiredTechnology()),
                "jamais une autre technologie");
    }

    @Test
    void guidedSelectorOnlyEmitsPairsAdjacentOnASharedResource() {
        JobShopSolution solution = FullDataGenerator.generate(600, 41L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        Random random = new Random(43L);
        int found = 0;
        for (int attempt = 0; attempt < 400; attempt++) {
            Order[] pair = calculator.sampleTightAdjacentPair(random);
            if (pair == null) {
                continue;
            }
            found++;
            assertNotNull(pair[0]);
            assertNotNull(pair[1]);
            assertTrue(pair[0] != pair[1], "une paire doit porter deux ordres distincts");
            assertTrue(shareAMachine(solution, pair[0], pair[1]),
                    "le sélecteur a proposé deux ordres sans ressource commune : l'échange serait"
                            + " un no-op exact, exactement ce qu'il doit écarter");
        }
        // Un sélecteur qui n'émet rien ferait passer une absence de candidat pour une convergence.
        assertTrue(found > 100,
                "à 600 ordres le sélecteur doit trouver des arcs tendus, trouvés : " + found);
    }

    private static boolean shareAMachine(JobShopSolution solution, Order left, Order right) {
        List<Operation> operations = solution.getOperationList();
        return operations.stream()
                .filter(op -> op.getOrder() == left)
                .anyMatch(leftOp -> operations.stream()
                        .filter(op -> op.getOrder() == right)
                        .anyMatch(rightOp -> rightOp.getMachineId() == leftOp.getMachineId()));
    }
}
