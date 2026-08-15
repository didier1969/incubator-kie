package kki.bench.ffm;

/**
 * Empreinte mémoire par entrée — analytique et documentée, pas une mesure
 * JVM-instrumentée (pas de java.lang.instrument.Instrumentation ni de
 * dépendance JOL pour un throwaway benchmark, GUI-PRO-025). Hypothèses
 * HotSpot standard : compressed oops actifs (heap &lt; 32Go, cas par défaut).
 */
public final class MemoryFootprint {

    private static final long OBJECT_HEADER_BYTES = 12; // mark word + klass pointer compresse
    private static final long COMPRESSED_OOP_REF_BYTES = 4; // reference dans CalendarSlot[]

    private MemoryFootprint() {
    }

    public static long pojoBytesPerEntry() {
        long fieldBytes = 3 * 8 + 1; // 3 long + 1 boolean
        long raw = OBJECT_HEADER_BYTES + fieldBytes;
        long padded = ((raw + 7) / 8) * 8; // alignement 8 octets HotSpot
        return padded + COMPRESSED_OOP_REF_BYTES; // + la reference dans le tableau
    }

    public static long ffmBytesPerEntry() {
        return CalendarSlotLayout.ENTRY_BYTES;
    }
}
