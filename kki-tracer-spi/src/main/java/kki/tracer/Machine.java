package kki.tracer;

public class Machine {

    private final long id;
    private final long capacity;

    public Machine(long id, long capacity) {
        this.id = id;
        this.capacity = capacity;
    }

    public long getId() {
        return id;
    }

    public long getCapacity() {
        return capacity;
    }

    @Override
    public String toString() {
        return "Machine-" + id;
    }
}
