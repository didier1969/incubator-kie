package kki.domain.full;

import java.util.Arrays;

/**
 * Calendrier d'une ressource — machine ou metteur en train indifféremment.
 *
 * <p>
 * L'opérateur a précisé que les metteurs ont « chacun leur calendrier individuel <b>comme
 * n'importe quelle ressource d'ailleurs</b> ». Machine et metteur ne sont donc pas deux objets
 * différents : ce sont deux ressources, et leur calendrier est le même concept. Une machine
 * 24/7 est simplement un calendrier ouvert tout le temps.
 *
 * <p>
 * Deux couches, parce que le domaine en a deux :
 * <ul>
 * <li><b>un motif hebdomadaire</b> — jours ouvrés et plage horaire, ce qui décrit « Lun–Mer 8 h »
 * comme « 24/7 » sans cas particulier ;</li>
 * <li><b>des indisponibilités datées</b> — les fenêtres de maintenance d'une machine
 * (`CPT-KKI-004`, 4ᵉ cas, subies et jamais déplaçables par l'optimiseur) et les absences d'un
 * metteur (`CPT-KKI-007`, « peut tomber malade »). Le même mécanisme sert aux deux, ce qui est
 * exactement ce que dit « comme n'importe quelle ressource ».</li>
 * </ul>
 *
 * <p>
 * Le calcul passe par un <b>changement d'axe temporel</b> : deux fonctions inverses l'une de
 * l'autre convertissent temps réel ↔ temps ouvert. Les durées redeviennent additives dans l'axe
 * transformé, ce qui préserve la forme linéaire des passes de datation. Les indisponibilités
 * sont indexées par sommes préfixes, donc une conversion reste en O(log n) quel que soit le
 * nombre de fenêtres.
 *
 * <p>
 * L'origine des temps est un lundi 00:00 par construction.
 */
public final class WorkCalendar {

    private static final long DAY = 86_400L;
    private static final long WEEK = 7L * DAY;

    /** Masque de tous les jours ouvrés — lundi = bit 0, dimanche = bit 6. */
    public static final int EVERY_DAY = 0b111_1111;
    /** Lundi, mardi, mercredi. */
    public static final int MONDAY_TO_WEDNESDAY = 0b000_0111;
    /**
     * Vendredi ET lundi — le motif NON CONTIGU de l'exemple opérateur. Impossible à décrire tant
     * que le calendrier ne connaissait que « les N premiers jours de la semaine ».
     */
    public static final int FRIDAY_AND_MONDAY = 0b001_0001;

    /** Machine sans interruption : ouverte 7 jours sur 7, 24 h sur 24. */
    public static final WorkCalendar CONTINUOUS = new WorkCalendar(EVERY_DAY, 0L, DAY, new long[0]);

    /** Rendu par {@link #occupancyStart} quand l'occupation ne peut pas tenir avant la borne. */
    public static final long IMPOSSIBLE = Long.MIN_VALUE / 4;

    /** Metteur en train type : lundi à mercredi, 08:00–16:00. */
    public static final WorkCalendar MONDAY_TO_WEDNESDAY_8H =
            new WorkCalendar(MONDAY_TO_WEDNESDAY, 8L * 3600L, 8L * 3600L, new long[0]);

    private final int workingDayMask;
    private final long windowStart;
    private final long windowLength;
    private final long workPerWeek;
    /**
     * Temps ouvert cumulé depuis le lundi 00:00 jusqu'au début du jour <i>d</i>, pour d de 0 à 7.
     * Huit entiers qui remplacent une boucle sur les jours à chaque conversion — les deux
     * conversions sont sur le chemin chaud de la datation.
     */
    private final long[] workedByStartOfDay;

    /** Indisponibilités datées, aplaties en [début, fin) triés et disjoints. */
    private final long[] blackouts;
    /** Temps ouvert perdu cumulé avant chaque indisponibilité — évite de les reparcourir. */
    private final long[] lostBefore;

