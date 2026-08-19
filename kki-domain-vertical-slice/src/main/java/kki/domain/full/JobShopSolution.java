package kki.domain.full;

import java.util.List;

import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

/**
 * Le problème complet de PIL-KKI-004 : axe Z, matrice de mise en train (article, passe),
 * calendriers machine et metteur indépendants, pool d'outillage fini partagé, compatibilité
 * machine ascendante avec coût horaire croissant, trois paliers de gel, coûts retard et avance
 * quadratiques.
 *
 * <p>
 * Le clonage passe par {@link JobShopSolutionCloner} et non par le cloner par défaut : ce dernier
 * ne clone pas les FAITS, or l'affectation d'une opération à un poste — que le second mouvement
 * du paradigme change en permanence — vit dans un fait. Sans cela, la meilleure solution mémorisée
 * continue de muter sous elle et le solveur annonce le score d'un plan en en rendant un autre.
 *
 * <p>
 * Le score est {@code hard/soft} et les deux composantes ont un sens distinct : le <b>dur</b>
 * porte les violations de gel dur — un ordre démarré qu'on déplace n'est pas un surcoût, c'est
 * une faute ; le <b>souple</b> porte tout le reste, en centimes.
 */
@PlanningSolution(solutionCloner = JobShopSolutionCloner.class)
public class JobShopSolution {

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "orderRange")
    private List<Order> orderList;

    // L'opération reste un FAIT : cette version d'OptaPlanner ne fait pas coexister une
    // variable-liste et une variable simple (voir Operation). Le calculateur navigue malgré tout
    // par tableaux indexés sur l'identifiant plutôt que par références d'objet — c'est la
    // mémoïsation du chemin chaud, et c'est aussi ce qui rendra la bascule sans risque.
    @ProblemFactCollectionProperty
    private List<Operation> operationList;

    @ProblemFactCollectionProperty
    private List<Machine> machineList;

    @ProblemFactCollectionProperty
    private List<Setter> setterList;

    // Le pool d'outillage : fini et partagé (CPT-KKI-006). Troisième ressource exclusive
    // consommée par une mise en train, au même titre que la machine et le metteur.
    @ProblemFactCollectionProperty
    private List<Tooling> toolingList;

    @PlanningEntityCollectionProperty
    private List<Schedule> scheduleList;

    @PlanningScore
    private HardSoftLongScore score;

    private SetupMatrix setupMatrix;
    private long originEpochSec;

    public JobShopSolution() {
    }

    public JobShopSolution(List<Order> orderList, List<Operation> operationList,
            List<Machine> machineList, List<Setter> setterList, List<Tooling> toolingList,
            List<Schedule> scheduleList, SetupMatrix setupMatrix, long originEpochSec) {
        this.orderList = orderList;
        this.operationList = operationList;
        this.machineList = machineList;
        this.setterList = setterList;
        this.toolingList = toolingList;
        this.scheduleList = scheduleList;
        this.setupMatrix = setupMatrix;
        this.originEpochSec = originEpochSec;
    }

    public List<Order> getOrderList() {
        return orderList;
    }

    public List<Operation> getOperationList() {
        return operationList;
    }

    public List<Machine> getMachineList() {
        return machineList;
    }

    public List<Setter> getSetterList() {
        return setterList;
    }

    public List<Tooling> getToolingList() {
        return toolingList;
    }

    public List<Schedule> getScheduleList() {
        return scheduleList;
    }

    public HardSoftLongScore getScore() {
        return score;
    }

    public void setScore(HardSoftLongScore score) {
        this.score = score;
    }

    public SetupMatrix getSetupMatrix() {
        return setupMatrix;
    }

    public long getOriginEpochSec() {
        return originEpochSec;
    }
}
