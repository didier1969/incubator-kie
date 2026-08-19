package kki.domain.full;

/**
 * La fonction de coût de PIL-KKI-004, source unique.
 *
 * <p>
 * Deux calculateurs l'utilisent — celui de la représentation par priorité X et celui de la
 * représentation par séquence machine. Les faire diverger d'un centime rendrait leurs mesures
 * incomparables, ce qui est précisément ce qu'on cherche à comparer (GUI-PRO-013).
 *
 * <p>
 * Tout est en <b>centimes</b> : comparer des heures de metteur en train à des heures machine et à
 * des quadratiques de retard n'a de sens que dans une unité commune.
 */
public final class CostModel {

    /** 250 CHF de l'heure de metteur en train. */
    public static final long SETTER_CENTS_PER_HOUR = 25_000L;
    /**
     * Retard : {@code priorité × k × h²} avec <b>k = 5 CHF/h²</b>, la calibration de CPT-KKI-009.
     * La version précédente utilisait k = 10, ce qui doublait le poids du couple retard/avance
     * face aux 250 CHF/h du metteur et aux 60-150 CHF/h machine — le rapport interne était bon,
     * l'équilibre avec les autres termes ne l'était pas.
     */
    private static final long TARDINESS_CENTS_PER_HOUR2 = 500L;
    /** Avance : {@code (k/10) × h²}, JAMAIS pondérée par la priorité. */
    private static final long EARLINESS_CENTS_PER_HOUR2 = 50L;
    /** Gel souple : écart au dernier plan publié. */
    private static final long SOFT_FREEZE_CENTS_PER_HOUR = 500L;

    private CostModel() {
    }

    /** Coût d'un ordre à partir de sa date de fin : retard, avance, et dérive au plan publié. */
    public static long orderCents(Order order, long completionEpochSec) {
        double deviationHours = (completionEpochSec - order.getDueEpochSec()) / 3600.0;
        long cents;
        if (deviationHours > 0.0) {
            cents = Math.round(deviationHours * deviationHours * TARDINESS_CENTS_PER_HOUR2
                    * order.getPriorityWeight());
        } else {
            cents = Math.round(deviationHours * deviationHours * EARLINESS_CENTS_PER_HOUR2);
        }
        if (order.getFreezeLevel() == Order.FreezeLevel.SOFT) {
            double driftHours = Math.abs(completionEpochSec - order.getReferenceCompletionEpochSec()) / 3600.0;
            cents += Math.round(driftHours * SOFT_FREEZE_CENTS_PER_HOUR);
        }
        return cents;
    }

    /**
     * Coût d'occupation d'une mise en train : heures de metteur consommées, plus heures machine
     * immobilisées dans les trous du calendrier metteur.
     */
    public static long resourceCents(long setupSeconds, long machineIdleSeconds, long machineHourlyCents) {
        return setupSeconds * SETTER_CENTS_PER_HOUR / 3600L
                + machineIdleSeconds * machineHourlyCents / 3600L;
    }

    /**
     * Violation de gel dur : déplacer un ordre déjà lancé n'est pas un surcoût, c'est une faute.
     * Elle pèse sur le score DUR, que le solveur ne peut jamais échanger contre du souple.
     */
    public static long hardViolation(Order order, long completionEpochSec) {
        return order.getFreezeLevel() == Order.FreezeLevel.HARD
                ? Math.abs(completionEpochSec - order.getReferenceCompletionEpochSec())
                : 0L;
    }
}
