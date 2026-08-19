package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 *
 * <p>
 * Le différentiel mixte couvre désormais les QUATRE familles de prédécesseurs — chaîne, machine,
 * metteur, outillage. Une famille absente du tirage est une famille sans filet.
 */
class MoveSetTest {

    @org.junit.jupiter.api.BeforeEach
    void resetDomainParameters() {
        // Les dimensions du domaine sont des statiques mutables partagés par toute la JVM de
        // test. Repartir du référentiel avant CHAQUE test évite qu'un montage paramétré ayant
        // levé avant sa restauration ne fasse mesurer un autre modèle à ceux qui le suivent.
        FullDataGenerator.reset();
    }

    @Test
    void incrementalScoreSurvivesMixedSwapsAndMachineChanges() {
        JobShopSolution solution = FullDataGenerator.generate(150, 23L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        List<Order> sequence = solution.getScheduleList().get(0).getOrderSequence();
        List<Operation> operations = solution.getOperationList();
        Random random = new Random(29L);

        for (int move = 0; move < 160; move++) {
            int kind = random.nextInt(4);
            if (kind == 0) {
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
            } else if (kind == 1) {
                Operation op = operations.get(random.nextInt(operations.size()));
                List<Machine> candidates = op.getCompatibleMachines();
                calculator.reassignMachine(op, candidates.get(random.nextInt(candidates.size())));
            } else if (kind == 2) {
                // Swap sur outillage partagé — la QUATRIÈME famille de prédécesseurs. Sans cette
                // branche, la file du pool ne serait exercée que par les échanges X, qui la
                // réordonnent sans jamais en changer la composition.
                Operation op = operations.get(random.nextInt(operations.size()));
                List<Tooling> pool = op.getCompatibleToolings();
                if (pool.isEmpty()) {
                    continue;
                }
                calculator.reassignTooling(op, pool.get(random.nextInt(pool.size())));
            } else {
                // Réaffectation de metteur : la troisième famille de prédécesseurs. Un
                // différentiel qui ne ferait que des échanges X et des changements de machine
                // laisserait la file du metteur sans filet.
                Operation op = operations.get(random.nextInt(operations.size()));
                List<Setter> competent = solution.getSetterList().stream()
                        .filter(s -> s.canSetUp(op.getMachine()))
                        .toList();
                calculator.reassignSetter(op, competent.get(random.nextInt(competent.size())));
            }
            assertEquals(calculator.fullSweepScore(), calculator.calculateScore(),
                    "divergence au mouvement " + move);
        }
    }

    @Test
    void reassigningToAnIncompetentSetterIsRefused() {
        // La compétence n'est pas une préférence : un metteur qui ne sait pas régler cette
        // machine ne doit pas pouvoir être affecté, même par un appelant qui insiste.
        JobShopSolution solution = FullDataGenerator.generate(120, 61L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);
        Operation op = solution.getOperationList().get(0);
        Setter incompetent = solution.getSetterList().stream()
                .filter(s -> !s.canSetUp(op.getMachine()))
                .findFirst()
                .orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> calculator.reassignSetter(op, incompetent),
                "un metteur sans la compétence doit être refusé, pas facturé");
    }

    @Test
    void everyOperationHasACompetentSetter() {
        JobShopSolution solution = FullDataGenerator.generate(400, 67L);
        for (Operation op : solution.getOperationList()) {
            assertTrue(op.getSetter().canSetUp(op.getMachine()),
                    op + " est confiée à " + op.getSetter() + ", qui ne sait pas régler "
                            + op.getMachine());
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
    void hardFrozenOrdersAreNeverProposedForMovement() {
        // CPT-KKI-004 : « @PlanningPin, JAMAIS déplaçable ». Avant l'audit REQ-KKI-015 le verrou
        // dur était une PÉNALITÉ : le solveur pouvait acheter le déplacement d'un ordre déjà
        // lancé. Une contrainte qui s'achète n'est pas une contrainte.
        JobShopSolution solution = FullDataGenerator.generate(600, 47L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        Random random = new Random(53L);
        int inspected = 0;
        for (int attempt = 0; attempt < 600; attempt++) {
            Order[] pair = calculator.sampleTightAdjacentPair(random);
            if (pair == null) {
                continue;
            }
            inspected++;
            assertTrue(pair[0].getFreezeLevel() != Order.FreezeLevel.HARD
                            && pair[1].getFreezeLevel() != Order.FreezeLevel.HARD,
                    "le sélecteur a proposé de déplacer un ordre à verrou dur");
        }
        assertTrue(inspected > 50, "il faut assez de paires inspectées pour que le test morde : " + inspected);

        // Et le mouvement lui-même refuse, même construit à la main : le filtre du sélecteur ne
        // doit pas être la seule ligne de défense.
        Schedule schedule = solution.getScheduleList().get(0);
        List<Order> sequence = schedule.getOrderSequence();
        int hardIndex = -1;
        int freeIndex = -1;
        for (int i = 0; i < sequence.size(); i++) {
            if (hardIndex < 0 && sequence.get(i).getFreezeLevel() == Order.FreezeLevel.HARD) {
                hardIndex = i;
            }
            if (freeIndex < 0 && sequence.get(i).getFreezeLevel() == Order.FreezeLevel.FREE) {
                freeIndex = i;
            }
        }
        assertTrue(hardIndex >= 0 && freeIndex >= 0, "l'instance doit fournir les deux paliers");
        assertFalse(new CriticalPairSwapMove(schedule, hardIndex, freeIndex).isMoveDoable(null),
                "un échange touchant un ordre à verrou dur doit être refusé, pas facturé");
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
