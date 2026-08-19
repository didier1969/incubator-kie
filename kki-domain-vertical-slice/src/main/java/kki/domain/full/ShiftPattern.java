package kki.domain.full;

import java.util.Arrays;

/**
 * L'horaire d'un secteur ou d'un profil de personnel — objet PARTAGÉ, jamais recopié.
 *
 * <p>
 * <b>Deux résolutions, parce que le domaine en a deux.</b> L'opérateur veut un calendrier au jour
 * le jour sur deux ans, et accepte qu'au-delà de six mois il soit simplifié : « c'est beaucoup
 * moins important ». D'où :
 * <ul>
 * <li><b>résolution fine</b> — un enregistrement par jour sur les {@code fineDayCount} premiers
 * jours : heure d'ouverture et durée propres à CE jour. C'est ce qui permet des équipes qui
 * changent, des demi-journées, et des jours fériés (durée nulle) sans mécanisme dédié ;</li>
 * <li><b>résolution grossière</b> — au-delà, un motif hebdomadaire de sept jours, prolongé sans
 * limite. Il faut qu'il soit illimité et non arrêté à deux ans : sur une instance chargée les
 * dates de fin partent à plusieurs années, et un calendrier qui s'arrêterait rendrait des dates
 * fausses là où elles comptent le plus.</li>
 * </ul>
 *
 * <p>
 * <b>Le nom dit ce que c'est.</b> La zone fine est à RÉSOLUTION journalière, elle n'est pas
 * « précise » : rien n'y est mesuré dans un atelier réel, c'est une structure au jour le jour
 * remplie de valeurs engendrées. Le jour où des données de terrain arrivent, elles entrent ici
 * sans changer une ligne.
 *
 * <p>
 * <b>Ce qui vit ici et ce qui n'y vit pas.</b> Ce qui est COMMUN au secteur — horaires, jours
 * fériés, fermeture annuelle — vit dans le motif, et coûte donc une seule fois pour toutes les
 * ressources du secteur. Ce qui est PROPRE à une ressource — une maintenance, une absence — reste
 * dans {@link WorkCalendar}, qui référence ce motif sans jamais le copier. Mettre les jours
 * fériés dans les exceptions individuelles les dupliquerait autant de fois qu'il y a de
 * ressources, et perdrait exactement le partage que cette classe existe pour obtenir.
 *
 * <p>
 * L'origine des temps est un lundi 00:00 par construction.
 */
public final class ShiftPattern {

    private static final long DAY = 86_400L;

    /** Jours à résolution journalière. Au-delà, le motif hebdomadaire prend le relais. */
    private final int fineDayCount;
    private final int[] dayStart;
    private final int[] dayLength;
    /** Temps ouvert cumulé avant le début de chaque jour fin — taille {@code fineDayCount + 1}. */
    private final long[] workedBeforeDay;

    /** Motif hebdomadaire de la zone grossière, indexé par jour de semaine réel. */
    private final int[] tailStart;
    private final int[] tailLength;
    private final long[] tailBeforeWeekday;
    private final long tailPerWeek;

    public ShiftPattern(int[] dayStart, int[] dayLength, int[] tailStart, int[] tailLength) {
        if (dayStart.length != dayLength.length) {
            throw new IllegalArgumentException("un début et une durée par jour");
        }
        if (tailStart.length != 7 || tailLength.length != 7) {
            throw new IllegalArgumentException("le motif de queue couvre sept jours");
        }
        this.fineDayCount = dayStart.length;
        this.dayStart = dayStart.clone();
        this.dayLength = dayLength.clone();
        this.workedBeforeDay = new long[fineDayCount + 1];
        for (int day = 0; day < fineDayCount; day++) {
            workedBeforeDay[day + 1] = workedBeforeDay[day] + dayLength[day];
        }
        this.tailStart = tailStart.clone();
        this.tailLength = tailLength.clone();
        this.tailBeforeWeekday = new long[8];
        for (int weekday = 0; weekday < 7; weekday++) {
            tailBeforeWeekday[weekday + 1] = tailBeforeWeekday[weekday] + tailLength[weekday];
        }
        this.tailPerWeek = tailBeforeWeekday[7];
    }

    /**
     * Horaire régulier : mêmes heures tous les jours ouvrés de la semaine, avec des jours fermés
     * datés — fériés, ponts, fermeture annuelle. La zone grossière reprend le même horaire, sans
     * les fermetures datées : c'est exactement la simplification que l'opérateur autorise.
     *
     * @param weekdayMask lundi = bit 0, dimanche = bit 6
     * @param closedDays  indices de jours fermés dans la zone fine, non triés
     */
    public static ShiftPattern regular(int fineDayCount, int weekdayMask, long startSeconds,
            long lengthSeconds, int[] closedDays) {
        int[] start = new int[fineDayCount];
        int[] length = new int[fineDayCount];
        for (int day = 0; day < fineDayCount; day++) {
            boolean open = (weekdayMask & (1 << (day % 7))) != 0;
            start[day] = (int) startSeconds;
            length[day] = open ? (int) lengthSeconds : 0;
        }
        for (int closed : closedDays) {
            if (closed >= 0 && closed < fineDayCount) {
                length[closed] = 0;
            }
        }
        int[] tailStart = new int[7];
        int[] tailLength = new int[7];
        for (int weekday = 0; weekday < 7; weekday++) {
            tailStart[weekday] = (int) startSeconds;
            tailLength[weekday] = (weekdayMask & (1 << weekday)) != 0 ? (int) lengthSeconds : 0;
        }
        return new ShiftPattern(start, length, tailStart, tailLength);
    }

