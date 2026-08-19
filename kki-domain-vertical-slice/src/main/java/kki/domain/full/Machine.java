package kki.domain.full;

/**
 * CPT-KKI-008 — ressource, avec sa position sur l'échelle de compatibilité ascendante.
 *
 * <p>
 * Une opération exigeant le niveau <i>k</i> d'une technologie peut tourner sur les niveaux
 * <i>k</i> et au-dessus de CETTE technologie, jamais en dessous, et le coût horaire croît le
 * long de l'échelle (60 à 150 CHF/h par paliers d'environ 10). Le coût horaire n'est pas
 * décoratif : il entre dans l'objectif par les heures machine perdues dans les trous du
 * calendrier metteur.
 */
public final class Machine {

    private final long id;
    private final int technology;
    private final int level;
    private final long hourlyCostCents;
    private final WorkCalendar calendar;

    /** Machine sans interruption — le cas « peut tourner 24/7 » de CPT-KKI-007. */
    public Machine(long id, int technology, int level, long hourlyCostCents) {
        this(id, technology, level, hourlyCostCents, WorkCalendar.CONTINUOUS);
    }

    public Machine(long id, int technology, int level, long hourlyCostCents, WorkCalendar calendar) {
        this.id = id;
        this.technology = technology;
        this.level = level;
        this.hourlyCostCents = hourlyCostCents;
        this.calendar = calendar;
    }

    /**
     * Le calendrier porte à la fois les heures d'ouverture et les fenêtres de MAINTENANCE — ces
     * dernières ne sont qu'une indisponibilité datée, ce qui évite un mécanisme dédié pour le
     * 4e cas de CPT-KKI-004.
     */
    public WorkCalendar getCalendar() {
        return calendar;
    }

    public long getId() {
        return id;
    }

    public int getTechnology() {
        return technology;
    }

    public int getLevel() {
        return level;
    }

    public long getHourlyCostCents() {
        return hourlyCostCents;
    }

    /** Compatibilité ASCENDANTE : même technologie, niveau au moins égal à l'exigence. */
    public boolean canRun(int requiredTechnology, int requiredLevel) {
        return technology == requiredTechnology && level >= requiredLevel;
    }

    @Override
    public String toString() {
        return "M" + id + "(t" + technology + "L" + level + ")";
    }
}
