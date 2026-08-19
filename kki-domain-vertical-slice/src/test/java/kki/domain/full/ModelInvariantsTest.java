package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Un modèle se vérifie par ce qu'il INTERDIT, pas par ce qu'il fait gagner.
 *
 * <p>
 * Ces invariants sont la contrepartie de la correction de méthode « on crée le modèle, pas
 * l'optimisation » : ils ne regardent aucun coût, aucun pourcentage, aucune réduction. Ils
 * regardent si les contraintes de PIL-KKI-004 sont réellement portées ou seulement déclarées.
 * Chacun attrape une manière précise dont une contrainte peut devenir décorative.
 */
class ModelInvariantsTest {

    private static final int ORDERS = 500;

    @Test
    void noSetupRunsOutsideItsOwnSetterCalendar() {
        // Le piège : garder un calendrier dans le modèle mais dater sans le consulter. Le coût
        // continuerait d'être calculé, le calendrier ne contraindrait plus rien.
        Fixture fixture = Fixture.build();
        for (Operation op : fixture.solution.getOperationList()) {
            int opId = (int) op.getId();
            long start = fixture.calculator.setupStartOf(opId);
            long end = fixture.calculator.setupEndOf(opId);
            if (end <= start) {
                continue; // mise en train nulle
            }
            WorkCalendar calendar = op.getSetter().getCalendar();
            long worked = calendar.workedSecondsBefore(end) - calendar.workedSecondsBefore(start);
            assertTrue(worked > 0L,
                    "la mise en train de " + op + " s'étale de " + start + " à " + end
                            + " sans que " + op.getSetter() + " soit ouvert une seule seconde");
        }
    }

    @Test
    void noSetterDoesTwoSetupsAtOnce() {
        // Le modèle précédent autorisait un nombre ILLIMITÉ de mises en train simultanées, ce
        // qui revenait à supposer un metteur par machine.
        Fixture fixture = Fixture.build();
        List<List<Operation>> bySetter = new ArrayList<>();
        for (int i = 0; i < fixture.solution.getSetterList().size(); i++) {
            bySetter.add(new ArrayList<>());
        }
        for (Operation op : fixture.solution.getOperationList()) {
            bySetter.get((int) op.getSetter().getId()).add(op);
        }
        for (List<Operation> queue : bySetter) {
            queue.sort(Comparator.comparingLong(op -> fixture.calculator.setupStartOf((int) op.getId())));
            for (int i = 1; i < queue.size(); i++) {
                long previousEnd = fixture.calculator.setupEndOf((int) queue.get(i - 1).getId());
                long start = fixture.calculator.setupStartOf((int) queue.get(i).getId());
                assertTrue(start >= previousEnd,
                        "chevauchement sur " + queue.get(i).getSetter() + " : "
                                + queue.get(i - 1) + " finit à " + previousEnd + " et "
                                + queue.get(i) + " commence à " + start);
            }
        }
    }

    @Test
    void noMachineRunsTwoOperationsAtOnce() {
        Fixture fixture = Fixture.build();
        List<List<Operation>> byMachine = new ArrayList<>();
        for (int i = 0; i < fixture.solution.getMachineList().size(); i++) {
            byMachine.add(new ArrayList<>());
        }
        for (Operation op : fixture.solution.getOperationList()) {
            byMachine.get((int) op.getMachineId()).add(op);
        }
        for (List<Operation> queue : byMachine) {
            queue.sort(Comparator.comparingLong(op -> fixture.calculator.startOf((int) op.getId())));
            for (int i = 1; i < queue.size(); i++) {
                long previousEnd = fixture.calculator.endOf((int) queue.get(i - 1).getId());
                long start = fixture.calculator.startOf((int) queue.get(i).getId());
                assertTrue(start >= previousEnd,
                        "chevauchement machine entre " + queue.get(i - 1) + " et " + queue.get(i));
            }
        }
    }

    @Test
    void noMachiningHappensWhileTheMachineIsClosedOrUnderMaintenance() {
        // Attrape le cas où le calendrier machine ne servirait qu'à décorer : une opération
        // entièrement contenue dans une fenêtre de maintenance passerait inaperçue autrement.
        Fixture fixture = Fixture.build();
        int checked = 0;
        for (Operation op : fixture.solution.getOperationList()) {
            int opId = (int) op.getId();
            WorkCalendar calendar =
                    fixture.solution.getMachineList().get((int) op.getMachineId()).getCalendar();
            long open = calendar.workedSecondsBefore(fixture.calculator.endOf(opId))
                    - calendar.workedSecondsBefore(fixture.calculator.startOf(opId));
            assertTrue(open >= op.getDurationSeconds(),
                    op + " occupe la machine de " + fixture.calculator.startOf(opId) + " à "
                            + fixture.calculator.endOf(opId) + " alors qu'elle n'est ouverte que "
                            + open + " s là-dedans, pour une durée de " + op.getDurationSeconds());
            checked++;
        }
        assertTrue(checked > 1000, "trop peu d'opérations vérifiées : " + checked);
    }

