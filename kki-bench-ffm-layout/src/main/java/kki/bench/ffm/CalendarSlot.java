package kki.bench.ffm;

/**
 * REQ-KKI-003 — cote POJO du comparatif : un creneau de calendrier
 * machine/metteur (CPT-KKI-007), un objet Java classique.
 */
public final class CalendarSlot {

    public final long machineId;
    public final long startEpochSec;
    public final long endEpochSec;
    public final boolean available;

    public CalendarSlot(long machineId, long startEpochSec, long endEpochSec, boolean available) {
        this.machineId = machineId;
        this.startEpochSec = startEpochSec;
        this.endEpochSec = endEpochSec;
        this.available = available;
    }
}
