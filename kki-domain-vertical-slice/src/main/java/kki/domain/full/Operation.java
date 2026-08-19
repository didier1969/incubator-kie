package kki.domain.full;

/**
 * Un passage d'un ordre sur une ressource.
 *
 * <p>
 * CPT-KKI-005 (axe Z) — la clé d'identité est <b>(ordre, rang de passe)</b>, jamais
 * (ordre, machine) : un même ordre peut revenir plusieurs fois sur la même machine,
 * consécutivement ou non, et chaque passage a sa propre mise en train. Une identité par
 * machine rendrait l'axe Z inexprimable.
 *
 * <p>
 * CPT-KKI-006 — la mise en train se lit sur le couple <b>(article, passe)</b> du prédécesseur
 * et du successeur sur la ressource, pas sur l'article seul. {@code setupKey} est cet index.
 */
public final class Operation {

    private final long id;
    private final Order order;
    private final int passIndex;
    private final long durationSeconds;
    private final int requiredTechnology;
    private final int requiredLevel;
    private final int setupKey;
    private long machineId;

    public Operation(long id, Order order, int passIndex, long durationSeconds,
            int requiredTechnology, int requiredLevel, int setupKey, long machineId) {
        this.id = id;
        this.order = order;
        this.passIndex = passIndex;
        this.durationSeconds = durationSeconds;
        this.requiredTechnology = requiredTechnology;
        this.requiredLevel = requiredLevel;
        this.setupKey = setupKey;
        this.machineId = machineId;
    }

    public long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public int getPassIndex() {
        return passIndex;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public int getRequiredTechnology() {
        return requiredTechnology;
    }

    public int getRequiredLevel() {
        return requiredLevel;
    }

    /** Index dans la matrice de mise en train : encode le couple (article, passe). */
    public int getSetupKey() {
        return setupKey;
    }

    public long getMachineId() {
        return machineId;
    }

    /** Substitution de ressource — le seul champ mutable, c'est le levier CPT-KKI-008. */
    public void setMachineId(long machineId) {
        this.machineId = machineId;
    }

    @Override
    public String toString() {
        return "Op" + id + "(o" + order.getId() + "p" + passIndex + "@M" + machineId + ")";
    }
}
