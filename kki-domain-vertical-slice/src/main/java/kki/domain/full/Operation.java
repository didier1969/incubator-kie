package kki.domain.full;

import java.util.List;

/**
 * Un passage d'un ordre sur une ressource.
 *
 * <p>
 * CPT-KKI-005 (axe Z) — l'identité est <b>(ordre, rang de passe)</b>, jamais (ordre, machine) :
 * un même ordre peut revenir plusieurs fois sur la même machine, consécutivement ou non, et
 * chaque passage a sa propre mise en train. Une identité par machine rendrait l'axe Z
 * inexprimable.
 *
 * <p>
 * CPT-KKI-006 — la mise en train se lit sur le couple <b>(article, passe)</b> du prédécesseur et
 * du successeur sur la ressource, pas sur l'article seul. {@code setupKey} est cet index.
 *
 * <p>
 * CPT-KKI-008 — la machine DEVRAIT être une décision (mouvement M2), et la logique incrémentale
 * correspondante existe et est vérifiée par test différentiel. Mais cette version d'OptaPlanner
 * ne sait pas faire coexister une variable-liste et une variable simple, même portées par des
 * classes d'entités DIFFÉRENTES : {@code SolutionDescriptor.countUnassignedValues} applique le
 * descripteur de liste à toutes les entités sans filtrer, et lève sur la première qui ne la porte
 * pas. L'opération reste donc un fait ici, et M2 attend la représentation par séquence machine,
 * où l'unique classe d'entité porteuse de liste est la ressource. La plage de compatibilité
 * ascendante est déjà construite et mémoïsée — 50 listes partagées par les 17 515 opérations.
 */
public class Operation {

    private long id;
    private Order order;
    private int passIndex;
    private long durationSeconds;
    private int requiredTechnology;
    private int requiredLevel;
    private int setupKey;
    private List<Machine> compatibleMachines;

    private Machine machine;

    public Operation() {
    }

    public Operation(long id, Order order, int passIndex, long durationSeconds,
            int requiredTechnology, int requiredLevel, int setupKey,
            List<Machine> compatibleMachines, Machine machine) {
        this.id = id;
        this.order = order;
        this.passIndex = passIndex;
        this.durationSeconds = durationSeconds;
        this.requiredTechnology = requiredTechnology;
        this.requiredLevel = requiredLevel;
        this.setupKey = setupKey;
        this.compatibleMachines = compatibleMachines;
        this.machine = machine;
    }

    public List<Machine> getCompatibleMachines() {
        return compatibleMachines;
    }

    public Machine getMachine() {
        return machine;
    }

    public void setMachine(Machine machine) {
        this.machine = machine;
    }

    public long getMachineId() {
        return machine.getId();
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

    public int getSetupKey() {
        return setupKey;
    }

    @Override
    public String toString() {
        return "Op" + id + "(o" + order.getId() + "p" + passIndex + "@M"
                + (machine == null ? "?" : machine.getId()) + ")";
    }
}