    /**
     * Motif CONTIGU : les {@code workingDays} premiers jours de la semaine.
     *
     * <p>
     * C'est une FABRIQUE NOMMÉE et non une surcharge du constructeur, délibérément. Une surcharge
     * {@code (long workingDays, ...)} à côté de {@code (int mask, ...)} se distingue par le seul
     * type du premier argument : Java choisit alors {@code int} pour tout littéral, et
     * {@code new WorkCalendar(5, ...)} — « les cinq premiers jours » — devient silencieusement le
     * masque {@code 0b101}, soit lundi et mercredi. L'erreur ne se voit ni à la compilation ni à
     * la lecture ; elle a doublé le coût de l'instance de référence avant d'être trouvée.
     */
    public static WorkCalendar ofFirstDays(int workingDays, long windowStart, long windowLength,
            long[] blackouts) {
        return new WorkCalendar(maskOfFirstDays(workingDays), windowStart, windowLength, blackouts);
    }

    /**
     * Motif QUELCONQUE, décrit par un masque de jours — lundi = bit 0, dimanche = bit 6.
     *
     * <p>
     * La version précédente ne savait exprimer que « les N premiers jours de la semaine ». Or
     * l'opérateur a donné en exemple un metteur qui travaille <b>le vendredi et le lundi</b> :
     * un motif non contigu, et le pire cas du modèle — une mise en train de 16 h y bloque la
     * machine du vendredi au lundi soir, soit une centaine d'heures de production perdues pour
     * seize heures de travail. Ce cas-là n'était pas représentable, donc pas testable.
     */
    public WorkCalendar(int workingDayMask, long windowStart, long windowLength, long[] blackouts) {
        this.workingDayMask = workingDayMask & EVERY_DAY;
        this.windowStart = windowStart;
        this.windowLength = windowLength;
        this.workPerWeek = Integer.bitCount(this.workingDayMask) * windowLength;
        this.workedByStartOfDay = new long[8];
        for (int day = 0; day < 7; day++) {
            workedByStartOfDay[day + 1] = workedByStartOfDay[day]
                    + (isWorkingDay(day) ? windowLength : 0L);
        }
        this.blackouts = blackouts.clone();
        this.lostBefore = new long[blackouts.length / 2 + 1];
        long cumulated = 0L;
        for (int i = 0; i < blackouts.length; i += 2) {
            lostBefore[i / 2] = cumulated;
            cumulated += patternWorkedBefore(blackouts[i + 1]) - patternWorkedBefore(blackouts[i]);
        }
        lostBefore[blackouts.length / 2] = cumulated;
    }

    /** Le même calendrier, avec des indisponibilités datées en plus. */
    public WorkCalendar withBlackouts(long[] flattenedIntervals) {
        return new WorkCalendar(workingDayMask, windowStart, windowLength, flattenedIntervals);
    }

    private boolean isWorkingDay(int dayOfWeek) {
        return (workingDayMask & (1 << dayOfWeek)) != 0;
    }

    private static int maskOfFirstDays(int count) {
        return (1 << Math.max(0, Math.min(7, count))) - 1;
    }

    /** Secondes réellement ouvertes entre l'origine et {@code time}, indisponibilités déduites. */
    public long workedSecondsBefore(long time) {
        return patternWorkedBefore(time) - lostUpTo(time);
    }

    /**
     * Inverse : instant réel auquel la ressource a été ouverte {@code workedSeconds} secondes.
     *
     * <p>
     * <b>Résolu par recherche sur les indisponibilités, pas par itération.</b> La version
     * précédente cherchait un point fixe — « ajouter la perte, recalculer, recommencer » — et ne
     * convergeait pas dès qu'une seule fenêtre couvrait PLUSIEURS périodes ouvrées : chaque
     * itération n'avançait que d'une journée ouvrée, et le compteur de sécurité s'épuisait avant
     * d'avoir traversé la fenêtre, rendant une date silencieusement trop précoce. Une absence de
     * metteur de cinq jours couvre trois journées ouvrées : le défaut était donc atteint en
     * production, pas seulement en théorie. Trouvé par l'aller-retour
     * {@code occupancyStart(occupancyEnd(t, s), s)} de la passe amont.
     *
     * <p>
     * La grandeur {@code patternWorkedBefore(début_i) - lostBefore[i]} — le temps ouvert NET
     * disponible avant la fenêtre <i>i</i> — est croissante. Il suffit donc de trouver par
     * dichotomie la dernière fenêtre entièrement franchie, et d'ajouter sa perte cumulée une
     * seule fois. Exact, et en O(log n).
     */
    public long timeAtWorkedSeconds(long workedSeconds) {
        return patternTimeAtEarliest(workedSeconds + lostBeforeReaching(workedSeconds));
    }

