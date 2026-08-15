package kki.bench.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;

/**
 * REQ-KKI-003 : empreinte memoire + debit d'acces, POJO vs FFM, sur un
 * calendrier machine/metteur synthetique (CPT-KKI-007). Meme methodologie
 * de chauffe/mesure que SequentialBenchmark (REQ-KKI-002).
 */
public final class LayoutBenchmark {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASURED_ITERATIONS = 5;
    private static final int[] ENTRY_COUNTS = { 10_000, 100_000, 1_000_000 };

    private LayoutBenchmark() {
    }

    public static void main(String[] args) {
        System.out.printf("pojo_bytes_per_entry=%d ffm_bytes_per_entry=%d%n",
                MemoryFootprint.pojoBytesPerEntry(), MemoryFootprint.ffmBytesPerEntry());

        Random random = new Random(42);
        for (int n : ENTRY_COUNTS) {
            CalendarSlot[] pojo = buildPojo(n, random);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment ffm = buildFfm(arena, n, new Random(42));

                long pojoTotal = MemoryFootprint.pojoBytesPerEntry() * (long) n;
                long ffmTotal = MemoryFootprint.ffmBytesPerEntry() * (long) n;
                System.out.printf("N=%d pojo_total_bytes=%d ffm_total_bytes=%d%n", n, pojoTotal, ffmTotal);

                long pojoAvgNanos = timeScan(() -> countAvailablePojo(pojo));
                long ffmAvgNanos = timeScan(() -> countAvailableFfm(ffm, n));

                System.out.printf("N=%d pojo_avg_ms=%.4f pojo_ips=%.0f%n",
                        n, pojoAvgNanos / 1_000_000.0, n / (pojoAvgNanos / 1_000_000_000.0));
                System.out.printf("N=%d ffm_avg_ms=%.4f ffm_ips=%.0f%n",
                        n, ffmAvgNanos / 1_000_000.0, n / (ffmAvgNanos / 1_000_000_000.0));
            }
        }
    }

    interface Scan {
        long run();
    }

    private static long timeScan(Scan scan) {
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            scan.run();
        }
        long total = 0;
        for (int m = 0; m < MEASURED_ITERATIONS; m++) {
            long start = System.nanoTime();
            scan.run();
            total += System.nanoTime() - start;
        }
        return total / MEASURED_ITERATIONS;
    }

    static long countAvailablePojo(CalendarSlot[] slots) {
        long count = 0;
        for (CalendarSlot slot : slots) {
            if (slot.available) {
                count++;
            }
        }
        return count;
    }

    static long countAvailableFfm(MemorySegment seg, int n) {
        long count = 0;
        for (int i = 0; i < n; i++) {
            if (CalendarSlotLayout.isAvailable(seg, i)) {
                count++;
            }
        }
        return count;
    }

    private static CalendarSlot[] buildPojo(int n, Random random) {
        CalendarSlot[] slots = new CalendarSlot[n];
        for (int i = 0; i < n; i++) {
            long machineId = i % 1000;
            long start = 1_700_000_000L + i * 60L;
            long end = start + 3600L;
            boolean available = random.nextInt(3) != 0; // ~2/3 disponibles
            slots[i] = new CalendarSlot(machineId, start, end, available);
        }
        return slots;
    }

    private static MemorySegment buildFfm(Arena arena, int n, Random random) {
        MemorySegment seg = CalendarSlotLayout.allocate(arena, n);
        for (int i = 0; i < n; i++) {
            long machineId = i % 1000;
            long start = 1_700_000_000L + i * 60L;
            long end = start + 3600L;
            boolean available = random.nextInt(3) != 0;
            CalendarSlotLayout.set(seg, i, machineId, start, end, available);
        }
        return seg;
    }
}
