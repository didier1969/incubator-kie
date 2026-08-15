package kki.bench.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * REQ-KKI-003 — cote FFM API (JEP 454, preview sur JDK 21 / JEP 442) du
 * comparatif : le meme creneau que CalendarSlot, mais packe dans un seul
 * MemorySegment off-heap, zero en-tete d'objet par element. 4 champs longs
 * (machineId, start, end, available-comme-0/1) = 32 octets/entree exacts,
 * pas de padding entre elements de la sequence.
 */
public final class CalendarSlotLayout {

    public static final MemoryLayout STRUCT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("machineId"),
            ValueLayout.JAVA_LONG.withName("startEpochSec"),
            ValueLayout.JAVA_LONG.withName("endEpochSec"),
            ValueLayout.JAVA_LONG.withName("available"));

    public static final long ENTRY_BYTES = STRUCT.byteSize();

    private CalendarSlotLayout() {
    }

    public static MemorySegment allocate(Arena arena, int count) {
        return arena.allocate(MemoryLayout.sequenceLayout(count, STRUCT));
    }

    public static void set(MemorySegment seg, int index, long machineId, long start, long end, boolean available) {
        long base = index * ENTRY_BYTES;
        seg.set(ValueLayout.JAVA_LONG, base, machineId);
        seg.set(ValueLayout.JAVA_LONG, base + 8, start);
        seg.set(ValueLayout.JAVA_LONG, base + 16, end);
        seg.set(ValueLayout.JAVA_LONG, base + 24, available ? 1L : 0L);
    }

    public static long getMachineId(MemorySegment seg, int index) {
        return seg.get(ValueLayout.JAVA_LONG, index * ENTRY_BYTES);
    }

    public static boolean isAvailable(MemorySegment seg, int index) {
        return seg.get(ValueLayout.JAVA_LONG, index * ENTRY_BYTES + 24) != 0L;
    }
}