    /**
     * Le même instant, pris à l'autre bout de sa classe d'équivalence.
     *
     * <p>
     * Un compteur de temps ouvert ne désigne pas UN instant mais un intervalle : la fermeture du
     * vendredi 16:00 et l'ouverture du lundi 08:00 portent le même compteur, et tout le week-end
     * entre les deux aussi. Pour une date au plus TÔT — quand une occupation se termine-t-elle
     * réellement — c'est le début de l'intervalle qui compte. Pour une date au plus TARD — la
     * passe amont — c'en est la fin.
     */
    public long latestTimeAtWorkedSeconds(long workedSeconds) {
        return patternTimeAtLatest(workedSeconds + lostBeforeReaching(workedSeconds));
    }

    /** Temps ouvert perdu dans les fenêtres entièrement franchies pour atteindre ce compteur. */
    private long lostBeforeReaching(long workedSeconds) {
        if (blackouts.length == 0) {
            return 0L;
        }
        int low = 0;
        int high = blackouts.length / 2;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (netWorkedBeforeWindow(mid) <= workedSeconds) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return lostBefore[low];
    }

    /** Temps ouvert net, indisponibilités antérieures déduites, avant le début de la fenêtre. */
    private long netWorkedBeforeWindow(int window) {
        return patternWorkedBefore(blackouts[2 * window]) - lostBefore[window];
    }

    /**
     * Fin d'une occupation de {@code workSeconds} secondes de temps ouvert, ne pouvant pas
     * commencer avant {@code readyAt}. Si la ressource est fermée à cet instant, l'occupation
     * attend l'ouverture suivante.
     */
    public long occupancyEnd(long readyAt, long workSeconds) {
        if (workSeconds <= 0L) {
            return readyAt;
        }
        return timeAtWorkedSeconds(workedSecondsBefore(readyAt) + workSeconds);
    }

    /**
     * Inverse d'{@link #occupancyEnd} : instant le plus TARDIF auquel commencer une occupation de
     * {@code workSeconds} secondes de temps ouvert pour l'avoir terminée au plus tard à
     * {@code finishBy}. C'est ce que demande la passe amont de `CPT-KKI-003`.
     *
     * <p>
     * Deux points qui ressemblent à des erreurs et n'en sont pas :
     * <ul>
     * <li>quand la ressource n'a pas assez d'heures ouvertes avant {@code finishBy}, le solde
     * demandé devient négatif ; {@link #patternTimeAt} produirait alors n'importe quoi par
     * troncature de division. On rend {@code Long.MIN_VALUE / 4}, une date impossible que
     * l'appelant reconnaît, plutôt qu'une date silencieusement fausse ;</li>
     * <li>{@link #timeAtWorkedSeconds} rend l'instant le plus TARDIF de sa classe d'équivalence
     * (lundi 16:00 et mardi 08:00 valent le même compteur de temps ouvert ; c'est mardi 08:00 qui
     * est rendu). Pour une date au plus tard, c'est exactement la borne voulue.</li>
     * </ul>
     */
    public long occupancyStart(long finishBy, long workSeconds) {
        if (workSeconds <= 0L) {
            return finishBy;
        }
        long available = workedSecondsBefore(finishBy);
        if (available < workSeconds) {
            return IMPOSSIBLE;
        }
        return latestTimeAtWorkedSeconds(available - workSeconds);
    }

    /**
     * Temps mur pendant lequel la ressource est immobilisée sans qu'aucun travail ne s'y fasse :
     * la différence entre le temps mur consommé et le temps ouvert réellement dépensé. C'est le
     * terme que `CPT-KKI-007` fait payer au coût horaire machine.
     */
    public long idleDuring(long readyAt, long workSeconds) {
        if (workSeconds <= 0L) {
            return 0L;
        }
        return occupancyEnd(readyAt, workSeconds) - readyAt - workSeconds;
    }

