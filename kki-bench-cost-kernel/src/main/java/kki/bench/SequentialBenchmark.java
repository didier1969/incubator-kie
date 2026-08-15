package kki.bench;

import java.util.Random;

/**
 * REQ-KKI-002 : chemin "Java sequentiel naif" du benchmark a 3 voies.
 * Les chemins TornadoVM CPU/GPU sont bloques dans cet environnement
 * (detection de plateforme OpenCL cassee sous ce WSL2, reproduit hors
 * Nix/devenv aussi — cf REQ-KKI-002 pour le diagnostic complet). Cette
 * baseline reste la seule mesure chiffree disponible pour l'instant.
 *
 * Mesure : N candidats croissant, 3 repetitions apres une phase de
 * chauffe JIT separee (pas de mesure sur du code non-JITe).
 */
public final class SequentialBenchmark {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASURED_ITERATIONS = 3;
    private static final int[] CANDIDATE_COUNTS = { 10_000, 100_000, 1_000_000, 10_000_000 };

    private SequentialBenchmark() {
    }

    public static void main(String[] args) {
        Random random = new Random(42);

        for (int n : CANDIDATE_COUNTS) {
            float[] priority = new float[n];
            float[] hours = new float[n];
            float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                priority[i] = 1 + random.nextInt(5);
                hours[i] = (random.nextFloat() - 0.5f) * 200f;
            }

            for (int w = 0; w < WARMUP_ITERATIONS; w++) {
                CostKernel.scoreBatch(priority, 5f, hours, out);
            }

            long totalNanos = 0;
            for (int m = 0; m < MEASURED_ITERATIONS; m++) {
                long start = System.nanoTime();
                CostKernel.scoreBatch(priority, 5f, hours, out);
                totalNanos += System.nanoTime() - start;
            }
            double avgMs = (totalNanos / (double) MEASURED_ITERATIONS) / 1_000_000.0;

            System.out.printf("sequential,N=%d,avg_ms=%.3f%n", n, avgMs);
        }
    }
}