    /** Sans interruption : ouvert tous les jours, vingt-quatre heures sur vingt-quatre. */
    public static ShiftPattern continuous(int fineDayCount) {
        return regular(fineDayCount, 0b111_1111, 0L, DAY, new int[0]);
    }

    public int getFineDayCount() {
        return fineDayCount;
    }

    /** Secondes ouvertes entre l'origine et {@code time}, exceptions individuelles exclues. */
    public long workedBefore(long time) {
        if (time <= 0L) {
            return 0L;
        }
        long day = time / DAY;
        long timeOfDay = time % DAY;
        if (day < fineDayCount) {
            int d = (int) day;
            return workedBeforeDay[d] + within(timeOfDay, dayStart[d], dayLength[d]);
        }
        int weekday = (int) (day % 7);
        return workedBeforeDay[fineDayCount] + tailWorkedBetween(fineDayCount, day)
                + within(timeOfDay, tailStart[weekday], tailLength[weekday]);
    }

    /** PREMIER instant portant ce compteur de temps ouvert. */
    public long timeAtWorkedEarliest(long workedSeconds) {
        return timeAtWorked(workedSeconds, false);
    }

    /** DERNIER instant portant ce compteur — ce que veut une date au plus tard. */
    public long timeAtWorkedLatest(long workedSeconds) {
        return timeAtWorked(workedSeconds, true);
    }

    public boolean isOpenAt(long time) {
        if (time < 0L) {
            return false;
        }
        long day = time / DAY;
        long timeOfDay = time % DAY;
        int start;
        int length;
        if (day < fineDayCount) {
            start = dayStart[(int) day];
            length = dayLength[(int) day];
        } else {
            int weekday = (int) (day % 7);
            start = tailStart[weekday];
            length = tailLength[weekday];
        }
        return length > 0 && timeOfDay >= start && timeOfDay < start + length;
    }

    // ************************************************************************
    // Interne
    // ************************************************************************

    private static long within(long timeOfDay, int start, int length) {
        if (length <= 0) {
            return 0L;
        }
        return Math.max(0L, Math.min(timeOfDay - start, length));
    }

    /** Temps ouvert du motif hebdomadaire entre deux indices de jour absolus. */
    private long tailWorkedBetween(long fromDay, long toDay) {
        return tailWorkedUpTo(toDay) - tailWorkedUpTo(fromDay);
    }

    private long tailWorkedUpTo(long day) {
        return day / 7 * tailPerWeek + tailBeforeWeekday[(int) (day % 7)];
    }

    /**
     * Conversion inverse. La seule différence entre les deux bouts de la classe d'équivalence est
     * la comparaison utilisée pour choisir le jour : un compteur qui tombe pile sur une fin de
     * journée désigne aussi bien cette fermeture que l'ouverture suivante, et tout le temps mort
     * entre les deux.
     */
    private long timeAtWorked(long workedSeconds, boolean latest) {
        if (workedSeconds <= 0L) {
            return latest ? openingOfFirstOpenDay() : 0L;
        }
        long fineTotal = workedBeforeDay[fineDayCount];
        if (latest ? workedSeconds < fineTotal : workedSeconds <= fineTotal) {
            int day = fineDayIndexFor(workedSeconds, latest);
            return (long) day * DAY + dayStart[day] + (workedSeconds - workedBeforeDay[day]);
        }
        long remaining = workedSeconds - fineTotal + tailWorkedUpTo(fineDayCount);
        long weeks = remaining / tailPerWeek;
        long inWeek = remaining % tailPerWeek;
        int weekday = 0;
        while (weekday < 7 && (latest ? tailBeforeWeekday[weekday + 1] <= inWeek
                : tailBeforeWeekday[weekday + 1] < inWeek)) {
            weekday++;
        }
        // Un compteur multiple exact de la semaine appartient encore à la semaine précédente.
        if (!latest && inWeek == 0L && weeks > 0L) {
            weeks--;
            weekday = lastOpenWeekday();
            return weeks * 7L * DAY + (long) weekday * DAY + tailStart[weekday]
                    + tailLength[weekday];
        }
        return weeks * 7L * DAY + (long) weekday * DAY + tailStart[weekday]
                + (inWeek - tailBeforeWeekday[weekday]);
    }

    private int fineDayIndexFor(long workedSeconds, boolean latest) {
        int low = 0;
        int high = fineDayCount - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            boolean pastMid = latest ? workedBeforeDay[mid + 1] <= workedSeconds
                    : workedBeforeDay[mid + 1] < workedSeconds;
            if (pastMid) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private long openingOfFirstOpenDay() {
        for (int day = 0; day < fineDayCount; day++) {
            if (dayLength[day] > 0) {
                return (long) day * DAY + dayStart[day];
            }
        }
        for (int weekday = 0; weekday < 7; weekday++) {
            if (tailLength[weekday] > 0) {
                return (long) (fineDayCount + weekday) * DAY + tailStart[weekday];
            }
        }
        return 0L;
    }

    private int lastOpenWeekday() {
        for (int weekday = 6; weekday >= 0; weekday--) {
            if (tailLength[weekday] > 0) {
                return weekday;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return "ShiftPattern(fine=" + fineDayCount + "j, semaine=" + Arrays.toString(tailLength) + ")";
    }
}
