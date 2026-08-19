package kki.domain.full;

import java.util.Arrays;

/**
 * Le calendrier d'UNE ressource — machine ou metteur en train indifféremment.
 *
 * <p>
 * Deux couches, et leur séparation est ce qui empêche l'explosion mémoire :
 * <ul>
 * <li>un {@link ShiftPattern} <b>partagé</b>, qui porte l'horaire du secteur ou du profil de
 * personnel, à résolution journalière sur les premiers mois puis hebdomadaire au-delà. Il est
 * <b>référencé</b>, jamais recopié : mille machines d'un même secteur pointent le même objet ;</li>
 * <li>des <b>indisponibilités datées propres</b> à cette ressource — maintenance d'une machine
 * (`CPT-KKI-004`, 4ᵉ cas), absence d'un metteur (`CPT-KKI-007`). Elles sont individuelles par
 * nature, donc elles seules sont stockées par ressource, et elles tiennent en quelques entiers.</li>
 * </ul>
 *
 * <p>
 * Ce partage n'est pas une intention, il est vérifié par identité d'instance : {@code ==}, pas
 * {@code equals}. Une copie profonde accidentelle passerait n'importe quelle assertion sur des
 * mégaoctets — vingt-deux mégaoctets n'ont l'air de rien — mais échoue sur l'identité.
 *
 * <p>
 * Le calcul passe par un <b>changement d'axe temporel</b> : deux fonctions inverses convertissent
 * temps réel ↔ temps ouvert, ce qui rend les durées additives et préserve la forme linéaire des
 * passes de datation.
 *
 * <p>
 * L'origine des temps est un lundi 00:00 par construction.
 */
public final class WorkCalendar {

    /** Jours à résolution journalière par défaut — six mois, au-delà desquels on simplifie. */
    public static final int DEFAULT_FINE_DAYS = 182;

    /** Masque de tous les jours ouvrés — lundi = bit 0, dimanche = bit 6. */
    public static final int EVERY_DAY = 0b111_1111;
    /** Lundi, mardi, mercredi. */
    public static final int MONDAY_TO_WEDNESDAY = 0b000_0111;
    /**
     * Vendredi ET lundi — le motif NON CONTIGU de l'exemple opérateur, et le pire cas du modèle :
     * seize heures de mise en train y bloquent la machine du jeudi au lundi soir.
     */
    public static final int FRIDAY_AND_MONDAY = 0b001_0001;

    /** Rendu par {@link #occupancyStart} quand l'occupation ne peut pas tenir avant la borne. */
    public static final long IMPOSSIBLE = Long.MIN_VALUE / 4;

    /** Machine sans interruption — le cas « peut tourner 24/7 » de CPT-KKI-007. */
    public static final WorkCalendar CONTINUOUS =
            new WorkCalendar(ShiftPattern.continuous(DEFAULT_FINE_DAYS), new long[0]);

    /** Metteur en train type : lundi à mercredi, 08:00–16:00. */
    public static final WorkCalendar MONDAY_TO_WEDNESDAY_8H =
            new WorkCalendar(MONDAY_TO_WEDNESDAY, 8L * 3600L, 8L * 3600L, new long[0]);

    private final ShiftPattern pattern;
    /** Indisponibilités datées, aplaties en [début, fin) triés et disjoints. */
    private final long[] blackouts;
    /** Temps ouvert perdu cumulé avant chaque indisponibilité — évite de les reparcourir. */
    private final long[] lostBefore;

    /**
     * Constructeur canonique : un motif PARTAGÉ plus les indisponibilités propres à la ressource.
     * Le motif n'est pas copié — c'est tout l'intérêt.
     */
    public WorkCalendar(ShiftPattern pattern, long[] blackouts) {
        this.pattern = pattern;
        this.blackouts = blackouts.clone();
        this.lostBefore = new long[blackouts.length / 2 + 1];
        long cumulated = 0L;
        for (int i = 0; i < blackouts.length; i += 2) {
            lostBefore[i / 2] = cumulated;
            cumulated += pattern.workedBefore(blackouts[i + 1]) - pattern.workedBefore(blackouts[i]);
        }
        lostBefore[blackouts.length / 2] = cumulated;
    }

