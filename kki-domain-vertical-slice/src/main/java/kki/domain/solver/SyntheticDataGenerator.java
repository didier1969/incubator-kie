package kki.domain.solver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import kki.domain.Machine;
import kki.domain.Operation;
import kki.domain.Order;
import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * REQ-KKI-006 (réécrit) — 5000 ordres (corrigé par l'opérateur). La machine
 * de chaque opération est un fait FIXE assigné ici (choix machine = CPT-KKI-008,
 * différé) ; seule la position X des ordres (Schedule.orderSequence) est
 * une décision de planification — la liste démarre vide, la construction
 * heuristique la peuple.
 */
public final class SyntheticDataGenerator {

    private static final long HORIZON_SECONDS = 6L * 30 * 24 * 3600; // ~6 mois

    /** Origine absolue partagée avec le balayage du score (même base de temps). */
    public static final long BASE_EPOCH = 1_700_000_000L;

    private SyntheticDataGenerator() {
    }

    /** Comportement historique : durées 30 min–2 h, machine tirée uniformément. */
    public static VerticalSliceSolution generate(int orderCount, int machineCount, long seed) {
        return generate(orderCount, machineCount, seed, 1.0, machineCount, 0.0);
    }

    /**
     * REQ-KKI-010 — deux mécanismes que le générateur historique ne portait pas, et dont
     * l'absence rendait toute mesure de charge et de criticité ininterprétable. L'opérateur
     * a désigné les deux (2026-08-19).
     *
     * <p>
     * Ils agissent sur des grandeurs différentes, et c'est pour ça qu'il faut les deux :
     *
     * <ul>
     * <li><b>durationScale</b> fixe le TAUX D'OCCUPATION moyen. Aux durées historiques
     * (30 min–2 h) et 1000 machines sur 6 mois, l'atelier tourne à 0,65 % — un atelier vide,
     * où 99,3 % du coût est de l'avance. C'est la charge qui décide si le problème est un
     * problème de séquencement ou de datation.</li>
     * <li><b>capabilityClassSize</b> fixe la CONCENTRATION de la charge. Une machine tirée
     * uniformément parmi 1000 garantit une criticité uniforme par construction — ce qui
     * invalidait le verdict H2. En réalité une opération ne va que sur les machines d'une
     * classe de capacité.</li>
     * <li><b>demandSkew</b> : exposant d'une loi de Zipf sur la demande adressée aux classes.
     * 0 = toutes les classes également demandées ; 1 = la classe la plus demandée l'est
     * {@code classCount} fois plus que la moins demandée. Sans ce paramètre, découper en
     * classes de taille égale et les demander uniformément reproduirait exactement
     * l'uniformité qu'on cherche à éviter.</li>
     * </ul>
     *
     * <p>
     * Les valeurs de ces trois paramètres sont des HYPOTHÈSES tant qu'un extrait de
     * production n'est pas disponible. Elles sont donc balayées, jamais fixées.
     */
    public static VerticalSliceSolution generate(int orderCount, int machineCount, long seed,
            double durationScale, int capabilityClassSize, double demandSkew) {
        Random random = new Random(seed);

        List<Machine> machineList = new ArrayList<>(machineCount);
        for (long m = 0; m < machineCount; m++) {
            machineList.add(new Machine(m, 1000));
        }
        int classSize = Math.max(1, Math.min(capabilityClassSize, machineCount));
        int classCount = Math.max(1, machineCount / classSize);
        double[] cumulativeClassWeight = buildZipfCumulative(classCount, demandSkew);

        List<Order> orderList = new ArrayList<>(orderCount);
        List<Operation> operationList = new ArrayList<>();
        long operationId = 0;
        for (long o = 0; o < orderCount; o++) {
            long articleId = random.nextInt(200);
            int priorityWeight = 1 + random.nextInt(5);
            long requiredDueEpochSec = BASE_EPOCH + (long) (random.nextDouble() * HORIZON_SECONDS);
            Order order = new Order(o, articleId, priorityWeight, requiredDueEpochSec);

            int opCount = 3 + random.nextInt(4); // 3..6
            List<Operation> orderOps = new ArrayList<>(opCount);
            for (int i = 0; i < opCount; i++) {
                long durationSeconds = Math.max(1L,
                        Math.round((1800 + random.nextInt(5400)) * durationScale)); // 30min..2h × échelle
                int capabilityClass = sampleClass(cumulativeClassWeight, random.nextDouble());
                // La machine est tirée DANS la classe de capacité de l'opération : c'est ce
                // confinement, pas le nombre total de machines, qui crée la contention réelle.
                long machineId = Math.min(machineCount - 1L,
                        (long) capabilityClass * classSize + random.nextInt(classSize));
                Operation op = new Operation(operationId++, order, i, durationSeconds, machineId);
                orderOps.add(op);
                operationList.add(op);
            }
            order.setOperations(orderOps);
            orderList.add(order);
        }

        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>()); // vide : la construction heuristique la peuple

        return new VerticalSliceSolution(orderList, operationList, machineList, List.of(schedule));
    }

    /**
     * CPT-KKI-008 — compatibilité ASCENDANTE, la vraie topologie. 1000 machines =
     * {@code technologyCount} technologies × {@code levelsPerTechnology} sous-types ×
     * {@code machinesPerLevel} machines. Une opération entrant au niveau <i>k</i> est
     * compatible avec les niveaux <i>k</i> à N de SA technologie, jamais en dessous, et le
     * coût horaire croît de 60 à 150 CHF/h le long de l'échelle.
     *
     * <p>
     * Différence essentielle avec un découpage en classes disjointes : les niveaux hauts sont
     * la SOUPAPE de tous les niveaux inférieurs. La charge ne se répartit donc pas par
     * tirage, elle se déverse vers le haut quand le bas sature — et c'est exactement ce que
     * le mouvement de substitution machine exploite.
     *
     * <p>
     * {@code levelSkew} : exposant d'une loi de Zipf sur le niveau REQUIS. 0 = tous les
     * niveaux également demandés ; 1 = les articles simples dominent, donc le bas de
     * l'échelle est structurellement sous tension et le haut sous-utilisé. C'est le seul
     * paramètre qui décide de la concentration de la criticité (H2).
     *
     * <p>
     * Affectation ici : au niveau REQUIS, c'est-à-dire au moins cher — le choix qu'une
     * affectation minimisant le coût ferait en l'absence de contention. Le débordement vers
     * le haut est une DÉCISION de planification (mouvement M2), pas une donnée : le
     * générateur ne doit pas la préempter, sinon il masquerait précisément le goulot qu'on
     * cherche à mesurer.
     */
    public static VerticalSliceSolution generateAscendingCompatibility(int orderCount, long seed,
            double durationScale, int technologyCount, int levelsPerTechnology, int machinesPerLevel,
            double levelSkew) {
        Random random = new Random(seed);
        int machineCount = technologyCount * levelsPerTechnology * machinesPerLevel;

        List<Machine> machineList = new ArrayList<>(machineCount);
        for (long m = 0; m < machineCount; m++) {
            machineList.add(new Machine(m, 1000));
        }
        double[] cumulativeLevelWeight = buildZipfCumulative(levelsPerTechnology, levelSkew);

        List<Order> orderList = new ArrayList<>(orderCount);
        List<Operation> operationList = new ArrayList<>();
        long operationId = 0;
        for (long o = 0; o < orderCount; o++) {
            long articleId = random.nextInt(200);
            int priorityWeight = 1 + random.nextInt(5);
            long requiredDueEpochSec = BASE_EPOCH + (long) (random.nextDouble() * HORIZON_SECONDS);
            Order order = new Order(o, articleId, priorityWeight, requiredDueEpochSec);

            int opCount = 3 + random.nextInt(4);
            List<Operation> orderOps = new ArrayList<>(opCount);
            for (int i = 0; i < opCount; i++) {
                long durationSeconds = Math.max(1L,
                        Math.round((1800 + random.nextInt(5400)) * durationScale));
                int technology = random.nextInt(technologyCount);
                int requiredLevel = sampleClass(cumulativeLevelWeight, random.nextDouble());
                long machineId = (long) technology * levelsPerTechnology * machinesPerLevel
                        + (long) requiredLevel * machinesPerLevel
                        + random.nextInt(machinesPerLevel);
                Operation op = new Operation(operationId++, order, i, durationSeconds, machineId);
                orderOps.add(op);
                operationList.add(op);
            }
            order.setOperations(orderOps);
            orderList.add(order);
        }

        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>());
        return new VerticalSliceSolution(orderList, operationList, machineList, List.of(schedule));
    }

    /** Poids cumulés d'une loi de Zipf d'exposant {@code skew} sur {@code classCount} classes. */
    private static double[] buildZipfCumulative(int classCount, double skew) {
        double[] cumulative = new double[classCount];
        double total = 0.0;
        for (int i = 0; i < classCount; i++) {
            total += 1.0 / Math.pow(i + 1.0, skew);
            cumulative[i] = total;
        }
        for (int i = 0; i < classCount; i++) {
            cumulative[i] /= total;
        }
        return cumulative;
    }

    private static int sampleClass(double[] cumulative, double uniform) {
        for (int i = 0; i < cumulative.length; i++) {
            if (uniform <= cumulative[i]) {
                return i;
            }
        }
        return cumulative.length - 1;
    }
}
