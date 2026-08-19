package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Le pool d'outillage de CPT-KKI-006, sur son angle de faiblesse propre.
 *
 * <p>
 * <b>Pourquoi un test à valeurs calculées à la main plutôt que le seul différentiel.</b> Le
 * différentiel compare la propagation incrémentale au balayage à froid. Or les deux ont dû être
 * modifiés pour porter l'outillage : s'ils l'ont été de la MÊME manière fausse, le différentiel
 * reste vert et ne prouve rien. Il faut donc au moins un point où l'attendu est calculé
 * indépendamment des deux — ici à la main, par la même discipline qui a gardé les tests du
 * calendrier metteur.
 *
 * <p>
 * <b>Et pourquoi un contrôle négatif.</b> Un emprunt de durée nulle ne chevauche jamais rien :
 * une implémentation inerte satisfait « aucun outillage emprunté deux fois à la fois » sans
 * contraindre quoi que ce soit. La preuve que la ressource EXISTE, c'est que la retirer change la
 * date — d'où la même instance montée deux fois, avec un exemplaire partagé puis deux
 * exemplaires distincts.
 */
class ToolingPoolTest {

    @org.junit.jupiter.api.BeforeEach
    void resetDomainParameters() {
        // Les dimensions du domaine sont des statiques mutables partagés par toute la JVM de
        // test. Repartir du référentiel avant CHAQUE test évite qu'un montage paramétré ayant
        // levé avant sa restauration ne fasse mesurer un autre modèle à ceux qui le suivent.
        FullDataGenerator.reset();
    }

    private static final long HOUR = 3600L;
    private static final long DAY = 86_400L;

    @Test
    void aSharedToolingDelaysTheSecondSetupByAKnownNumberOfSeconds() {
        // Deux ordres, une opération chacun, sur des machines DIFFÉRENTES et des metteurs
        // DIFFÉRENTS : ni la machine ni le metteur ne peut retenir le second. La seule ressource
        // commune est l'outillage — ce qui est mesuré ne peut donc venir que de lui.
        Fixture shared = Fixture.build(true);
        long setupA = shared.setupSecondsOf(0);

        // Calcul à la main, sans passer par WorkCalendar : le metteur ouvre lundi 08:00–16:00,
        // soit 8 h par jour. Pour une mise en train comprise entre 8 h et 16 h de travail, le
        // premier jour en absorbe 8 h et le reste tombe le mardi à partir de 08:00 :
        //     fin = 24 h + 8 h + (setup − 8 h) = 24 h + setup.
        assertTrue(setupA > 8 * HOUR && setupA < 16 * HOUR,
                "le montage du test suppose une mise en train de 8 h à 16 h, mesurée "
                        + setupA / 3600.0 + " h");
        long expectedEndA = DAY + setupA;
        assertEquals(expectedEndA, shared.calculator.setupEndOf(0),
                "la mise en train du premier ordre doit finir mardi 08:00 + le reliquat");

        // Le second ne peut pas commencer avant que l'exemplaire soit rendu, c'est-à-dire à la
        // FIN de la première mise en train — pas à la fin de son usinage.
        assertEquals(expectedEndA, shared.calculator.setupStartOf(1),
                "l'outillage partagé doit retenir la seconde mise en train exactement jusqu'à sa"
                        + " restitution");

        // CONTRÔLE NÉGATIF — deux exemplaires : plus rien ne lie, le second part à l'origine.
        Fixture free = Fixture.build(false);
        assertEquals(0L, free.calculator.setupStartOf(1),
                "sans exemplaire partagé, la seconde mise en train ne doit être retenue par rien");
        assertTrue(shared.calculator.setupStartOf(1) - free.calculator.setupStartOf(1) == expectedEndA,
                "le décalage mesuré doit être exactement la fenêtre d'emprunt");
    }

