package kki.bench.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * POJO et FFM doivent produire le meme resultat d'agregat sur un jeu de
 * donnees identique — pas seulement "ca ne plante pas" (REQ-KKI-003).
 */
class LayoutEquivalenceTest {

    @Test
    void pojoAndFfmAgreeOnAvailableCount() {
        int n = 5_000;
        long seed = 7L;

        CalendarSlot[] pojo = new CalendarSlot[n];
        Random r1 = new Random(seed);
        for (int i = 0; i < n; i++) {
            boolean available = r1.nextInt(3) != 0;
            pojo[i] = new CalendarSlot(i % 1000, 1_700_000_000L + i * 60L, 1_700_003_600L + i * 60L, available);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ffm = CalendarSlotLayout.allocate(arena, n);
            Random r2 = new Random(seed);
            for (int i = 0; i < n; i++) {
                boolean available = r2.nextInt(3) != 0;
                CalendarSlotLayout.set(ffm, i, i % 1000, 1_700_000_000L + i * 60L, 1_700_003_600L + i * 60L, available);
            }

            long pojoCount = LayoutBenchmark.countAvailablePojo(pojo);
            long ffmCount = LayoutBenchmark.countAvailableFfm(ffm, n);

            assertEquals(pojoCount, ffmCount);
            assertTrue(pojoCount > 0 && pojoCount < n, "sanity: le jeu de donnees melange dispo/non-dispo");
        }
    }

    @Test
    void ffmEntryIsExactlyThirtyTwoBytes() {
        assertEquals(32L, CalendarSlotLayout.ENTRY_BYTES);
    }
}