    /** Raccourci pour un horaire régulier non partagé — commodité de test, pas de production. */
    public WorkCalendar(int workingDayMask, long windowStart, long windowLength, long[] blackouts) {
        this(ShiftPattern.regular(DEFAULT_FINE_DAYS, workingDayMask, windowStart, windowLength,
                new int[0]), blackouts);
    }

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
     * Le même calendrier, avec des indisponibilités datées en plus.
     *
     * <p>
     * Le motif partagé traverse cet appel par RÉFÉRENCE. C'est le point sur lequel tout le
     * partage repose : le générateur crée un calendrier par ressource pour y inscrire sa
     * maintenance, et si cette méthode recopiait le motif, chaque ressource porterait sa propre
     * table de cent quatre-vingt-deux jours.
     */
    public WorkCalendar withBlackouts(long[] flattenedIntervals) {
        return new WorkCalendar(pattern, flattenedIntervals);
    }

    /** Le motif partagé — exposé pour que le partage soit vérifiable par identité. */
    public ShiftPattern getPattern() {
        return pattern;
    }

    private static int maskOfFirstDays(int count) {
        return (1 << Math.max(0, Math.min(7, count))) - 1;
    }

    /** Secondes réellement ouvertes entre l'origine et {@code time}, indisponibilités déduites. */
    public long workedSecondsBefore(long time) {
        return pattern.workedBefore(time) - lostUpTo(time);
    }

    /** Inverse : PREMIER instant auquel la ressource a été ouverte {@code workedSeconds} secondes. */
    public long timeAtWorkedSeconds(long workedSeconds) {
        return pattern.timeAtWorkedEarliest(workedSeconds + lostBeforeReaching(workedSeconds));
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
        return pattern.timeAtWorkedLatest(workedSeconds + lostBeforeReaching(workedSeconds));
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
     * {@code workSeconds} secondes pour l'avoir terminée au plus tard à {@code finishBy}.
     *
     * <p>
     * Quand la ressource n'a pas assez d'heures ouvertes avant {@code finishBy}, le solde demandé
     * deviendrait négatif ; on rend {@link #IMPOSSIBLE}, une date que l'appelant reconnaît, plutôt
     * qu'une date silencieusement fausse.
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
        return pattern.isOpenAt(time) && !isBlackedOutAt(time);
    }

    public boolean isBlackedOutAt(long time) {
        int index = Arrays.binarySearch(blackouts, time);
        if (index >= 0) {
            return index % 2 == 0; // pile sur un début de fenêtre
        }
        return (-index - 1) % 2 == 1; // à l'intérieur d'une fenêtre
    }

    // ************************************************************************
    // Indisponibilités datées
    // ************************************************************************

    /** Temps ouvert perdu dans les indisponibilités situées avant {@code time}. */
    private long lostUpTo(long time) {
        if (blackouts.length == 0) {
            return 0L;
        }
        int index = Arrays.binarySearch(blackouts, time);
        int position = index >= 0 ? index : -index - 1;
        long lost = lostBefore[position / 2];
        if (position % 2 == 1) {
            // On est à l'intérieur d'une fenêtre : compter la part déjà traversée.
            lost += pattern.workedBefore(time) - pattern.workedBefore(blackouts[position - 1]);
        }
        return lost;
    }

    /**
     * Temps ouvert perdu dans les fenêtres ENTIÈREMENT franchies pour atteindre ce compteur.
     *
     * <p>
     * Résolu par recherche et non par itération. Une version antérieure cherchait un point fixe —
     * « ajouter la perte, recalculer, recommencer » — et ne convergeait pas dès qu'UNE fenêtre
     * couvrait plusieurs périodes ouvrées : chaque itération n'avançait que d'une journée ouvrée
     * et le compteur de sécurité s'épuisait avant la traversée, rendant une date silencieusement
     * trop précoce. Une absence de metteur de cinq jours en couvre trois : le défaut était atteint
     * en production.
     */
    private long lostBeforeReaching(long workedSeconds) {
        if (blackouts.length == 0) {
            return 0L;
        }
        int low = 0;
        int high = blackouts.length / 2;
        while (low < high) {
            int mid = (low + high) >>> 1;
            // Le temps ouvert NET disponible avant la fenêtre `mid` est croissant en `mid`.
            long netBefore = pattern.workedBefore(blackouts[2 * mid]) - lostBefore[mid];
            if (netBefore <= workedSeconds) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return lostBefore[low];
    }
}
