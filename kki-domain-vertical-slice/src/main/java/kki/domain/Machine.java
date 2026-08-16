package kki.domain;

/**
 * REQ-KKI-006 (réécrit) — fait fixe, plus une @PlanningEntity : dans le
 * concept opérateur, une seule coordonnée X partagée (position de l'ordre)
 * pilote toutes les machines à la fois ; il n'y a plus de séquence par
 * machine choisie indépendamment (c'est ça qui créait le risque de cycle).
 */
public final class Machine {

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
