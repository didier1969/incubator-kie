package kki.domain.full;

import java.util.Random;

/**
 * CPT-KKI-006 — la mise en train, décomposée en ses quatre postes réels.
 *
 * <p>
 * L'opérateur : « le temps de mise en train dépend de l'article précédent vs suivant sur la
 * technologie concernée. Respectivement d'un temps de préparation, de démontage des outils, de
 * montage des nouveaux outils et de réglage. Au total entre 2 h et 24 h selon très différents ou
 * pas. »
 *
 * <p>
 * Cette décomposition n'est pas décorative : elle EXPLIQUE les deux propriétés que le modèle
 * exigeait jusqu'ici par décret.
 * <ul>
 * <li><b>Pourquoi la matrice est asymétrique</b> — on démonte l'outillage du <i>sortant</i> et on
 * monte celui de l'<i>entrant</i>. Aller de A vers B démonte A et monte B ; l'inverse fait
 * l'inverse. L'asymétrie vient de là, pas d'un tirage aléatoire.</li>
 * <li><b>Pourquoi setup(A→A) = 0</b> — rien à démonter, rien à monter, rien à régler. Et pourquoi
 * ce zéro ne vaut QUE pour une clé (article, passe) identique : une autre passe du même article
 * change bien l'outillage.</li>
 * </ul>
 *
 * <p>
 * <b>Conséquence de structure.</b> Démontage et montage sont des propriétés d'une clé, pas d'une
 * paire : la matrice n'a plus besoin d'être stockée. Elle passe de {@code clés²} entrées — 5,8 Mo
 * à 1200 clés — à quelques tableaux linéaires, tout en restant en O(1) sur le chemin chaud et en
 * gagnant l'extensibilité : le jour où le nombre d'articles est multiplié par dix, rien ne change.
 */
public final class SetupMatrix {

    private static final long HOUR = 3600L;
    /** Plancher observé. */
    private static final int MIN_SECONDS = (int) (2 * HOUR);
    /** Plafond observé — 24 h, et non 48 comme la version précédente le supposait. */
    private static final int MAX_SECONDS = (int) (24 * HOUR);

    private final int passCount;
    private final int keyCount;

    /** Chercher les outils, la documentation, préparer le poste. Ne dépend que du poste. */
    private final int[] preparationByTechnology;
    /** Démonter l'outillage du SORTANT : propriété de la clé qui quitte le poste. */
    private final int[] teardownSeconds;
    /** Monter l'outillage de l'ENTRANT : propriété de la clé qui arrive. */
    private final int[] mountingSeconds;
    /** Difficulté propre de chaque clé au réglage, combinée par paire ci-dessous. */
    private final int[] adjustmentSeconds;

    public SetupMatrix(int articleCount, int passCount, long seed) {
        this(articleCount, passCount, seed, 5);
    }

    public SetupMatrix(int articleCount, int passCount, long seed, int technologyCount) {
        this.passCount = passCount;
        this.keyCount = articleCount * passCount;
        Random random = new Random(seed);

        this.preparationByTechnology = new int[Math.max(1, technologyCount)];
        for (int technology = 0; technology < preparationByTechnology.length; technology++) {
            preparationByTechnology[technology] = uniform(random, HOUR / 2, 3 * HOUR / 2);
        }

        this.teardownSeconds = new int[keyCount];
        this.mountingSeconds = new int[keyCount];
        this.adjustmentSeconds = new int[keyCount];
        for (int key = 0; key < keyCount; key++) {
            teardownSeconds[key] = uniform(random, HOUR / 4, 4 * HOUR);
            mountingSeconds[key] = uniform(random, HOUR / 2, 6 * HOUR);
            adjustmentSeconds[key] = uniform(random, HOUR / 4, 6 * HOUR);
        }
    }

    private static int uniform(Random random, long lowSeconds, long highSeconds) {
        return (int) (lowSeconds + (long) (random.nextDouble() * (highSeconds - lowSeconds)));
    }

    public int keyOf(int articleId, int passIndex) {
        return articleId * passCount + Math.min(passIndex, passCount - 1);
    }

    /**
     * Mise en train à payer sur un poste de cette technologie pour passer de {@code fromKey} à
     * {@code toKey}.
     *
     * <p>
     * Le réglage combine la difficulté des DEUX clés : « selon très différents ou pas ». Deux
     * articles proches partagent une part de leur mise au point ; deux articles éloignés
     * additionnent leurs difficultés. La proximité est ici prise sur la distance des clés, faute
     * de mieux — une vraie nomenclature dirait laquelle.
     */
    public long secondsBetween(int fromKey, int toKey, int technology) {
        if (fromKey == toKey) {
            return 0L; // rien ne change : ni démontage, ni montage, ni réglage
        }
        long preparation = preparationByTechnology[technology % preparationByTechnology.length];
        long teardown = teardownSeconds[fromKey];
        long mounting = mountingSeconds[toKey];
        long adjustment = adjustment(fromKey, toKey);
        return clamp(preparation + teardown + mounting + adjustment);
    }

    /** Compatibilité : technologie inconnue de l'appelant, préparation moyenne. */
    public long secondsBetween(int fromKey, int toKey) {
        return secondsBetween(fromKey, toKey, 0);
    }

    private long adjustment(int fromKey, int toKey) {
        // Deux clés à moins d'une longueur de gamme l'une de l'autre sont deux passes du MÊME
        // article : la mise au point y est largement commune. Au-delà, on change d'article et
        // tout est à refaire.
        boolean sameArticle = Math.abs(fromKey - toKey) < passCount;
        long combined = (adjustmentSeconds[fromKey] + adjustmentSeconds[toKey]) / 2L;
        return sameArticle ? combined / 3L : combined;
    }

    private static long clamp(long seconds) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
    }

    /**
     * Première opération d'un poste : il part froid, donc mise en train pleine — jamais zéro,
     * quelle que soit la clé.
     */
    public long coldStartSeconds(int toKey) {
        int fromKey = (toKey + 1 + keyCount / 2) % keyCount;
        return secondsBetween(fromKey, toKey, 0);
    }

    public int minSeconds() {
        return MIN_SECONDS;
    }

    public int maxSeconds() {
        return MAX_SECONDS;
    }
}
