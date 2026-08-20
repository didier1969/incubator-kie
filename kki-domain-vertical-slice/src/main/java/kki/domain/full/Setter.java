package kki.domain.full;

import org.optaplanner.core.api.domain.lookup.PlanningId;

/**
 * Metteur en train — une ressource comme une autre, avec son calendrier propre et ses
 * compétences.
 *
 * <p>
 * Précision opérateur : « il peut y avoir plusieurs metteurs en train, ils ont chacun leur
 * calendrier individuel comme n'importe quelle ressource d'ailleurs, et ils ont des compétences.
 * Donc il faut trouver un metteur en train qui a la compétence pour faire la mise en train sur
 * cette machine. »
 *
 * <p>
 * Deux contraintes en découlent, et elles sont de natures différentes :
 * <ul>
 * <li><b>capacité</b> — un metteur ne fait qu'une mise en train à la fois. Le modèle précédent
 * en autorisait un nombre illimité en parallèle, ce qui revenait à supposer un metteur par
 * machine ;</li>
 * <li><b>compétence</b> — tous ne savent pas régler toutes les machines. C'est un goulot d'une
 * autre nature que celui des machines : la technologie se substitue vers le haut
 * (`CPT-KKI-008`), la compétence non. Un metteur qui ne sait pas est un mur, pas un surcoût.</li>
 * </ul>
 *
 * <p>
 * La maladie de `CPT-KKI-007` n'a besoin d'aucun mécanisme dédié : c'est une indisponibilité
 * datée dans son calendrier, exactement comme une fenêtre de maintenance sur une machine.
 */
public final class Setter {

    @PlanningId
    private final long id;
    private final WorkCalendar calendar;
    /** Technologies que ce metteur sait régler, indexées par numéro de technologie. */
    private final boolean[] masteredTechnologies;

    public Setter(long id, WorkCalendar calendar, boolean[] masteredTechnologies) {
        this.id = id;
        this.calendar = calendar;
        this.masteredTechnologies = masteredTechnologies.clone();
    }

    public long getId() {
        return id;
    }

    public WorkCalendar getCalendar() {
        return calendar;
    }

    /** Ce metteur sait-il régler cette machine ? */
    public boolean canSetUp(Machine machine) {
        int technology = machine.getTechnology();
        return technology < masteredTechnologies.length && masteredTechnologies[technology];
    }

    public int masteredCount() {
        int count = 0;
        for (boolean mastered : masteredTechnologies) {
            if (mastered) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "Setter" + id;
    }
}
