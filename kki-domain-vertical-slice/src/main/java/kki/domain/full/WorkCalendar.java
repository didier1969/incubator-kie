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

    /** Machine sans interruption : ouverte 7 jours sur 7, 24 h sur 24. */
    public static final WorkCalendar CONTINUOUS = new WorkCalendar(7, 0L, DAY, new long[0]);

    /** Rendu par {@link #occupancyStart} quand l'occupation ne peut pas tenir avant la borne. */
    public static final long IMPOSSIBLE = Long.MIN_VALUE / 4;

    /** Metteur en train type : lundi à mercredi, 08:00–16:00. */
    public static final WorkCalendar MONDAY_TO_WEDNESDAY_8H =
            new WorkCalendar(3, 8L * 3600L, 8L * 3600L, new long[0]);

    private final long workingDays;
    private final long windowStart;
    private final long windowLength;
    private final long workPerWeek;

    /** Indisponibilités datées, aplaties en [début, fin) triés et disjoints. */
    private final long[] blackouts;
    /** Temps ouvert perdu cumulé avant chaque indisponibilité — évite de les reparcourir. */
    private final long[] lostBefore;

    public WorkCalendar(long workingDays, long windowStart, long windowLength, long[] blackouts) {
        this.workingDays = workingDays;
        this.windowStart = windowStart;
        this.windowLength = windowLength;
        this.workPerWeek = workingDays * windowLength;
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
        return new WorkCalendar(workingDays, windowStart, windowLength, flattenedIntervals);
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
        if (blackouts.length == 0) {
            return patternTimeAt(workedSeconds);
        }
        int low = 0;
        int high = blackouts.length / 2; // nombre de fenêtres
        // Cherche le nombre de fenêtres entièrement franchies pour ce compteur de temps ouvert.
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (netWorkedBeforeWindow(mid) <= workedSeconds) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return patternTimeAt(workedSeconds + lostBefore[low]);
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
        return timeAtWorkedSeconds(available - workSeconds);
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
        long day = remainder / DAY;
        long timeOfDay = remainder % DAY;
        if (day >= workingDays || timeOfDay < windowStart || timeOfDay >= windowStart + windowLength) {
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
        long worked = weeks * workPerWeek;
        long day = remainder / DAY;
        long timeOfDay = remainder % DAY;
        worked += Math.min(day, workingDays) * windowLength;
        if (day < workingDays) {
            worked += Math.max(0L, Math.min(timeOfDay - windowStart, windowLength));
        }
        return worked;
    }

    private long patternTimeAt(long workedSeconds) {
        long weeks = workedSeconds / workPerWeek;
        long remainder = workedSeconds % workPerWeek;
        long day = remainder / windowLength;
        long within = remainder % windowLength;
        return weeks * WEEK + day * DAY + windowStart + within;
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
