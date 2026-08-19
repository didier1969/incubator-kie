package kki.domain.solver;

import java.util.ArrayList;
import java.util.List;

import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * CPT-KKI-012 · V3 — falsification de H1 (la marge est-elle abondante ?) et H2 (la criticité
 * est-elle concentrée ?).
 *
 * <p>
 * Mesure PURE : aucune recherche, aucun solveur, aucun budget de temps. On génère un plan de
 * départ naïf et on en lit la structure. Le résultat ne dépend donc ni de la charge machine
 * ni du hasard d'exécution — inutile d'établir un plancher de bruit, il n'y en a pas.
 *
 * <p>
 * Le plan de départ naïf est le point de mesure honnête : c'est celui sur lequel une
 * recherche locale démarrerait. Une marge mesurée sur un plan déjà optimisé dirait ce qu'il
 * reste à la fin, pas ce qui est disponible au début.
 *
 * <pre>
 *   java -cp ... kki.domain.solver.SlackAnalysisRunner [orderCount...]
 *   défaut : 3000 5000
 * </pre>
 */
public final class SlackAnalysisRunner {

    private static final int MACHINE_COUNT = 1000;

    private SlackAnalysisRunner() {
    }

    public static void main(String[] args) {
        // arg[0] = nombre d'ordres (defaut 5000). arg[1] = nombre de machines (defaut 1000).
        // Le nombre de machines est parametrable parce que c'est LUI qui fixe le taux
        // d'occupation, et donc la validite de toute mesure de marge : sur un atelier vide,
        // une marge abondante ne mesure que la capacite excedentaire.
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int machineCount = args.length > 1 ? Integer.parseInt(args[1]) : MACHINE_COUNT;
        for (int repetition = 0; repetition < 1; repetition++) {
            VerticalSliceSolution generated =
                    SyntheticDataGenerator.generate(orderCount, machineCount, 42L);
            Schedule schedule = new Schedule();
            schedule.setOrderSequence(new ArrayList<>(generated.getOrderList()));
            VerticalSliceSolution solution = new VerticalSliceSolution(generated.getOrderList(),
                    generated.getOperationList(), generated.getMachineList(), List.of(schedule));
            System.out.print(new SlackReporter(solution)
                    .report("naive_N" + orderCount + "_M" + machineCount));
        }
    }
}
