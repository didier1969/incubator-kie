package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Les conditions de cohérence d'une instance de banc.
 *
 * <p>
 * Une instance peut être parfaitement bien datée et ne rien mesurer d'utile. Ces tests portent sur
 * ce que les données doivent garantir AVANT toute mesure : que l'exercice soit faisable, et qu'il
 * ait quelque chose à optimiser.
 *
 * <p>
 * L'opérateur a fixé la zone : « pas une entreprise dont l'atelier est vide, mais une entreprise
 * dont la charge dépasse cent pour cent sans optimisation et retombe à quatre-vingts pour cent,
 * équilibrée, grâce au système ».
 */
class BenchCalibrationTest {

    @BeforeEach
    void resetDomainParameters() {
        FullDataGenerator.reset();
    }

    @Test
    void thereIsAlwaysAtLeastOneSetterPerTechnology() {
        // Contrainte de faisabilité posée par l'opérateur. C'est le seul endroit où un paramètre
        // de banc peut faire MENTIR le générateur au lieu de simplement mal mesurer : sans
        // metteur compétent, une technologie n'a aucune mise en train réalisable.
        FullDataGenerator.setterCount = 1;
        JobShopSolution solution = FullDataGenerator.generate(120, 3L);

        Set<Integer> coveredTechnologies = new HashSet<>();
        for (Setter setter : solution.getSetterList()) {
            for (Machine machine : solution.getMachineList()) {
                if (setter.canSetUp(machine)) {
                    coveredTechnologies.add(machine.getTechnology());
                }
            }
        }
        assertTrue(coveredTechnologies.size() >= FullDataGenerator.technologies,
                "toute technologie doit avoir au moins un metteur compétent, couvertes : "
                        + coveredTechnologies);
        for (Operation op : solution.getOperationList()) {
            assertTrue(op.getSetter().canSetUp(op.getMachine()),
                    op + " est confiée à un metteur incompétent malgré le plancher");
        }
    }

    @Test
    void noOrderIsDueBeforeItsOwnRoutingCanPossiblyFinish() {
        // Un ordre dû avant que sa propre gamme puisse s'exécuter est en retard quoi que fasse le
        // système. Mesurer son retard revient à mesurer de l'infaisabilité, pas de la qualité
        // d'ordonnancement.
        JobShopSolution solution = FullDataGenerator.generate(400, 11L);
        int feasible = 0;
        for (Order order : solution.getOrderList()) {
            long machining = 0L;
            for (Operation op : order.getOperations()) {
                machining += op.getDurationSeconds();
            }
            long available = order.getDueEpochSec() - FullDataGenerator.ORIGIN_EPOCH;
            assertTrue(available >= machining,
                    "l'ordre " + order.getId() + " est dû dans " + available / 3600 + " h alors"
                            + " que son seul usinage en demande " + machining / 3600);
            feasible++;
        }
        assertTrue(feasible > 0, "aucun ordre vérifié");
    }

    @Test
    void theThreeFreezeLevelsSurviveTheDueDateDerivation() {
        // Les paliers étaient exprimés en délai ABSOLU. Depuis que la date due ne peut plus
        // précéder le temps de traversée, aucun ordre n'atteignait plus le seuil du gel dur et
        // les trois paliers de CPT-KKI-004 se réduisaient à un seul, en silence.
        JobShopSolution solution = FullDataGenerator.generate(600, 13L);
        for (Order.FreezeLevel level : Order.FreezeLevel.values()) {
            assertTrue(solution.getOrderList().stream().anyMatch(o -> o.getFreezeLevel() == level),
                    "palier de gel absent de l'instance : " + level);
        }
    }

    @Test
    void theInstanceIsLoadedAndUnbalancedNotEmptyAndNotFlat() {
        // LA condition de l'exercice. Deux choses distinctes, et la moyenne seule n'en dit
        // qu'une : un atelier dont TOUS les postes seraient à quatre-vingts pour cent aurait la
        // bonne moyenne et n'offrirait rien à équilibrer.
        JobShopSolution problem = FullDataGenerator.generate(2000, 17L);
        problem.getScheduleList().get(0).getOrderSequence()
                .sort(Comparator.comparingLong(Order::getDueEpochSec));
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(problem);
        FullScoreCalculator.ResourceUsage usage =
                calculator.resourceUsage(FullDataGenerator.horizonSeconds);

        assertTrue(usage.loadMean() > 0.10,
                "atelier quasi vide : rien à ordonnancer, charge moyenne "
                        + Math.round(100 * usage.loadMean()) + " %");
        assertTrue(usage.overloadedMachines() > 0,
                "aucun poste au-dessus de cent pour cent : rien à rééquilibrer, l'exercice est"
                        + " vide même si la moyenne tombe juste");
        assertTrue(usage.loadMax() > 1.5 * usage.loadMean(),
                "la charge doit être franchement DISPERSÉE : max "
                        + Math.round(100 * usage.loadMax()) + " % contre une moyenne de "
                        + Math.round(100 * usage.loadMean()) + " %");
    }

    @Test
    void extendingTheSetterShiftIsWhatMovesTheLoad() {
        // Le résultat de calibration, verrouillé : la charge est dominée par le temps mur
        // d'immobilisation de mise en train, et c'est la couverture horaire du réglage qui la
        // commande — pas la durée d'usinage, dont la réduction bute sur un plancher.
        double eightHours = loadAt(8);
        double twelveHours = loadAt(12);
        assertTrue(twelveHours < eightHours * 0.85,
                "étendre la couverture de réglage doit faire nettement baisser la charge : "
                        + Math.round(100 * eightHours) + " % contre "
                        + Math.round(100 * twelveHours) + " %");
    }

    private static double loadAt(int shiftHours) {
        FullDataGenerator.reset();
        FullDataGenerator.setterWindowSeconds = shiftHours * 3600L;
        JobShopSolution problem = FullDataGenerator.generate(2000, 19L);
        problem.getScheduleList().get(0).getOrderSequence()
                .sort(Comparator.comparingLong(Order::getDueEpochSec));
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(problem);
        double load = calculator.resourceUsage(FullDataGenerator.horizonSeconds).loadMean();
        FullDataGenerator.reset();
        return load;
    }
}
