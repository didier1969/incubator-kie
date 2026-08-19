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
 * du successeur sur la ressource, pas sur l'article seul. {@code setupKey} est cet index. Elle
 * consomme trois ressources exclusives : la machine, un metteur compétent, et — pour une partie
 * des opérations — un exemplaire d'outillage du pool partagé.
 *
 * <p>
 * CPT-KKI-008 — machine, metteur et outillage SONT des décisions du domaine, et la logique
 * incrémentale de réaffectation existe et est vérifiée par test différentiel. Elles ne sont
 * pourtant pas des {@code @PlanningVariable} : cette version d'OptaPlanner ne sait pas faire
 * coexister une variable-liste et une variable simple, même portées par des classes d'entités
 * DIFFÉRENTES ({@code SolutionDescriptor.countUnassignedValues} applique le descripteur de liste
 * à toutes les entités sans filtrer, et lève sur la première qui ne la porte pas — DEC-KKI-004).
 * L'opération reste donc un <b>fait</b>, et les réaffectations passent par une commande de phase
 * personnalisée entre deux phases de recherche. La piste « représentation par séquence machine »
 * qui figurait ici est <b>close</b> : REQ-KKI-014 l'a mesurée perdante d'un facteur 19 à 32.
 */
public class Operation {

    /** Valeur de {@link #requiredToolingType} quand la mise en train n'emprunte aucun outillage. */
    public static final int NO_TOOLING = -1;

    private long id;
    private Order order;
    private int passIndex;
    private long durationSeconds;
    private int requiredTechnology;
    private int requiredLevel;
    private int setupKey;
    private int requiredToolingType;
    private List<Machine> compatibleMachines;
    private List<Tooling> compatibleToolings;

    private Machine machine;
    private Setter setter;
    private Tooling tooling;

    public Operation() {
    }

    public Operation(long id, Order order, int passIndex, long durationSeconds,
            int requiredTechnology, int requiredLevel, int setupKey, int requiredToolingType,
            List<Machine> compatibleMachines, List<Tooling> compatibleToolings,
            Machine machine, Setter setter, Tooling tooling) {
        this.id = id;
        this.order = order;
        this.passIndex = passIndex;
        this.durationSeconds = durationSeconds;
        this.requiredTechnology = requiredTechnology;
        this.requiredLevel = requiredLevel;
        this.setupKey = setupKey;
        this.requiredToolingType = requiredToolingType;
        this.compatibleMachines = compatibleMachines;
        this.compatibleToolings = compatibleToolings;
        this.machine = machine;
        this.setter = setter;
        this.tooling = tooling;
    }

    public List<Machine> getCompatibleMachines() {
        return compatibleMachines;
    }

    /** Les exemplaires du type exigé — vide quand la mise en train n'emprunte rien. */
    public List<Tooling> getCompatibleToolings() {
        return compatibleToolings;
    }

    /**
     * Le metteur qui exécute LA MISE EN TRAIN de cette opération. C'est une décision du domaine
     * au même titre que la machine : il faut un metteur compétent pour cette machine, et il n'en
     * fait qu'une à la fois.
     */
    public Setter getSetter() {
        return setter;
    }

    public void setSetter(Setter setter) {
        this.setter = setter;
    }

    /** L'exemplaire emprunté pour cette mise en train, {@code null} si elle n'en exige aucun. */
    public Tooling getTooling() {
        return tooling;
    }

    public void setTooling(Tooling tooling) {
        this.tooling = tooling;
    }

    /**
     * Le type d'outillage exigé, ou {@link #NO_TOOLING}. C'est une donnée <b>statique</b> de
     * l'opération, dérivée de sa clé (article, passe) : la faire dépendre du prédécesseur sur la
     * machine — par exemple « n'emprunter que si la mise en train dure plus de zéro » — rendrait
     * l'appartenance à la file d'outillage variable d'un mouvement à l'autre, et ferait tomber
     * toute la structure incrémentale.
     */
    public int getRequiredToolingType() {
        return requiredToolingType;
    }

    public Machine getMachine() {
        return machine;
    }

    /**
     * Le metteur n'est pas revalidé ici, et ce n'est pas un oubli : toutes les machines
     * compatibles d'une opération partagent sa technologie requise (compatibilité ASCENDANTE au
     * sein d'UNE technologie), et {@link Setter#canSetUp} ne regarde que la technologie. La
     * compétence survit donc à un changement de machine par construction.
     */
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