    @Test
    void noToolingIsBorrowedTwiceAtOnce() {
        // Le pool est FINI (CPT-KKI-006) : deux mises en train qui exigent le même exemplaire ne
        // peuvent pas se chevaucher. Attention, cet invariant SEUL est faible — un emprunt de
        // durée nulle ne chevauche jamais rien. C'est le test suivant qui le rend concluant.
        Fixture fixture = Fixture.build();
        List<List<Operation>> byTooling = new ArrayList<>();
        for (int i = 0; i < fixture.solution.getToolingList().size(); i++) {
            byTooling.add(new ArrayList<>());
        }
        for (Operation op : fixture.solution.getOperationList()) {
            if (op.getTooling() != null) {
                byTooling.get((int) op.getTooling().getId()).add(op);
            }
        }
        for (List<Operation> queue : byTooling) {
            queue.sort(Comparator.comparingLong(op -> fixture.calculator.setupStartOf((int) op.getId())));
            for (int i = 1; i < queue.size(); i++) {
                long previousEnd = fixture.calculator.setupEndOf((int) queue.get(i - 1).getId());
                long start = fixture.calculator.setupStartOf((int) queue.get(i).getId());
                assertTrue(start >= previousEnd,
                        "montage emprunté deux fois à la fois : " + queue.get(i - 1) + " le rend à "
                                + previousEnd + " et " + queue.get(i) + " le prend à " + start);
            }
        }
    }

    @Test
    void theToolingPoolActuallyBindsAndIsNotDecoration() {
        // LA mesure qui sépare un mécanisme d'un décor : combien de mises en train sont retenues
        // par l'outillage ALORS QUE la machine et le metteur étaient tous deux libres. Si ce
        // compte est nul, la quatrième famille de prédécesseurs est indiscernable de son absence
        // et le test différentiel ne peut rien en dire.
        Fixture fixture = Fixture.build();
        FullScoreCalculator.ColdSweep sweep = fixture.calculator.coldSweep();
        assertTrue(sweep.toolingBorrowing() > 0L, "aucune mise en train n'emprunte : pool mort");
        assertTrue(sweep.toolingBound() > 0L,
                "le pool ne retient JAMAIS rien : la contrainte est décorative. Emprunts "
                        + sweep.toolingBorrowing() + ", liaisons " + sweep.toolingBound());
        System.out.printf("tooling_pool borrowing=%d bound=%d binding_rate=%.1f%%%n",
                sweep.toolingBorrowing(), sweep.toolingBound(),
                100.0 * sweep.toolingBound() / Math.max(1L, sweep.toolingBorrowing()));
    }

    @Test
    void theInstanceActuallyCarriesMaintenanceAndNonContinuousMachines() {
        // Un invariant sur un modèle qui ne contient pas le cas ne prouve rien : on vérifie ici
        // que l'instance exerce bien les deux mécanismes.
        Fixture fixture = Fixture.build();
        long closedAtSomePoint = fixture.solution.getMachineList().stream()
                .filter(machine -> !machine.getCalendar().isOpenAt(FullDataGenerator.ORIGIN_EPOCH
                        - FullDataGenerator.ORIGIN_EPOCH + 3L * 3600L))
                .count();
        assertTrue(closedAtSomePoint > 100,
                "des machines doivent être fermées la nuit, vues " + closedAtSomePoint);

        long withMaintenance = fixture.solution.getMachineList().stream()
                .filter(machine -> machine.getCalendar().isBlackedOutAt(hoursIntoHorizon(machine)))
                .count();
        assertTrue(withMaintenance > 0, "aucune fenêtre de maintenance dans l'instance");
    }

    /** Cherche un instant réellement en maintenance pour cette machine, sinon renvoie -1. */
    private static long hoursIntoHorizon(Machine machine) {
        for (long t = 0; t < 6L * 30 * 24 * 3600; t += 6L * 3600L) {
            if (machine.getCalendar().isBlackedOutAt(t)) {
                return t;
            }
        }
        return -1L;
    }

    private record Fixture(JobShopSolution solution, FullScoreCalculator calculator) {
        static Fixture build() {
            JobShopSolution solution = FullDataGenerator.generate(ORDERS, 71L);
            FullScoreCalculator calculator = new FullScoreCalculator();
            calculator.resetWorkingSolution(solution);
            return new Fixture(solution, calculator);
        }
    }
}
