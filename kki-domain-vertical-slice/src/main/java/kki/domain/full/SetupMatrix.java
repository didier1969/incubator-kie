package kki.domain.full;

import java.util.Random;

/**
 * CPT-KKI-006 — matrice de mise en train ASYMÉTRIQUE, indexée sur le couple
 * <b>(article, passe)</b> et non sur l'article seul.
 *
 * <p>
 * Deux propriétés qui ne sont pas des détails :
 * <ul>
 * <li><b>Asymétrique</b> : passer de A vers B ne coûte pas ce que coûte B vers A. Le
 * séquencement sur une ressource est donc un problème de type voyageur de commerce asymétrique,
 * pas un simple tri.</li>
 * <li><b>Nulle entre deux passages du même article</b> : enchaîner deux opérations du même
 * article sur la même machine ne coûte aucune mise en train. C'est la seule composante de coût
 * qu'on peut annuler entièrement plutôt que réduire — d'où l'intérêt de regrouper les articles
 * sur une ressource.</li>
 * </ul>
 *
 * <p>
 * Stockée à plat en {@code int[]} : à 200 articles × 6 passes, la matrice fait 1200² entrées,
 * soit 5,8 Mo — accès en O(1) sans indirection ni autoboxing sur le chemin chaud.
 */
public final class SetupMatrix {

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
                setupSeconds[from * keyCount + to] =
                        from / passCount == to / passCount ? 0 : 600 + random.nextInt(5400);
            }
        }
    }

    public int keyOf(int articleId, int passIndex) {
        return articleId * passCount + Math.min(passIndex, passCount - 1);
    }

    /** Mise en train à payer sur une ressource pour passer de {@code fromKey} à {@code toKey}. */
    public long secondsBetween(int fromKey, int toKey) {
        return setupSeconds[fromKey * keyCount + toKey];
    }

    /** Première opération d'une ressource : la machine part froide, mise en train pleine. */
    public long coldStartSeconds(int toKey) {
        return setupSeconds[toKey * keyCount + (toKey + 1) % keyCount];
    }
}
