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
    /** Nombre de metteurs en train. Dimension du domaine, balayée — jamais devinée. */
    public static int setterCount = 40;
    /** Nombre de technologies que chaque metteur sait régler. 1 = spécialiste, 5 = polyvalent. */
    public static int setterSkillBreadth = 2;
    /** Part des machines qui ne tournent PAS en continu (CPT-KKI-007 : « peut » tourner 24/7). */
    public static double nonContinuousMachineShare = 0.3;
    /** Nombre moyen de fenêtres de maintenance par machine sur l'horizon. */
    public static double maintenanceWindowsPerMachine = 1.5;

    public static JobShopSolution generate(int orderCount, long seed) {
        Random random = new Random(seed);
        SetupMatrix setupMatrix = new SetupMatrix(ARTICLE_COUNT, MAX_PASSES, seed);

        List<Machine> machines = new ArrayList<>(TECHNOLOGIES * LEVELS * MACHINES_PER_LEVEL);
        for (int technology = 0; technology < TECHNOLOGIES; technology++) {
            for (int level = 0; level < LEVELS; level++) {
                for (int k = 0; k < MACHINES_PER_LEVEL; k++) {
                    long id = machines.size();
                    // 60 CHF/h en bas d'échelle, +10 par palier, 150 en haut.
                    machines.add(new Machine(id, technology, level, 6_000L + 1_000L * level,
                            machineCalendarOf(random)));
                }
            }
        }

        // Les metteurs sont des ressources comme les autres : calendrier propre, et compétences.
        // Les technologies sont attribuées en rotation, ce qui garantit qu'aucune ne se retrouve
        // sans personne pour la régler — une couverture trouée rendrait l'instance infaisable
        // pour une raison qui n'a rien à voir avec l'ordonnancement.
        List<Setter> setters = new ArrayList<>(setterCount);
        for (int i = 0; i < setterCount; i++) {
            boolean[] mastered = new boolean[TECHNOLOGIES];
            for (int b = 0; b < Math.min(setterSkillBreadth, TECHNOLOGIES); b++) {
                mastered[(i + b) % TECHNOLOGIES] = true;
            }
            setters.add(new Setter(i, setterCalendarOf(random), mastered));
        }
        List<List<Setter>> settersByTechnology = new ArrayList<>(TECHNOLOGIES);
        for (int technology = 0; technology < TECHNOLOGIES; technology++) {
            List<Setter> competent = new ArrayList<>();
            for (Setter setter : setters) {
                if (setter.canSetUp(machines.get(technology * LEVELS * MACHINES_PER_LEVEL))) {
                    competent.add(setter);
                }
            }
            settersByTechnology.add(competent);
        }
        int[] nextSetterOfTechnology = new int[TECHNOLOGIES];

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
                // Un metteur COMPÉTENT pour cette machine, distribué en rotation pour ne pas
                // concentrer artificiellement la charge sur le premier de la liste.
                List<Setter> competent = settersByTechnology.get(technology);
                Setter setter = competent.get(
                        nextSetterOfTechnology[technology]++ % competent.size());
                chain.add(new Operation(operationId++, order, pass, duration,
                        technology, requiredLevel, setupKey,
                        compatibleByTechAndLevel.get(technology).get(requiredLevel),
                        machines.get((int) machineId), setter));
            }
            order.setOperations(chain);
            operations.addAll(chain);
            orders.add(order);
        }

        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>(orders));
        return new JobShopSolution(orders, operations, machines, setters, List.of(schedule),
                setupMatrix, ORIGIN_EPOCH);
    }

    /**
     * Calendrier d'une machine : continue, ou en deux équipes du lundi au vendredi. Dans les deux
     * cas des fenêtres de MAINTENANCE viennent s'y inscrire — ce sont des indisponibilités datées,
     * pas un mécanisme à part (CPT-KKI-004, 4e cas).
     */
    private static WorkCalendar machineCalendarOf(Random random) {
        WorkCalendar base = random.nextDouble() < nonContinuousMachineShare
                ? new WorkCalendar(5, 6L * 3600L, 16L * 3600L, new long[0])
                : WorkCalendar.CONTINUOUS;
        return base.withBlackouts(maintenanceWindows(random));
    }

    /** Arrêts de maintenance : quelques journées entières sur l'horizon, subies. */
    private static long[] maintenanceWindows(Random random) {
        int count = (int) Math.floor(maintenanceWindowsPerMachine)
                + (random.nextDouble() < maintenanceWindowsPerMachine % 1.0 ? 1 : 0);
        long[] windows = new long[count * 2];
        long spacing = HORIZON_SECONDS / Math.max(1, count);
        for (int i = 0; i < count; i++) {
            long start = i * spacing + (long) (random.nextDouble() * spacing * 0.6);
            windows[2 * i] = start;
            windows[2 * i + 1] = start + 24L * 3600L;
        }
        return windows;
    }

    /**
     * Calendrier d'un metteur : lundi-mercredi 8 h, plus d'éventuelles absences. La maladie de
     * CPT-KKI-007 ne demande aucun mécanisme dédié — c'est un trou dans SON calendrier.
     */
    private static WorkCalendar setterCalendarOf(Random random) {
        if (random.nextDouble() < 0.15) {
            long start = (long) (random.nextDouble() * HORIZON_SECONDS * 0.9);
            return WorkCalendar.MONDAY_TO_WEDNESDAY_8H
                    .withBlackouts(new long[] { start, start + 5L * 24 * 3600L });
        }
        return WorkCalendar.MONDAY_TO_WEDNESDAY_8H;
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
