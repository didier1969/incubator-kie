package kki.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifie la formule contre CPT-KKI-009, calcul independant a la main.
 */
class CostKernelTest {

    @Test
    void tardinessIsWeightedByPriority() {
        // priority=3, k=5, h=4 (retard) -> 3 * 5 * 4^2 = 240
        assertEquals(240f, CostKernel.cost(3f, 5f, 4f), 1e-6f);
    }

    @Test
    void earlinessIgnoresPriorityAndIsTenTimesSmaller() {
        // k=5, h=-4 (avance) -> (5/10) * 4^2 = 8, priorite ignoree
        assertEquals(8f, CostKernel.cost(1f, 5f, -4f), 1e-6f);
        assertEquals(8f, CostKernel.cost(5f, 5f, -4f), 1e-6f);
    }

    @Test
    void zeroHoursIsZeroCost() {
        assertEquals(0f, CostKernel.cost(5f, 5f, 0f), 1e-6f);
    }

    @Test
    void scoreBatchMatchesElementwiseCost() {
        float[] priority = { 1f, 3f, 5f };
        float[] hours = { -4f, 4f, -2f };
        float[] out = new float[3];
        CostKernel.scoreBatch(priority, 5f, hours, out);
        assertEquals(CostKernel.cost(1f, 5f, -4f), out[0], 1e-6f);
        assertEquals(CostKernel.cost(3f, 5f, 4f), out[1], 1e-6f);
        assertEquals(CostKernel.cost(5f, 5f, -2f), out[2], 1e-6f);
    }
}