    @Test
    void reassigningToAToolingOfAnotherTypeIsRefused() {
        // Deux exemplaires du même type sont interchangeables ; deux types ne le sont pas. Sans
        // ce refus, le « swap sur outillage partagé » de CPT-KKI-010 pourrait monter n'importe
        // quel montage sur n'importe quelle mise en train.
        JobShopSolution solution = FullDataGenerator.generate(200, 83L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        Operation borrowing = solution.getOperationList().stream()
                .filter(op -> op.getRequiredToolingType() != Operation.NO_TOOLING)
                .findFirst()
                .orElseThrow(() -> new AssertionError("aucune opération n'emprunte : le pool est mort"));
        Tooling wrongType = solution.getToolingList().stream()
                .filter(tooling -> tooling.getType() != borrowing.getRequiredToolingType())
                .findFirst()
                .orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> calculator.reassignTooling(borrowing, wrongType),
                "un montage d'un autre type doit être refusé, pas facturé");

        Operation borrowingNothing = solution.getOperationList().stream()
                .filter(op -> op.getRequiredToolingType() == Operation.NO_TOOLING)
                .findFirst()
                .orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> calculator.reassignTooling(borrowingNothing, wrongType),
                "une mise en train qui n'emprunte rien ne peut pas se voir affecter un montage");
    }

    @Test
    void everyBorrowedToolingIsOfTheRequiredTypeAndInItsOwnValueRange() {
        JobShopSolution solution = FullDataGenerator.generate(400, 89L);
        int borrowing = 0;
        for (Operation op : solution.getOperationList()) {
            if (op.getRequiredToolingType() == Operation.NO_TOOLING) {
                assertTrue(op.getTooling() == null,
                        op + " n'exige aucun montage mais en détient un");
                assertTrue(op.getCompatibleToolings().isEmpty(), "plage de valeurs non vide");
                continue;
            }
            borrowing++;
            assertEquals(op.getRequiredToolingType(), op.getTooling().getType(),
                    op + " détient un montage d'un autre type");
            assertTrue(op.getCompatibleToolings().contains(op.getTooling()),
                    "le montage détenu doit appartenir à sa propre plage de valeurs");
        }
        // Sur ~1800 opérations et une part demandée de 40 %, un pool réellement exercé pèse
        // plusieurs centaines d'emprunts. Le seuil garde contre un pool devenu vide, pas contre
        // un écart de quelques points sur la part.
        assertTrue(borrowing > 500,
                "l'instance doit exercer réellement le pool, emprunts vus : " + borrowing);
    }

    /**
     * Instance minimale : 2 ordres × 1 opération, machines et metteurs distincts, et un pool qui
     * porte soit UN exemplaire pour les deux, soit un exemplaire chacun.
     */
    private record Fixture(JobShopSolution solution, FullScoreCalculator calculator,
            SetupMatrix matrix) {

        static Fixture build(boolean shareTheTooling) {
            SetupMatrix matrix = new SetupMatrix(20, 6, 101L);
            // Deux clés dont la mise en train à froid tombe dans la fenêtre 8 h–16 h, pour que
            // l'attendu se calcule d'une ligne.
            int keyA = firstKeyWithColdStartBetween(matrix, 8 * HOUR, 16 * HOUR, 0);
            int keyB = firstKeyWithColdStartBetween(matrix, 8 * HOUR, 16 * HOUR, keyA + 1);

            Machine machineA = new Machine(0, 0, 0, 10_000L, WorkCalendar.CONTINUOUS);
            Machine machineB = new Machine(1, 0, 0, 10_000L, WorkCalendar.CONTINUOUS);
            Setter setterA = new Setter(0, WorkCalendar.MONDAY_TO_WEDNESDAY_8H, new boolean[] { true });
            Setter setterB = new Setter(1, WorkCalendar.MONDAY_TO_WEDNESDAY_8H, new boolean[] { true });
            Tooling toolingA = new Tooling(0, 0);
            Tooling toolingB = shareTheTooling ? toolingA : new Tooling(1, 0);

            long due = 400L * DAY;
            Order orderA = new Order(0, 0, 1, due, Order.FreezeLevel.FREE, due);
            Order orderB = new Order(1, 1, 1, due, Order.FreezeLevel.FREE, due);
            Operation opA = new Operation(0, orderA, 0, HOUR, 0, 0, keyA, 0,
                    List.of(machineA, machineB), List.of(toolingA), machineA, setterA, toolingA);
            Operation opB = new Operation(1, orderB, 0, HOUR, 0, 0, keyB, 0,
                    List.of(machineA, machineB), List.of(toolingB), machineB, setterB, toolingB);
            orderA.setOperations(List.of(opA));
            orderB.setOperations(List.of(opB));

            Schedule schedule = new Schedule();
            schedule.setOrderSequence(new java.util.ArrayList<>(List.of(orderA, orderB)));
            List<Tooling> pool = shareTheTooling ? List.of(toolingA) : List.of(toolingA, toolingB);
            JobShopSolution solution = new JobShopSolution(List.of(orderA, orderB),
                    List.of(opA, opB), List.of(machineA, machineB), List.of(setterA, setterB),
                    pool, List.of(schedule), matrix, 0L);

            FullScoreCalculator calculator = new FullScoreCalculator();
            calculator.resetWorkingSolution(solution);
            return new Fixture(solution, calculator, matrix);
        }

        long setupSecondsOf(int opId) {
            return matrix.coldStartSeconds(solution.getOperationList().get(opId).getSetupKey());
        }

        private static int firstKeyWithColdStartBetween(SetupMatrix matrix, long low, long high,
                int from) {
            for (int key = from; key < 120; key++) {
                long cold = matrix.coldStartSeconds(key);
                if (cold > low && cold < high) {
                    return key;
                }
            }
            throw new AssertionError("aucune clé de mise en train dans la fenêtre voulue");
        }
    }
}
