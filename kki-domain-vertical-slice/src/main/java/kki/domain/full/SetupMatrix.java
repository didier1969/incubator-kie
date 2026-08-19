package kki.domain.full;

import java.util.Random;

/**
 * CPT-KKI-006 — matrice de mise en train ASYMÉTRIQUE, indexée sur le couple
 * <b>(article, passe)</b> et non sur l'article seul.
 *
 * <p>
 * Trois propriétés, dont deux corrigées après l'audit `REQ-KKI-015` :
 * <ul>
 * <li><b>Asymétrique</b> : passer de A vers B ne coûte pas ce que coûte B vers A. Le séquencement
 * sur une ressource est donc un problème de type voyageur de commerce asymétrique.</li>
 * <li><b>Nulle uniquement à clé IDENTIQUE</b>. La version précédente annulait la mise en train dès
 * que l'ARTICLE était le même, quelle que soit la passe — exactement ce que le concept interdit :
 * « setup(A→A) = 0 seulement pour une vraie transition article-à-article, <b>pas passe-à-passe du
 * même article</b> ». Conséquence de ce défaut : une révisite d'axe Z ne coûtait rien, alors que
 * CPT-KKI-005 exige que chaque passage ait sa propre durée de mise en train.</li>
 * <li><b>Durées réelles : 2 h à 48 h, ~16 h le cas courant</b>, les valeurs hautes rares. La
 * version précédente tirait 10 min à 1 h 40 — un ordre de grandeur trop court, ce qui
 * neutralisait au passage le piège du calendrier metteur (une mise en train de 1 h 40 ne bloque
 * jamais une machine tout un week-end).</li>
 * </ul>
 *
 * <p>
 * Stockée à plat en {@code int[]} : à 200 articles × 6 passes, 1200² entrées, soit 5,8 Mo —
 * accès en O(1) sans indirection ni autoboxing sur le chemin chaud.
 */
public final class SetupMatrix {

    private static final long HOUR = 3600L;
    /** Plancher observé. */
    private static final int MIN_SECONDS = (int) (2 * HOUR);
    /** Plafond observé, rare. */
    private static final int MAX_SECONDS = (int) (48 * HOUR);

    private final int passCount;
    private final int keyCount;
    private final int[] setupSeconds;

    public SetupMatrix(int articleCount, int passCount, long seed) {
        this.passCount = passCount;
        this.keyCount = articleCount * passCount;
        this.setupSeconds = new int[keyCount * keyCount];
        Random random = new Random(seed);
        for (int from = 0; from < keyCount; from++) {
            for (int to = 0; to < keyCount; to++) {
                // Zéro UNIQUEMENT si rien ne change : même article ET même passe.
                setupSeconds[from * keyCount + to] = from == to ? 0 : drawDuration(random);
            }
        }
    }

    /**
     * Distribution explicitement à trois régimes plutôt qu'un tirage uniforme : le cas courant
     * doit dominer et les 48 h rester exceptionnelles, ce qu'un uniforme sur [2 h, 48 h] ne donne
     * pas — il produirait une médiane à 25 h et 50 % des mises en train au-delà d'une journée.
     */
    private static int drawDuration(Random random) {
        double bucket = random.nextDouble();
        if (bucket < 0.12) {
            return uniform(random, 2 * HOUR, 8 * HOUR); // rapides
        }
        if (bucket < 0.97) {
            return uniform(random, 8 * HOUR, 24 * HOUR); // cas courant, médiane ~16 h
        }
        return uniform(random, 24 * HOUR, MAX_SECONDS); // lourdes, 3 %
    }

    private static int uniform(Random random, long lowSeconds, long highSeconds) {
        return (int) (lowSeconds + (long) (random.nextDouble() * (highSeconds - lowSeconds)));
    }

    public int keyOf(int articleId, int passIndex) {
        return articleId * passCount + Math.min(passIndex, passCount - 1);
    }

    /** Mise en train à payer sur une ressource pour passer de {@code fromKey} à {@code toKey}. */
    public long secondsBetween(int fromKey, int toKey) {
        return setupSeconds[fromKey * keyCount + toKey];
    }

    /**
     * Première opération d'une ressource : la machine part froide, donc mise en train pleine —
     * jamais zéro, quelle que soit la clé.
     */
    public long coldStartSeconds(int toKey) {
        int fromKey = (toKey + 1) % keyCount;
        return setupSeconds[fromKey * keyCount + toKey];
    }

    public int minSeconds() {
        return MIN_SECONDS;
    }

    public int maxSeconds() {
        return MAX_SECONDS;
    }
}
