package kki.domain.full;

/**
 * CPT-KKI-007 — les deux calendriers indépendants, et le coût qui naît de leur désaccord.
 *
 * <p>
 * La machine peut tourner 24/7 ; le metteur en train, non — lundi à mercredi, 8 h par jour. Une
 * mise en train ne consomme donc du temps que <b>dans les fenêtres du metteur</b>, mais elle
 * bloque la machine du moment où celle-ci est prête jusqu'à la fin de la mise en train, trous
 * du calendrier compris. C'est exactement le second terme du coût de PIL-KKI-004 : heures
 * metteur travaillées × 250, plus heures machine perdues dans les trous × coût horaire machine.
 *
 * <p>
 * Implémenté par <b>changement d'axe temporel</b> (levier L8) plutôt que par une simulation pas
 * à pas : deux fonctions inverses l'une de l'autre convertissent temps réel ↔ temps ouvré du
 * metteur. Les durées redeviennent additives dans l'axe transformé, ce qui préserve la forme
 * linéaire des passes de datation et donne un calcul en O(1) au lieu d'une boucle sur les
 * fenêtres. L'origine des temps est un lundi 00:00 par construction.
 */
public final class SetterCalendar {

    private static final long DAY = 86_400L;
    private static final long WEEK = 7L * DAY;
    /** Ouverture du metteur, 08:00. */
    private static final long WINDOW_START = 8L * 3600L;
    /** 8 h par jour ouvré. */
    private static final long WINDOW_LENGTH = 8L * 3600L;
    /** Lundi, mardi, mercredi. */
    private static final long WORKING_DAYS = 3L;
    private static final long WORK_PER_WEEK = WORKING_DAYS * WINDOW_LENGTH;

    private SetterCalendar() {
    }

    /** Secondes de metteur effectivement travaillées entre l'origine et {@code time}. */
    public static long workedSecondsBefore(long time) {
        if (time <= 0L) {
            return 0L;
        }
        long weeks = time / WEEK;
        long remainder = time % WEEK;
        long worked = weeks * WORK_PER_WEEK;
        long day = remainder / DAY;
        long timeOfDay = remainder % DAY;
        worked += Math.min(day, WORKING_DAYS) * WINDOW_LENGTH;
        if (day < WORKING_DAYS) {
            worked += Math.max(0L, Math.min(timeOfDay - WINDOW_START, WINDOW_LENGTH));
        }
        return worked;
    }

    /** Inverse : instant réel auquel le metteur a travaillé {@code workedSeconds} secondes. */
    public static long timeAtWorkedSeconds(long workedSeconds) {
        long weeks = workedSeconds / WORK_PER_WEEK;
        long remainder = workedSeconds % WORK_PER_WEEK;
        long day = remainder / WINDOW_LENGTH;
        long within = remainder % WINDOW_LENGTH;
        return weeks * WEEK + day * DAY + WINDOW_START + within;
    }

    /**
     * Fin d'une mise en train de {@code setupSeconds} secondes de travail metteur, ne pouvant
     * pas commencer avant {@code machineReadyAt}. Si la machine est prête hors fenêtre, la mise
     * en train attend l'ouverture suivante — et la machine attend avec elle.
     */
    public static long setupEnd(long machineReadyAt, long setupSeconds) {
        if (setupSeconds <= 0L) {
            return machineReadyAt;
        }
        return timeAtWorkedSeconds(workedSecondsBefore(machineReadyAt) + setupSeconds);
    }

    /**
     * Heures machine perdues : temps pendant lequel la ressource est immobilisée sans qu'aucun
     * travail metteur ne s'y fasse. C'est la différence entre le temps mur consommé par la mise
     * en train et le temps metteur réellement dépensé.
     */
    public static long machineIdleDuringSetup(long machineReadyAt, long setupSeconds) {
        if (setupSeconds <= 0L) {
            return 0L;
        }
        return setupEnd(machineReadyAt, setupSeconds) - machineReadyAt - setupSeconds;
    }
}
