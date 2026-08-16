package kki.domain.solver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

import kki.bench.CostKernel;
import kki.domain.Operation;
import kki.domain.Order;
import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * REQ-KKI-006 (réécrit après le bug de cycle de précédence, cf REQ-KKI-006
 * SOLL) — recalcul complet à chaque appel, volontairement simple pour une
 * première version correcte : balaie Schedule.orderSequence (l'axe X, LA
 * seule coordonnée de séquencement) une fois, dérive la séquence par
 * machine par simple filtrage au passage. Plus de shadow variable, plus de
 * risque de cycle — structurellement impossible avec une seule coordonnée
 * partagée. Pas incrémental au sens strict (O(N) par évaluation, pas O(1)) :
 * choix délibéré pour prouver la correction d'abord, mesurer l'IPS réel,
 * optimiser seulement si le chiffre le justifie (PIL-KKI-003 : la vitesse
 * est un multiplicateur, pas l'objectif).
 */
public class VerticalSliceIncrementalScoreCalculator
        implements IncrementalScoreCalculator<VerticalSliceSolution, HardSoftLongScore> {

    private static final float K = 5f;

    /** Compteur d'appels — mesure IPS (REQ-KKI-006). */
    public static final AtomicLong CALCULATE_SCORE_CALLS = new AtomicLong();

    private VerticalSliceSolution solution;

    @Override
    public void resetWorkingSolution(VerticalSliceSolution solution) {
        this.solution = solution;
    }

    @Override
    public void beforeEntityAdded(Object entity) {
    }

    @Override
    public void afterEntityAdded(Object entity) {
    }

    @Override
    public void beforeVariableChanged(Object entity, String variableName) {
    }

    @Override
    public void afterVariableChanged(Object entity, String variableName) {
    }

    @Override
    public void beforeEntityRemoved(Object entity) {
    }

    @Override
    public void afterEntityRemoved(Object entity) {
    }

    @Override
    public HardSoftLongScore calculateScore() {
        CALCULATE_SCORE_CALLS.incrementAndGet();

        Schedule schedule = solution.getScheduleList().get(0);
        List<Order> orderSequence = schedule.getOrderSequence();

        // Meme origine absolue que les dates dues (SyntheticDataGenerator.BASE_EPOCH) —
        // sans ca, un balayage relatif-a-zero se compare a des dates epoch absolues et
        // "l'avance" explose (bug reel observe : score ~ -11 mille milliards a N=100).
        long scheduleOrigin = SyntheticDataGenerator.BASE_EPOCH;
        Map<Long, Long> lastEndByMachine = new HashMap<>();
        long softScore = 0L;

        for (Order order : orderSequence) {
            long previousEndInOrder = scheduleOrigin;
            long lastOperationEnd = scheduleOrigin;
            for (Operation op : order.getOperations()) {
                long machinePredEnd = lastEndByMachine.getOrDefault(op.getMachineId(), scheduleOrigin);
                long start = Math.max(machinePredEnd, previousEndInOrder);
                long end = start + op.getDurationSeconds();
                lastEndByMachine.put(op.getMachineId(), end);
                previousEndInOrder = end;
                lastOperationEnd = end;
            }
            float hoursLateOrEarly = (lastOperationEnd - order.getRequiredDueEpochSec()) / 3600f;
            float cost = CostKernel.cost(order.getPriorityWeight(), K, hoursLateOrEarly);
            // Math.round(float) renvoie un int — deborde silencieusement si un ordre
            // accumule un retard massif sous une CH gloutonne (observe empiriquement :
            // score ~ -Integer.MIN_VALUE x N). Math.round(double) renvoie un long :
            // exactement le risque de depassement que le choix quadratique (pas
            // exponentiel) visait deja a eviter, cf corps CPT-KKI-009.
            softScore -= Math.round((double) cost);
        }

        return HardSoftLongScore.of(0L, softScore);
    }
}
