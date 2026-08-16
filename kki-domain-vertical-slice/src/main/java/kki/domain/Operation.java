package kki.domain;

/**
 * REQ-KKI-006 (réécrit) — fait fixe : la machine d'une opération est
 * assignée à la génération (choix machine = CPT-KKI-008, différé). Plus de
 * shadow variable ici — les heures sont recalculées par un balayage global
 * (VerticalSliceIncrementalScoreCalculator), piloté par la seule variable
 * de décision de cette tranche : Order.xPosition (via Schedule.orderSequence).
 */
public final class Operation {

    private final long id;
    private final Order order;
    private final int sequenceIndexInOrder;
    private final long durationSeconds;
    private final long machineId;

    public Operation(long id, Order order, int sequenceIndexInOrder, long durationSeconds, long machineId) {
        this.id = id;
        this.order = order;
        this.sequenceIndexInOrder = sequenceIndexInOrder;
        this.durationSeconds = durationSeconds;
        this.machineId = machineId;
    }

    public long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public int getSequenceIndexInOrder() {
        return sequenceIndexInOrder;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public long getMachineId() {
        return machineId;
    }

    @Override
    public String toString() {
        return "Op-" + id + "(order=" + order.getId() + ",machine=" + machineId + ")";
    }
}
