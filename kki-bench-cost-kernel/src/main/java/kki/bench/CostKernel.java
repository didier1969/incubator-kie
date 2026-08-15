package kki.bench;

/**
 * REQ-KKI-002 noyau de scoring batch : formule de cout retard/avance
 * quadratique de CPT-KKI-009, sur tableaux primitifs (SoA) — pas de graphe
 * d'objets, pour rester comparable a un futur portage TornadoVM.
 *
 * cost(h) = priorityWeight * k * h^2   si h >= 0 (retard, pondere)
 *         = (k / 10) * h^2             si h <  0 (avance, jamais pondere)
 */
public final class CostKernel {

    private CostKernel() {
    }

    public static float cost(float priorityWeight, float k, float hours) {
        float h2 = hours * hours;
        return hours >= 0 ? priorityWeight * k * h2 : (k / 10f) * h2;
    }

    /** Batch sequentiel naif : une passe sur les 3 tableaux d'entree, une sortie. */
    public static void scoreBatch(float[] priorityWeight, float k, float[] hours, float[] outCosts) {
        int n = hours.length;
        for (int i = 0; i < n; i++) {
            outCosts[i] = cost(priorityWeight[i], k, hours[i]);
        }
    }
}
