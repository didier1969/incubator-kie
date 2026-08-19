package kki.domain.solver;

import java.util.ArrayList;
import java.util.List;

import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * CPT-KKI-012 · V3 — falsification de H1 (la marge est-elle abondante ?) et H2 (la criticité
 * est-elle concentrée ?), sur les deux mécanismes que l'opérateur a désignés comme absents du
 * modèle : durées réelles et compatibilité machine (REQ-KKI-010).
 *
 * <p>
 * Mesure PURE : aucune recherche, aucun solveur, aucun budget de temps. On génère un plan de
 * départ naïf et on en lit la structure. Le résultat ne dépend ni de la charge machine ni du
 * hasard d'exécution — il n'y a pas de plancher de bruit à établir.
 *
 * <p>
 * Le départ naïf est le point de mesure honnête : c'est celui d'où une recherche locale
 * partirait. Une marge lue sur un plan déjà optimisé dirait ce qu'il reste à la fin, pas ce
 * qui est disponible au début.
 *
 * <pre>
 *   sans argument            → balaye la matrice (durée × taille de classe × asymétrie)
 *   N M échelle classe skew  → un point unique
 * </pre>
 */
public final class SlackAnalysisRunner {

    private static final int DEFAULT_ORDER_COUNT = 5000;
    private static final int DEFAULT_MACHINE_COUNT = 1000;

    /**
     * Échelles de durée balayées. ×1 = 30 min–2 h (valeur historique, atelier à 0,65 %) ;
     * ×24 ≈ une demi-journée à 2 jours ; ×80 ≈ 1,7 à 6,7 jours, seule échelle qui remplit
     * 1000 machines sur 6 mois. Hypothèses à remplacer par un extrait de production.
     */
    private static final double[] DURATION_SCALES = { 1.0, 24.0, 80.0 };

    /**
     * Nombre de machines éligibles par opération. 1000 = tirage uniforme, le cas historique
     * qui garantissait l'uniformité de la criticité par construction. 10 et 3 = confinement
     * réaliste évoqué par l'opérateur.
     */
    private static final int[] CLASS_SIZES = { 1000, 10, 3 };

    /** 0 = classes également demandées ; 1 = demande en 1/rang, donc goulots structurels. */
    private static final double[] DEMAND_SKEWS = { 0.0, 1.0 };

    private SlackAnalysisRunner() {
    }

    public static void main(String[] args) {
        if (args.length >= 5) {
            analyse(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
                    Double.parseDouble(args[2]), Integer.parseInt(args[3]), Double.parseDouble(args[4]));
            return;
        }
        for (double durationScale : DURATION_SCALES) {
            for (int classSize : CLASS_SIZES) {
                for (double demandSkew : DEMAND_SKEWS) {
                    // Sans confinement, l'asymétrie de demande n'a pas de sens : une seule classe.
                    if (classSize == DEFAULT_MACHINE_COUNT && demandSkew != 0.0) {
                        continue;
                    }
                    analyse(DEFAULT_ORDER_COUNT, DEFAULT_MACHINE_COUNT, durationScale, classSize, demandSkew);
                }
            }
        }
    }

    private static void analyse(int orderCount, int machineCount, double durationScale, int classSize,
            double demandSkew) {
        VerticalSliceSolution generated = SyntheticDataGenerator.generate(
                orderCount, machineCount, 42L, durationScale, classSize, demandSkew);
        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>(generated.getOrderList()));
        VerticalSliceSolution solution = new VerticalSliceSolution(generated.getOrderList(),
                generated.getOperationList(), generated.getMachineList(), List.of(schedule));
        String label = String.format("N%d_M%d_dur%.0fx_cls%d_skew%.0f",
                orderCount, machineCount, durationScale, classSize, demandSkew);
        System.out.print(new SlackReporter(solution).report(label));
        System.out.println();
    }
}
