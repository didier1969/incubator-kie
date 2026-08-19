package kki.domain.solver;

/**
 * REQ-KKI-011 — le solveur lit le régime de l'instance et choisit sa stratégie, au lieu qu'un
 * humain la choisisse pour lui.
 *
 * <p>
 * Raison d'être (DEC-KKI-005) : le livrable est un solveur, pas un plan. Les trois grandeurs
 * qui commandent la stratégie varient sur deux ordres de grandeur dans le domaine mesuré — la
 * part d'avance dans le coût va de 99,3 % à 0 %, la concentration de la criticité de 13,8 % à
 * 64,2 %. Tant qu'elles sont des questions posées au client, il n'y a pas de produit. Ici
 * elles sont mesurées sur l'instance, en un balayage chacune, avant tout solve.
 *
 * <p>
 * <b>Aucun seuil arbitraire.</b> Les deux décisions reposent sur des règles qui n'introduisent
 * pas de constante à calibrer :
 *
 * <ul>
 * <li><b>Datation ou séquencement</b> — règle mécaniste. Retarder un ordre ne peut réduire un
 * coût d'avance que s'il reste de la place devant : si le plan déborde déjà de l'horizon, tout
 * est en retard et il n'y a rien à retarder. Les deux conditions sont donc nécessaires
 * ensemble, et chacune sans l'autre se trompe à une borne.</li>
 * <li><b>Machinerie de goulot</b> — concentration rapportée à la répartition PARFAITEMENT
 * UNIFORME. Ce n'est pas une constante à calibrer mais la définition mathématique de « rien
 * n'est concentré » : le décile supérieur porte 10 % du total. Le rapport vaut donc 1 à
 * l'uniformité exacte et croît avec la concentration, à toute échelle et tout nombre de
 * machines.</li>
 * </ul>
 *
 * <p>
 * <b>Ce que ce détecteur a appris en se trompant.</b> Une première version normalisait la
 * criticité par la CHARGE des mêmes machines, pour n'introduire aucune constante. Mesuré : ce
 * rapport vaut 1,0003 sur l'instance la plus concentrée du domaine — la criticité y suit
 * exactement la charge. La normalisation répondait donc à « certaines machines sont-elles
 * critiques SANS être chargées ? », question rare et étrangère à la décision. Or ce qui
 * commande de traiter les ressources dans l'ordre, c'est précisément qu'une minorité porte
 * l'essentiel de la charge — le cas que l'ancien rapport annulait par construction. Le rapport
 * criticité/charge reste calculé et publié : informatif, mais il ne décide pas.
 */
public final class RegimeDetector {

    /**
     * Le décile supérieur doit porter au moins le double de sa part proportionnelle pour qu'il
     * y ait une cible à attaquer. Le plancher statistique n'est pas 1 mais ~1,4 : même une
     * affectation uniformément aléatoire concentre un peu, à cette densité d'opérations par
     * machine (mesuré 13,8 % pour le décile supérieur). Le seuil de 2 est donc franchement
     * au-dessus du bruit, et le domaine mesuré ne contient rien entre 1,4 et 6,4 — la
     * discrimination ne se joue pas sur la valeur exacte.
     */
    private static final double BOTTLENECK_CONCENTRATION_THRESHOLD = 2.0;

    /** Part du total portée par le décile supérieur quand rien n'est concentré. */
    private static final double UNIFORM_TOP_DECILE_SHARE = 0.10;

    /** Part d'avance au-delà de laquelle le séquencement adresse moins de la moitié du coût. */
    private static final double EARLINESS_DOMINANCE = 0.5;

    private RegimeDetector() {
    }

    /** Stratégie que l'instance appelle. */
    public enum Strategy {
        /**
         * Le coût est dominé par l'avance et le plan tient dans l'horizon : on le réduit en
         * RETARDANT les ordres, pas en les réordonnant. À séquence fixée, la datation optimale
         * est un problème convexe résoluble exactement — chercher des séquences ici, c'est
         * dépenser un budget sur la fraction minoritaire du coût.
         */
        DATATION,
        /**
         * Le coût est dominé par le retard : il n'existe pas de datation qui le fasse baisser,
         * seule une meilleure séquence le peut.
         */
        SEQUENCEMENT
    }

    /**
     * @param earlinessShare part du coût due aux ordres finissant trop tôt
     * @param makespanOverHorizon fin du plan naïf rapportée à l'échéance la plus lointaine
     * @param utilisation travail total par machine rapporté à l'horizon
     * @param concentrationRatio concentration de la criticité rapportée à celle de la charge
     */
    public record Regime(double earlinessShare, double makespanOverHorizon, double utilisation,
            double criticalityConcentration, double criticalityOverLoad, Strategy strategy,
            boolean bottleneckMachineryWorthwhile) {

        public String describe(String label) {
            return String.format(
                    "regime[%s] strategy=%s bottleneck_machinery=%s earliness_share=%.1f%% "
                            + "makespan_over_horizon=%.2f utilisation=%.2f%% concentration=%.2f "
                            + "criticality_over_load=%.2f%n",
                    label, strategy, bottleneckMachineryWorthwhile ? "ON" : "OFF",
                    100.0 * earlinessShare, makespanOverHorizon, 100.0 * utilisation,
                    criticalityConcentration, criticalityOverLoad);
        }
    }

    public static Regime detect(SlackReporter reporter) {
        double earlinessShare = reporter.costSplit().earlinessShare();
        long horizon = Math.max(1L, reporter.horizonSeconds());
        double makespanOverHorizon = (double) reporter.makespanSeconds() / horizon;
        double utilisation = (double) reporter.totalWorkSeconds()
                / reporter.operationCountByMachine().length / horizon;
        long[] criticalByMachine = reporter.criticalOperationCountByMachine();
        double concentration =
                SlackReporter.topShareOf(criticalByMachine, 10) / UNIFORM_TOP_DECILE_SHARE;
        double criticalityOverLoad =
                concentrationRatio(criticalByMachine, reporter.operationCountByMachine());

        // Les deux conditions sont conjointes, et c'est le fond de la règle : une part d'avance
        // élevée dit qu'il y a quelque chose à gagner en retardant, un makespan qui tient dans
        // l'horizon dit que c'est physiquement possible. L'une sans l'autre se trompe.
        Strategy strategy = earlinessShare > EARLINESS_DOMINANCE && makespanOverHorizon <= 1.0
                ? Strategy.DATATION
                : Strategy.SEQUENCEMENT;

        return new Regime(earlinessShare, makespanOverHorizon, utilisation, concentration,
                criticalityOverLoad, strategy, concentration >= BOTTLENECK_CONCENTRATION_THRESHOLD);
    }

    /**
     * Concentration de {@code observed} rapportée à celle de {@code baseline}, mesurée sur le
     * décile supérieur. Vaut ~1 quand l'observé ne fait que suivre la référence.
     *
     * <p>
     * C'est ce rapport, et non la concentration brute, qui distingue un goulot d'une machine
     * simplement chargée : une ressource qui porte beaucoup d'opérations en portera
     * mécaniquement beaucoup de critiques sans être pour autant le point à attaquer.
     */
    public static double concentrationRatio(long[] observed, long[] baseline) {
        double baselineShare = SlackReporter.topShareOf(baseline, 10);
        if (baselineShare <= 0.0) {
            return 0.0;
        }
        return SlackReporter.topShareOf(observed, 10) / baselineShare;
    }
}
