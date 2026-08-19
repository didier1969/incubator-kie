package kki.domain.full;

/**
 * Façade historique sur le calendrier metteur type — lundi à mercredi, 08:00–16:00.
 *
 * <p>
 * L'implémentation vit désormais dans {@link WorkCalendar}, parce que machine et metteur sont
 * deux ressources du même genre et que leur calendrier est le même concept. Cette classe est
 * conservée parce que ses tests à valeurs calculées à la main (attente de l'ouverture, bascule
 * de la fenêtre du mercredi au lundi suivant) gardent la transformation d'axe temporel — les
 * supprimer reviendrait à retirer le filet sous le mécanisme le plus subtil du modèle.
 */
public final class SetterCalendar {

    private SetterCalendar() {
    }

    public static long workedSecondsBefore(long time) {
        return WorkCalendar.MONDAY_TO_WEDNESDAY_8H.workedSecondsBefore(time);
    }

    public static long timeAtWorkedSeconds(long workedSeconds) {
        return WorkCalendar.MONDAY_TO_WEDNESDAY_8H.timeAtWorkedSeconds(workedSeconds);
    }

    public static long setupEnd(long machineReadyAt, long setupSeconds) {
        return WorkCalendar.MONDAY_TO_WEDNESDAY_8H.occupancyEnd(machineReadyAt, setupSeconds);
    }

    public static long machineIdleDuringSetup(long machineReadyAt, long setupSeconds) {
        return WorkCalendar.MONDAY_TO_WEDNESDAY_8H.idleDuring(machineReadyAt, setupSeconds);
    }
}
