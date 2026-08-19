package kki.domain.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kki.domain.Operation;
import kki.domain.Order;
import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * INSTRUMENT DE MESURE — pas un solveur, pas un calculateur de score.
 *
 * <p>
 * Falsification de H1 (la marge est-elle abondante ?) et H2 (la criticité est-elle
 * concentrée ?) de CPT-KKI-012. Autonome par construction : ne touche à aucun champ de
 * {@link VerticalSliceIncrementalScoreCalculator} et recalcule tout à froid, pour qu'aucune
 * mesure ne dépende de l'état incrémental et qu'aucune régression ne soit possible sur le
 * chemin de production.
 *
 * <p>
 * DEUX marges distinctes sont calculées, parce qu'elles répondent à deux questions
 * différentes et qu'un objectif avance/retard les sépare :
 *
 * <ul>
 * <li><b>marge à l'échéance</b> — passe amont amorcée à la date due de chaque ordre. C'est la
 * criticité classique : marge ≤ 0 ⇔ l'ordre est en retard (ou juste à l'heure) et sa chaîne
 * est critique. Elle pilote les leviers L2 (ne proposer que des arcs critiques) et L6
 * (goulot d'abord).</li>
 * <li><b>marge à la complétion</b> — passe amont amorcée à la date de fin ACTUELLE de chaque
 * ordre. C'est de combien une opération peut glisser sans qu'AUCUN ordre ne change de date
 * de fin, donc sans que le coût bouge d'un centime. C'est elle, et pas l'autre, qui borne
 * l'élagage de propagation (L3) : le coût ne dépend que des dates de fin d'ordre.</li>
 * </ul>
 *
 * <p>
 * La distinction n'est pas académique. Avec un coût d'avance, une opération à marge à
 * l'échéance largement positive n'est pas pour autant libre : la déplacer change la date de
 * fin de son ordre, donc son coût d'avance. Confondre les deux surestimerait H1.
 *
 * <p>
 * PÉRIMÈTRE — ce module ne modélise ni l'axe Z délibéré, ni la matrice de mise en train, ni
 * les deux calendriers, ni la compatibilité machine. Les chiffres produits ici sont
 * DIRECTIONNELS : ils suffisent à invalider une hypothèse, jamais à la valider.
 */
public final class SlackReporter {

    private static final long UNBOUNDED = Long.MAX_VALUE / 4;

    private final VerticalSliceSolution solution;
    private final Schedule schedule;
    private final long origin;

    private final long[] earliestStart;
    private final long[] earliestFinish;
    private final Operation[] nextOnMachine;
    private final long[] orderCompletion;

    public SlackReporter(VerticalSliceSolution solution) {
        this.solution = solution;
        this.schedule = solution.getScheduleList().get(0);
        this.origin = SyntheticDataGenerator.BASE_EPOCH;
        int opCount = solution.getOperationList().size();
        this.earliestStart = new long[opCount];
        this.earliestFinish = new long[opCount];
        this.nextOnMachine = new Operation[opCount];
        this.orderCompletion = new long[solution.getOrderList().size()];
        forwardPass();
    }

    /**
     * Passe aval : {@code est(n) = max(fin du prédécesseur machine, fin du prédécesseur de
     * chaîne)}. Même récurrence que le calculateur de production — l'égalité des deux est
     * vérifiée par test différentiel contre {@code fullSweepScore()}.
     */
    private void forwardPass() {
        Map<Long, Operation> machineTail = new HashMap<>();
        for (Order order : schedule.getOrderSequence()) {
            long previousEndInOrder = origin;
            for (Operation op : order.getOperations()) {
                Operation machinePredecessor = machineTail.get(op.getMachineId());
                long machinePredecessorEnd =
                        machinePredecessor != null ? earliestFinish[idx(machinePredecessor)] : origin;
                long start = Math.max(machinePredecessorEnd, previousEndInOrder);
                earliestStart[idx(op)] = start;
                earliestFinish[idx(op)] = start + op.getDurationSeconds();
                if (machinePredecessor != null) {
                    nextOnMachine[idx(machinePredecessor)] = op;
                }
                machineTail.put(op.getMachineId(), op);
                previousEndInOrder = earliestFinish[idx(op)];
            }
            orderCompletion[(int) order.getId()] = previousEndInOrder;
        }
    }

    /**
     * Passe amont : {@code lst(n) = min(lst du successeur de chaîne, lst du successeur
     * machine, échéance) − durée(n)}.
     *
     * <p>
     * Le balayage se fait à rebours de la séquence X, et à rebours de la chaîne à l'intérieur
     * de chaque ordre. C'est un ordre topologique INVERSE valide : une arête machine va
     * toujours vers un xPosition supérieur ou égal (elle est construite en parcourant la
     * séquence dans l'ordre), et quand elle reste dans le même ordre — un ordre qui repasse
     * sur la même machine — elle va vers un rang de passe supérieur. Les deux cas sont donc
     * déjà traités au moment où l'on atteint le nœud.
     *
     * @param seedAtDueDate {@code true} pour amorcer à la date due (criticité classique),
     *        {@code false} pour amorcer à la date de fin actuelle (marge sans effet sur le coût).
     */
    private long[] backwardPass(boolean seedAtDueDate) {
        long[] latestStart = new long[earliestStart.length];
        Arrays.fill(latestStart, UNBOUNDED);
        List<Order> sequence = schedule.getOrderSequence();
        for (int orderIndex = sequence.size() - 1; orderIndex >= 0; orderIndex--) {
            Order order = sequence.get(orderIndex);
            List<Operation> operations = order.getOperations();
            for (int k = operations.size() - 1; k >= 0; k--) {
                Operation op = operations.get(k);
                long deadline;
                if (k == operations.size() - 1) {
                    deadline = seedAtDueDate
                            ? order.getRequiredDueEpochSec()
                            : orderCompletion[(int) order.getId()];
                } else {
                    deadline = latestStart[idx(operations.get(k + 1))];
                }
                Operation machineSuccessor = nextOnMachine[idx(op)];
                if (machineSuccessor != null) {
                    deadline = Math.min(deadline, latestStart[idx(machineSuccessor)]);
                }
                latestStart[idx(op)] = deadline - op.getDurationSeconds();
            }
        }
        return latestStart;
    }

    /** Marge par opération, indexée comme {@link Operation#getId()}. */
    public long[] slack(boolean seedAtDueDate) {
        long[] latestStart = backwardPass(seedAtDueDate);
        long[] slack = new long[latestStart.length];
        for (int i = 0; i < slack.length; i++) {
            slack[i] = latestStart[i] - earliestStart[i];
        }
        return slack;
    }

    /** Score reproduit à froid — sert d'oracle croisé avec le calculateur de production. */
    public long softScore() {
        long soft = 0L;
        for (Order order : schedule.getOrderSequence()) {
            soft -= computeOrderCost(order, orderCompletion[(int) order.getId()]);
        }
        return soft;
    }

    private static long computeOrderCost(Order order, long completionEpochSec) {
        // Réutilise la formule du calculateur de production — jamais une copie (GUI-PRO-013).
        return VerticalSliceIncrementalScoreCalculator.computeOrderCost(order, completionEpochSec);
    }

    /** Rapport complet, une ligne par métrique, prêt à être journalisé. */
    public String report(String label) {
        StringBuilder out = new StringBuilder();
        long[] slackToDue = slack(true);
        long[] slackToCompletion = slack(false);
        int opCount = slackToDue.length;

        out.append(String.format("slack_report[%s] orders=%d operations=%d machines=%d soft_score=%d%n",
                label, solution.getOrderList().size(), opCount, solution.getMachineList().size(), softScore()));
        out.append(loadLine(label));
        out.append(distributionLine(label, "to_due", slackToDue));
        out.append(distributionLine(label, "to_completion", slackToCompletion));
        out.append(latenessLine(label));
        out.append(costSplitLine(label));
        out.append(criticalityByMachineLine(label, slackToDue));
        return out.toString();
    }

    /**
     * CHARGE DE L'INSTANCE — à lire AVANT toute conclusion sur la marge ou la criticité.
     *
     * <p>
     * Une marge abondante sur un atelier vide ne dit rien : elle mesure la capacité
     * excédentaire, pas la structure du problème. Le taux d'occupation est donc la première
     * chose que ce rapport doit imprimer, et la condition de validité de tout le reste.
     */
    private String loadLine(String label) {
        long totalWork = 0L;
        for (Operation op : solution.getOperationList()) {
            totalWork += op.getDurationSeconds();
        }
        int machineCount = solution.getMachineList().size();
        long horizon = 0L;
        long makespan = 0L;
        for (Order order : solution.getOrderList()) {
            horizon = Math.max(horizon, order.getRequiredDueEpochSec() - origin);
            makespan = Math.max(makespan, orderCompletion[(int) order.getId()] - origin);
        }
        double workPerMachine = (double) totalWork / machineCount;
        return String.format(
                "load[%s] total_work_s=%d work_per_machine_s=%.0f horizon_s=%d makespan_s=%d "
                        + "utilisation_vs_horizon=%.2f%% ops_per_machine=%.1f makespan_vs_horizon=%.2f%%%n",
                label, totalWork, workPerMachine, horizon, makespan,
                100.0 * workPerMachine / horizon,
                (double) solution.getOperationList().size() / machineCount,
                100.0 * makespan / horizon);
    }

    private String distributionLine(String label, String kind, long[] slack) {
        long[] sorted = slack.clone();
        Arrays.sort(sorted);
        long zeroOrLess = 0L;
        long strictlyZero = 0L;
        for (long value : slack) {
            if (value <= 0L) {
                zeroOrLess++;
            }
            if (value == 0L) {
                strictlyZero++;
            }
        }
        int n = sorted.length;
        return String.format(
                "slack_dist[%s|%s] min=%d p10=%d p25=%d median=%d p75=%d p90=%d max=%d "
                        + "share_slack_le_0=%.2f%% share_slack_eq_0=%.2f%%%n",
                label, kind, sorted[0], percentile(sorted, 10), percentile(sorted, 25),
                percentile(sorted, 50), percentile(sorted, 75), percentile(sorted, 90), sorted[n - 1],
                100.0 * zeroOrLess / n, 100.0 * strictlyZero / n);
    }

    /**
     * Répartition avance / retard par ordre. Sans elle, une marge à l'échéance négative
     * partout se lirait comme « tout est critique » alors qu'elle signifie seulement « tout
     * est en retard » — deux diagnostics opposés pour le pilotage de la recherche.
     */
    private String latenessLine(String label) {
        int late = 0;
        int early = 0;
        int onTime = 0;
        List<Long> lateness = new ArrayList<>();
        for (Order order : solution.getOrderList()) {
            long delta = orderCompletion[(int) order.getId()] - order.getRequiredDueEpochSec();
            lateness.add(delta);
            if (delta > 0L) {
                late++;
            } else if (delta < 0L) {
                early++;
            } else {
                onTime++;
            }
        }
        long[] sorted = lateness.stream().mapToLong(Long::longValue).sorted().toArray();
        return String.format(
                "lateness[%s] late=%d early=%d on_time=%d median_seconds=%d p10=%d p90=%d%n",
                label, late, early, onTime, percentile(sorted, 50), percentile(sorted, 10),
                percentile(sorted, 90));
    }

    /**
     * Répartition du coût entre avance et retard. C'est elle qui dit de quel problème il
     * s'agit : dominé par le retard, c'est un problème de contention et de séquencement ;
     * dominé par l'avance, c'est un problème de DATATION, et le séquencement n'y change
     * presque rien — deux architectures de solveur opposées.
     */
    private String costSplitLine(String label) {
        long earlinessCost = 0L;
        long tardinessCost = 0L;
        for (Order order : solution.getOrderList()) {
            long completion = orderCompletion[(int) order.getId()];
            long cost = computeOrderCost(order, completion);
            if (completion > order.getRequiredDueEpochSec()) {
                tardinessCost += cost;
            } else {
                earlinessCost += cost;
            }
        }
        long total = earlinessCost + tardinessCost;
        return String.format("cost_split[%s] earliness=%d tardiness=%d earliness_share=%.1f%%%n",
                label, earlinessCost, tardinessCost, total == 0L ? 0.0 : 100.0 * earlinessCost / total);
    }

    /**
     * H2 — concentration de la criticité. On compte les opérations critiques par machine,
     * puis la part portée par les machines les plus chargées en criticité. Une criticité
     * étalée sur toutes les machines prive L6 et R3 de leur objet.
     */
    private String criticalityByMachineLine(String label, long[] slackToDue) {
        int machineCount = solution.getMachineList().size();
        long[] criticalByMachine = new long[machineCount];
        long totalCritical = 0L;
        for (Operation op : solution.getOperationList()) {
            if (slackToDue[idx(op)] <= 0L) {
                criticalByMachine[(int) op.getMachineId()]++;
                totalCritical++;
            }
        }
        long[] sortedDesc = criticalByMachine.clone();
        Arrays.sort(sortedDesc);
        long top1 = topShare(sortedDesc, Math.max(1, machineCount / 100));
        long top5 = topShare(sortedDesc, Math.max(1, machineCount / 20));
        long top10 = topShare(sortedDesc, Math.max(1, machineCount / 10));
        long machinesWithCritical = Arrays.stream(criticalByMachine).filter(c -> c > 0L).count();
        return String.format(
                "criticality[%s] critical_ops=%d machines_touched=%d/%d "
                        + "top1pct_share=%.1f%% top5pct_share=%.1f%% top10pct_share=%.1f%%%n",
                label, totalCritical, machinesWithCritical, machineCount,
                totalCritical == 0 ? 0.0 : 100.0 * top1 / totalCritical,
                totalCritical == 0 ? 0.0 : 100.0 * top5 / totalCritical,
                totalCritical == 0 ? 0.0 : 100.0 * top10 / totalCritical);
    }

    private static long topShare(long[] ascending, int count) {
        long sum = 0L;
        for (int i = ascending.length - 1; i >= 0 && i >= ascending.length - count; i--) {
            sum += ascending[i];
        }
        return sum;
    }

    private static long percentile(long[] ascending, int percent) {
        if (ascending.length == 0) {
            return 0L;
        }
        int index = (int) Math.min(ascending.length - 1L, (long) Math.floor(percent / 100.0 * ascending.length));
        return ascending[index];
    }

    private static int idx(Operation op) {
        return (int) op.getId();
    }
}
