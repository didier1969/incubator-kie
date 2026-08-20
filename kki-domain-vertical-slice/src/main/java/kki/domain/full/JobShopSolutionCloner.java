package kki.domain.full;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.optaplanner.core.api.domain.solution.cloner.SolutionCloner;

/**
 * Clone ce que les mouvements MUTENT — sans quoi la meilleure solution mémorisée ment.
 *
 * <p>
 * <b>Le défaut que ce cloner corrige.</b> OptaPlanner conserve la meilleure solution rencontrée en
 * la clonant. Son cloner par défaut clone la solution et ses entités de planification, mais pas
 * les <b>faits</b> : ici le {@link Schedule} et sa séquence sont clonés, l'{@link Operation} ne
 * l'est pas. Tant que le seul mouvement était l'échange de position, cela suffisait — la séquence
 * était la seule chose à préserver.
 *
 * <p>
 * Dès que le second mouvement du paradigme entre dans la boucle, l'affectation d'une opération à
 * un poste change en permanence, et elle vit dans un objet PARTAGÉ par toutes les copies de
 * solution. La meilleure solution mémorisée continue donc de muter sous elle : le solveur annonce
 * le score d'un plan et rend un autre plan. Mesuré au premier essai — score annoncé
 * {@code -66,4e9}, plan rendu {@code -69,2e9}, soit 4 % d'écart en faveur de l'annonce.
 *
 * <p>
 * <b>Ce qui est cloné, et rien d'autre.</b> Les ordres et les opérations, parce qu'ils portent
 * l'état muté (séquence, poste, metteur, outillage). Postes, metteurs, outillages, calendriers et
 * matrice de mise en train restent partagés par référence : ils sont immuables, et les copier
 * ferait de chaque amélioration une recopie de tout l'atelier.
 */
public final class JobShopSolutionCloner implements SolutionCloner<JobShopSolution> {

    @Override
    public JobShopSolution cloneSolution(JobShopSolution original) {
        List<Order> originalOrders = original.getOrderList();
        Map<Order, Order> clonedByOriginal = new HashMap<>(originalOrders.size() * 2);
        List<Order> clonedOrders = new ArrayList<>(originalOrders.size());
        for (Order order : originalOrders) {
            Order clone = new Order(order.getId(), order.getArticleId(), order.getPriorityWeight(),
                    order.getDueEpochSec(), order.getFreezeLevel(),
                    order.getReferenceCompletionEpochSec());
            clonedByOriginal.put(order, clone);
            clonedOrders.add(clone);
        }

        List<Operation> originalOperations = original.getOperationList();
        List<Operation> clonedOperations = new ArrayList<>(originalOperations.size());
        Map<Order, List<Operation>> chains = new HashMap<>(originalOrders.size() * 2);
        for (Operation op : originalOperations) {
            Order owner = clonedByOriginal.get(op.getOrder());
            Operation clone = new Operation(op.getId(), owner, op.getPassIndex(),
                    op.getDurationSeconds(), op.getRequiredTechnology(), op.getRequiredLevel(),
                    op.getSetupKey(), op.getRequiredToolingType(),
                    op.getCompatibleMachines(), op.getCompatibleToolings(),
                    op.getMachine(), op.getSetter(), op.getTooling());
            clonedOperations.add(clone);
            chains.computeIfAbsent(owner, key -> new ArrayList<>()).add(clone);
        }
        for (Map.Entry<Order, List<Operation>> chain : chains.entrySet()) {
            chain.getKey().setOperations(chain.getValue());
        }

        Schedule originalSchedule = original.getScheduleList().get(0);
        List<Order> clonedSequence = new ArrayList<>(originalSchedule.getOrderSequence().size());
        for (Order order : originalSchedule.getOrderSequence()) {
            clonedSequence.add(clonedByOriginal.get(order));
        }
        Schedule clonedSchedule = new Schedule();
        // L'identité se REPORTE, elle ne se redevine pas : c'est par elle que
        // `lookUpWorkingObject` retrouve ce Schedule depuis une autre copie de la solution,
        // donc par elle que `Move.rebase` fonctionne. Compter sur le zéro par défaut marcherait
        // tant qu'il n'y a qu'un Schedule — c'est-à-dire jusqu'au jour où il y en aura deux.
        clonedSchedule.setId(originalSchedule.getId());
        clonedSchedule.setOrderSequence(clonedSequence);

        JobShopSolution clone = new JobShopSolution(clonedOrders, clonedOperations,
                original.getMachineList(), original.getSetterList(), original.getToolingList(),
                List.of(clonedSchedule), original.getSetupMatrix(), original.getOriginEpochSec());
        clone.setScore(original.getScore());
        return clone;
    }
}
