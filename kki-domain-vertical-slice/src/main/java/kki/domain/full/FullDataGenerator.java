package kki.domain.full;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Instance du domaine COMPLET. Aucun mécanisme retiré.
 *
 * <p>
 * Paramètres calés sur PIL-KKI-004 et CPT-KKI-008 : 1000 machines en 5 technologies × 10
 * sous-types × 20 machines, coût horaire de 60 à 150 CHF par paliers de 10 le long de l'échelle,
 * chaînes de 1 à 6 passes, 200 articles, horizon 6 mois. La durée d'opération est calée sur
 * l'intervalle 5–21 h établi en REQ-KKI-010 : c'est le seul qui rende cohérents 5000 ordres,
 * ~4,5 opérations par ordre, 1000 machines et 6 mois.
 *
 * <p>
 * Deux choix qui font l'instance et qu'il faut voir :
 * <ul>
 * <li><b>Axe Z réel</b> — une passe sur deux revient sur une machine déjà visitée par le même
 * ordre, au lieu d'attendre que le hasard le fasse.</li>
 * <li><b>Demande asymétrique sur l'échelle</b> — les articles simples dominent, donc le bas de
 * l'échelle est sous tension et le haut sert de soupape. C'est le régime que le détecteur
 * identifie comme porteur de goulots.</li>
 * </ul>
 */
public final class FullDataGenerator {

    /** Un lundi 00:00, pour que le calendrier metteur ait un sens. */
    public static final long ORIGIN_EPOCH = 1_700_006_400L - (1_700_006_400L % 604_800L);

    private static final long HORIZON_SECONDS = 6L * 30 * 24 * 3600;
    private static final int ARTICLE_COUNT = 200;
    private static final int MAX_PASSES = 6;
    private static final int TECHNOLOGIES = 5;
    private static final int LEVELS = 10;
    private static final int MACHINES_PER_LEVEL = 20;
    /** 5 h à 21 h par opération (REQ-KKI-010). */
    private static final long MIN_DURATION = 5L * 3600L;
    private static final long DURATION_SPREAD = 16L * 3600L;

    private FullDataGenerator() {
    }

    /** Exposant de la loi sur le niveau technologique requis. 2 = les articles simples dominent. */
    public static double levelDemandSkew = 2.0;

    public static JobShopSolution generate(int orderCount, long seed) {
        Random random = new Random(seed);
        SetupMatrix setupMatrix = new SetupMatrix(ARTICLE_COUNT, MAX_PASSES, seed);

        List<Machine> machines = new ArrayList<>(TECHNOLOGIES * LEVELS * MACHINES_PER_LEVEL);
        for (int technology = 0; technology < TECHNOLOGIES; technology++) {
            for (int level = 0; level < LEVELS; level++) {
                for (int k = 0; k < MACHINES_PER_LEVEL; k++) {
                    long id = machines.size();
                    // 60 CHF/h en bas d'échelle, +10 par palier, 150 en haut.
                    machines.add(new Machine(id, technology, level, 6_000L + 1_000L * level));
                }
            }
        }

        // MÉMOÏSATION — la plage de valeurs de M2 ne dépend que du couple (technologie, niveau) :
        // 50 listes partagées par les 17 515 opérations, construites une fois. Compatibilité
        // ASCENDANTE : les niveaux égaux ou supérieurs de la technologie, jamais en dessous.
        List<List<List<Machine>>> compatibleByTechAndLevel = new ArrayList<>(TECHNOLOGIES);
        for (int technology = 0; technology < TECHNOLOGIES; technology++) {
            List<List<Machine>> byLevel = new ArrayList<>(LEVELS);
            for (int level = 0; level < LEVELS; level++) {
                List<Machine> compatible = new ArrayList<>();
                for (Machine machine : machines) {
                    if (machine.canRun(technology, level)) {
                        compatible.add(machine);
                    }
                }
                byLevel.add(List.copyOf(compatible));
            }
            compatibleByTechAndLevel.add(byLevel);
        }

        List<Order> orders = new ArrayList<>(orderCount);
        List<Operation> operations = new ArrayList<>();
        long operationId = 0;
        for (long o = 0; o < orderCount; o++) {
            int articleId = random.nextInt(ARTICLE_COUNT);
            int priorityWeight = 1 + random.nextInt(5);
            long due = ORIGIN_EPOCH + (long) (random.nextDouble() * HORIZON_SECONDS);
            Order.FreezeLevel freeze = freezeLevelOf(due, random);
            // Plan de référence : la date due elle-même sert de dernier plan publié — c'est la
            // référence la plus défavorable au solveur, donc la plus honnête pour mesurer.
            Order order = new Order(o, articleId, priorityWeight, due, freeze, due);

            int passCount = 1 + random.nextInt(MAX_PASSES);
            List<Operation> chain = new ArrayList<>(passCount);
            List<Long> visited = new ArrayList<>();
            for (int pass = 0; pass < passCount; pass++) {
                long duration = MIN_DURATION + (long) (random.nextDouble() * DURATION_SPREAD);
                int technology = random.nextInt(TECHNOLOGIES);
                int requiredLevel = skewedLevel(random);
                long machineId;
                // Axe Z : une passe sur deux revient sur une machine déjà visitée par cet ordre.
                if (!visited.isEmpty() && random.nextBoolean()) {
                    machineId = visited.get(random.nextInt(visited.size()));
                    Machine reused = machines.get((int) machineId);
                    technology = reused.getTechnology();
                    requiredLevel = reused.getLevel();
                } else {
                    machineId = (long) technology * LEVELS * MACHINES_PER_LEVEL
                            + (long) requiredLevel * MACHINES_PER_LEVEL
                            + random.nextInt(MACHINES_PER_LEVEL);
                    visited.add(machineId);
                }
                int setupKey = setupMatrix.keyOf(articleId, pass);
                chain.add(new Operation(operationId++, order, pass, duration,
                        technology, requiredLevel, setupKey,
                        compatibleByTechAndLevel.get(technology).get(requiredLevel),
                        machines.get((int) machineId)));
            }
            order.setOperations(chain);
            operations.addAll(chain);
            orders.add(order);
        }

        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>(orders));
        return new JobShopSolution(orders, operations, machines, List.of(schedule), setupMatrix,
                ORIGIN_EPOCH);
    }

    /**
     * Trois paliers : verrou dur sur ce qui est déjà lancé (première semaine), gel souple sur
     * l'horizon de trois semaines, libre au-delà.
     */
    private static Order.FreezeLevel freezeLevelOf(long due, Random random) {
        long horizonSeconds = due - ORIGIN_EPOCH;
        if (horizonSeconds < 7L * 24 * 3600 && random.nextDouble() < 0.5) {
            return Order.FreezeLevel.HARD;
        }
        if (horizonSeconds < 21L * 24 * 3600) {
            return Order.FreezeLevel.SOFT;
        }
        return Order.FreezeLevel.FREE;
    }

    /** Demande en 1/rang² sur l'échelle : les articles simples dominent largement. */
    private static int skewedLevel(Random random) {
        double u = random.nextDouble();
        double total = 0.0;
        for (int level = 0; level < LEVELS; level++) {
            total += 1.0 / Math.pow(level + 1.0, levelDemandSkew);
        }
        double cumulative = 0.0;
        for (int level = 0; level < LEVELS; level++) {
            cumulative += 1.0 / Math.pow(level + 1.0, levelDemandSkew) / total;
            if (u <= cumulative) {
                return level;
            }
        }
        return LEVELS - 1;
    }
}
