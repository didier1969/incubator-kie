package kki.domain.full;

import java.util.Comparator;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * Banc de qualification du domaine — `DEC-KKI-005` : « la cible doit devenir une ENVELOPPE : elle
 * tient sur une FAMILLE d'instances couvrant le domaine, pas sur un point. »
 *
 * <p>
 * Toutes les mesures publiées jusqu'ici portent sur UNE instance, et cette instance est saturée :
 * 5000 ordres sur 5000 en retard, un terme de coût à 1e15 contre 1e7 à 1e10 pour tous les autres.
 * Dans ce régime, aucun levier autre que le séquencement ne peut peser, et un levier qui ne pèse
 * pas n'est pas départageable d'un levier inutile. Ce banc sert à sortir de ce point unique.
 *
 * <pre>
 *   threshold [ordres]      seuil de faisabilité en nombre de metteurs, par bisection
 *   sweep     [ordres]      dimensions qui changent le RÉGIME, une à la fois
 *   solve     [ordres] [s]  M3 contre M4 à budget égal, sur un point NON saturé
 * </pre>
 */
public final class ParameterSweepRunner {

    /** Au-dessous de cette part d'ordres en retard, l'instance n'est plus saturée. */
    private static final double SATURATION_LATE_SHARE = 0.20;

    private ParameterSweepRunner() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "threshold";
        int orderCount = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        switch (command) {
            case "threshold" -> threshold(orderCount);
            case "sweep" -> sweep(orderCount);
            case "combined" -> combined(orderCount);
            case "solve" -> solve(orderCount, args.length > 2 ? Long.parseLong(args[2]) : 60L);
            default -> throw new IllegalArgumentException("commande inconnue : " + command);
        }
    }

    // ************************************************************************
    // Seuil de faisabilité — la prédiction arithmétique est ce qu'on cherche à FALSIFIER
    // ************************************************************************

    /**
     * Cherche le plus petit nombre de metteurs pour lequel l'instance sort de la saturation.
     *
     * <p>
     * Échelle géométrique puis bisection sur l'intervalle de franchissement : huit générations à
     * froid suffisent là où une grille dense en demanderait des dizaines, pour une réponse plus
     * précise. Aucune résolution — c'est la faisabilité du plan de départ qui est mesurée, pas la
     * qualité d'un solveur.
     */
    private static void threshold(int orderCount) {
        FullDataGenerator.reset();
        Regime reference = regimeAt(orderCount, FullDataGenerator.setterCount);
        System.out.printf("capacity_arithmetic setup_demand_h=%.0f setter_supply_h=%.0f ratio=%.1f%n",
                reference.setupDemandHours, reference.setterSupplyHours,
                reference.setupDemandHours / Math.max(1.0, reference.setterSupplyHours));

        int low = FullDataGenerator.setterCount;
        int high = low;
        Regime highRegime = reference;
        System.out.print(reference.describe());
        while (highRegime.lateShare > SATURATION_LATE_SHARE && high <= 8192) {
            low = high;
            high *= 2;
            highRegime = regimeAt(orderCount, high);
            System.out.print(highRegime.describe());
        }
        if (highRegime.lateShare > SATURATION_LATE_SHARE) {
            System.out.printf("threshold NOT_FOUND up_to_setters=%d%n", high);
            return;
        }
        // Bisection sur l'intervalle franchi.
        while (high - low > Math.max(1, low / 10)) {
            int middle = (low + high) / 2;
            Regime regime = regimeAt(orderCount, middle);
            System.out.print(regime.describe());
            if (regime.lateShare > SATURATION_LATE_SHARE) {
                low = middle;
            } else {
                high = middle;
            }
        }
        System.out.printf("threshold setters=%d orders=%d predicted_by_arithmetic=%.0f%n",
                high, orderCount,
                FullDataGenerator.setterCount * reference.setupDemandHours
                        / Math.max(1.0, reference.setterSupplyHours));
    }

    // ************************************************************************
    // Balayage des dimensions qui changent le régime
    // ************************************************************************

    /**
     * Ne balaye QUE les dimensions qui changent la contrainte qui mord. {@code articleCount},
     * {@code technologies} et {@code levels} sont exposées en paramètres — le plan exige zéro
     * constante cachée — mais NON balayées : elles redimensionnent l'instance sans déplacer le
     * goulot, et les inclure dépenserait le budget sans rien apprendre. Le dire ici plutôt que de
     * les omettre en silence : une couverture tronquée sans mention se lit comme une couverture
     * complète.
     */
    private static void sweep(int orderCount) {
        sweepInt("setter_working_days", orderCount, new int[] { 3, 5, 7 },
                value -> FullDataGenerator.setterWorkingDays = value);
        sweepInt("setter_window_hours", orderCount, new int[] { 8, 16, 24 },
                value -> {
                    FullDataGenerator.setterWindowSeconds = value * 3600L;
                    FullDataGenerator.setterWindowStartSeconds = value >= 24 ? 0L : 8L * 3600L;
                });
        sweepInt("setter_count", orderCount, new int[] { 40, 120, 360, 1080 },
                value -> FullDataGenerator.setterCount = value);
        sweepInt("setter_skill_breadth", orderCount, new int[] { 1, 2, 3, 5 },
                value -> FullDataGenerator.setterSkillBreadth = value);
        sweepInt("tooling_copies_per_type", orderCount, new int[] { 1, 2, 4, 8 },
                value -> FullDataGenerator.toolingCopiesPerType = value);
        sweepDouble("non_continuous_machine_share", orderCount, new double[] { 0.0, 0.3, 0.6, 1.0 },
                value -> FullDataGenerator.nonContinuousMachineShare = value);
        sweepDouble("level_demand_skew", orderCount, new double[] { 0.0, 1.0, 2.0, 3.0 },
                value -> FullDataGenerator.levelDemandSkew = value);
        sweepDouble("tooling_requirement_share", orderCount, new double[] { 0.0, 0.4, 0.8, 1.0 },
                value -> FullDataGenerator.toolingRequirementShare = value);
    }

    private static void sweepInt(String name, int orderCount, int[] values, IntConsumer setter) {
        for (int value : values) {
            FullDataGenerator.reset();
            setter.accept(value);
            System.out.print(measure(orderCount).describe(name + "=" + value));
        }
        FullDataGenerator.reset();
    }

    private static void sweepDouble(String name, int orderCount, double[] values,
            DoubleConsumer setter) {
        for (double value : values) {
            FullDataGenerator.reset();
            setter.accept(value);
            System.out.print(measure(orderCount).describe(name + "=" + value));
        }
        FullDataGenerator.reset();
    }

    /**
     * Points COMBINÉS. Le balayage une-dimension-à-la-fois ne sort jamais de la saturation, et
     * ce n'est pas un défaut du balayage : les facteurs se composent. À 40 metteurs le goulot est
     * le metteur ; dès qu'on en met assez, il passe aux machines de bas d'échelle, que la loi de
     * demande y concentre. Desserrer l'un fait apparaître l'autre, donc aucune coupe unique ne
     * peut montrer un régime non saturé.
     *
     * <p>
     * Ce que ces points mesurent n'est PAS « le bon réglage de l'atelier » : c'est de quelles
     * hypothèses dépend la faisabilité du carnet annoncé (5000 ordres / 6 mois). Les valeurs que
     * l'opérateur a réellement données — 5000 ordres, 1 à 6 passes, 1000 machines, mise en train
     * de 2 à 48 h médiane 16 h, metteur lundi-mercredi ~8 h — sont arithmétiquement
     * incompatibles entre elles : 17 489 mises en train de 16 h demandent 279 824 h de metteur,
     * quand 40 metteurs à 24 h ouvrées par semaine n'en offrent que 24 960 sur l'horizon. Il
     * manque donc une donnée de terrain, et ces points disent laquelle.
     */
    private static void combined(int orderCount) {
        FullDataGenerator.reset();
        System.out.print(measure(orderCount).describe("reference"));

        FullDataGenerator.reset();
        FullDataGenerator.setterCount = 1000;
        System.out.print(measure(orderCount).describe("setters=1000"));

        FullDataGenerator.reset();
        FullDataGenerator.setterCount = 1000;
        FullDataGenerator.levelDemandSkew = 0.0;
        System.out.print(measure(orderCount).describe("setters=1000+uniform_levels"));

        FullDataGenerator.reset();
        FullDataGenerator.setterCount = 1000;
        FullDataGenerator.levelDemandSkew = 0.0;
        FullDataGenerator.setterWorkingDays = 7;
        FullDataGenerator.setterWindowSeconds = 24L * 3600L;
        FullDataGenerator.setterWindowStartSeconds = 0L;
        System.out.print(measure(orderCount).describe("setters=1000+uniform+setter_24_7"));

        FullDataGenerator.reset();
        FullDataGenerator.setterCount = 1000;
        FullDataGenerator.levelDemandSkew = 0.0;
        FullDataGenerator.setterWorkingDays = 7;
        FullDataGenerator.setterWindowSeconds = 24L * 3600L;
        FullDataGenerator.setterWindowStartSeconds = 0L;
        for (int orders : new int[] { orderCount / 2, orderCount / 5, orderCount / 10 }) {
            System.out.print(measure(orders).describe("all_relaxed+orders=" + orders));
        }
        FullDataGenerator.reset();
    }

    // ************************************************************************
    // Un point NON saturé résolu pour de vrai
    // ************************************************************************

    /**
     * M3 contre M4 à budget de temps égal, sur une instance qui n'est PAS saturée. C'est la seule
     * mesure qui rende interprétable l'écart entre les deux : dans le régime saturé, le retard
     * écrase tout d'un facteur 1e5 et un levier de ressource ne peut pas s'y voir.
     */
    private static void solve(int orderCount, long seconds) throws Exception {
        // Le point NON saturé identifié par `combined` : c'est le seul où un levier autre que le
        // séquencement peut se voir. Les trois desserrages sont ceux qui composent la bascule —
        // ni un réglage d'atelier ni un choix de commodité, mais la configuration minimale où le
        // carnet devient physiquement réalisable.
        FullDataGenerator.reset();
        FullDataGenerator.setterCount = 1000;
        FullDataGenerator.levelDemandSkew = 0.0;
        FullDataGenerator.setterWorkingDays = 7;
        FullDataGenerator.setterWindowSeconds = 24L * 3600L;
        FullDataGenerator.setterWindowStartSeconds = 0L;
        Regime regime = measure(orderCount);
        System.out.print(regime.describe("solve_point"));
        if (regime.lateShare > SATURATION_LATE_SHARE) {
            System.out.printf("WARNING point encore saturé (late_share=%.2f) : la comparaison"
                    + " M3/M4 n'y sera pas plus interprétable qu'avant%n", regime.lateShare);
        }
        for (FullRunner.Variant variant : new FullRunner.Variant[] {
                FullRunner.Variant.M3, FullRunner.Variant.M4 }) {
            FullRunner.main(new String[] { Integer.toString(orderCount), Long.toString(seconds),
                    variant.name() });
        }
    }

    // ************************************************************************
    // Mesure d'un point — à froid, sans résolution
    // ************************************************************************

    private static Regime regimeAt(int orderCount, int setterCount) {
        FullDataGenerator.reset();
        FullDataGenerator.setterCount = setterCount;
        return measure(orderCount);
    }

    private static Regime measure(int orderCount) {
        JobShopSolution problem = FullDataGenerator.generate(orderCount, 42L);
        // Même référence honnête que le banc principal : « plus urgent d'abord ». Mesurer un
        // régime sur un ordre de génération aléatoire décrirait le hasard, pas l'atelier.
        problem.getScheduleList().get(0).getOrderSequence()
                .sort(Comparator.comparingLong(Order::getDueEpochSec));
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(problem);
        FullScoreCalculator.ColdSweep cold = calculator.coldSweep();
        FullScoreCalculator.BackwardSweep backward = calculator.backwardSweep();

        long late = problem.getOrderList().stream()
                .filter(order -> cold.completions()[(int) order.getId()] > order.getDueEpochSec())
                .count();
        double setupDemandHours = 0.0;
        for (Operation op : problem.getOperationList()) {
            setupDemandHours += 16.0; // médiane de la matrice, suffisant pour un ordre de grandeur
        }
        double weeks = FullDataGenerator.horizonSeconds / 604_800.0;
        double setterSupplyHours = FullDataGenerator.setterCount * 24.0 * weeks;

        return new Regime(FullDataGenerator.setterCount, problem.getOperationList().size(),
                (double) late / problem.getOrderList().size(), -cold.soft(), cold.tardiness(),
                cold.earliness(), cold.setter(), cold.machineIdle(),
                cold.toolingBorrowing(), cold.toolingBound(), backward.opsWithSlack(),
                backward.ordersWithSlack(), setupDemandHours, setterSupplyHours);
    }

    private record Regime(int setterCount, int operations, double lateShare, long totalCents,
            long tardiness, long earliness, long setter, long machineIdle,
            long toolingBorrowing, long toolingBound, long opsWithSlack, long ordersWithSlack,
            double setupDemandHours, double setterSupplyHours) {

        String describe() {
            return describe("setters=" + setterCount);
        }

        String describe(String label) {
            return String.format(
                    "regime[%s] ops=%d late_share=%.3f total_chf=%.3e tardiness_share=%.5f"
                            + " earliness_share=%.5f physical_share=%.5f tooling_binding=%.1f%%"
                            + " ops_with_slack=%d orders_with_slack=%d%n",
                    label, operations, lateShare, totalCents / 100.0,
                    (double) tardiness / Math.max(1L, totalCents),
                    (double) earliness / Math.max(1L, totalCents),
                    (double) (setter + machineIdle) / Math.max(1L, totalCents),
                    100.0 * toolingBound / Math.max(1L, toolingBorrowing),
                    opsWithSlack, ordersWithSlack);
        }
    }
}