    /** La ressource est-elle ouverte à cet instant précis ? */
    public boolean isOpenAt(long time) {
        long remainder = Math.floorMod(time, WEEK);
        int day = (int) (remainder / DAY);
        long timeOfDay = remainder % DAY;
        if (!isWorkingDay(day) || timeOfDay < windowStart
                || timeOfDay >= windowStart + windowLength) {
            return false;
        }
        return !isBlackedOutAt(time);
    }

    public boolean isBlackedOutAt(long time) {
        int index = Arrays.binarySearch(blackouts, time);
        if (index >= 0) {
            return index % 2 == 0; // pile sur un début de fenêtre
        }
        return (-index - 1) % 2 == 1; // à l'intérieur d'une fenêtre
    }

    // ************************************************************************
    // Motif hebdomadaire seul, indisponibilités exclues
    // ************************************************************************

    private long patternWorkedBefore(long time) {
        if (time <= 0L) {
            return 0L;
        }
        long weeks = time / WEEK;
        long remainder = time % WEEK;
        int day = (int) (remainder / DAY);
        long timeOfDay = remainder % DAY;
        long worked = weeks * workPerWeek + workedByStartOfDay[day];
        if (isWorkingDay(day)) {
            worked += Math.max(0L, Math.min(timeOfDay - windowStart, windowLength));
        }
        return worked;
    }

    /**
     * PREMIER instant portant ce compteur de temps ouvert — la fermeture du dernier jour ouvré
     * traversé, pas l'ouverture du suivant.
     *
     * <p>
     * La version précédente rendait le représentant le plus TARDIF, y compris pour
     * {@link #occupancyEnd}. Avec un motif lundi-mercredi cela ajoutait une nuit fictive à toute
     * mise en train se terminant pile en fin de journée ; avec le motif vendredi + lundi de
     * l'opérateur, cela ajoutait <b>quatre jours</b> — et ces heures étaient facturées au coût
     * horaire de la machine.
     */
    private long patternTimeAtEarliest(long workedSeconds) {
        long weeks = workedSeconds / workPerWeek;
        long remainder = workedSeconds % workPerWeek;
        if (remainder == 0L) {
            if (weeks == 0L) {
                return 0L; // rien n'a encore été travaillé : le plus tôt est l'origine
            }
            // Un compteur qui tombe pile sur un multiple de la semaine désigne la FERMETURE du
            // dernier jour ouvré de la semaine précédente, jamais l'ouverture de la suivante.
            int lastWorkingDay = 31 - Integer.numberOfLeadingZeros(workingDayMask);
            return (weeks - 1) * WEEK + (long) lastWorkingDay * DAY + windowStart + windowLength;
        }
        // Les jours fermés ne font pas progresser le cumul : la boucle les franchit d'elle-même.
        int day = 0;
        while (day < 7 && workedByStartOfDay[day + 1] < remainder) {
            day++;
        }
        return weeks * WEEK + (long) day * DAY + windowStart
                + (remainder - workedByStartOfDay[day]);
    }

    /** DERNIER instant portant ce compteur — ce que veut une date au plus tard. */
    private long patternTimeAtLatest(long workedSeconds) {
        long weeks = workedSeconds / workPerWeek;
        long remainder = workedSeconds % workPerWeek;
        int day = 0;
        while (day < 7 && workedByStartOfDay[day + 1] <= remainder) {
            day++;
        }
        return weeks * WEEK + (long) day * DAY + windowStart
                + (remainder - workedByStartOfDay[day]);
    }

    /** Temps ouvert perdu dans les indisponibilités situées avant {@code time}. */
    private long lostUpTo(long time) {
        if (blackouts.length == 0) {
            return 0L;
        }
        int index = Arrays.binarySearch(blackouts, time);
        int position = index >= 0 ? index : -index - 1;
        int completedWindows = position / 2;
        long lost = lostBefore[completedWindows];
        if (position % 2 == 1) {
            // On est à l'intérieur d'une fenêtre : compter la part déjà traversée.
            lost += patternWorkedBefore(time) - patternWorkedBefore(blackouts[position - 1]);
        }
        return lost;
    }
}
