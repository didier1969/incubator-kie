package kki.domain.solver;

import org.optaplanner.core.impl.heuristic.selector.common.nearby.NearbyDistanceMeter;

import kki.domain.Order;

/**
 * REQ-KKI-007 piste (d) : distance = ecart de xPosition[] LIVE (pas
 * Order.getId(), qui ne correspond a l'ordre de sequence qu'au tout premier
 * instant naif -- apres des mouvements acceptes la sequence est une
 * permutation de l'ordre des id, l'id cesserait silencieusement d'approximer
 * la distance dans la liste). xPosition est deja tenu a jour a chaque
 * mouvement par VerticalSliceIncrementalScoreCalculator ; expose via
 * LIVE_X_POSITION plutot que recalcule ici.
 *
 * Destination = Object, pas Order (verifie empiriquement : ClassCastException
 * sans ceci). Pour ListChangeMoveSelectorConfig, DestinationSelectorConfig
 * melange ENTITES (Schedule, "insere en tete de liste") et VALEURS (Order,
 * "insere apres cet ordre") -- cf. le commentaire source de
 * ListNearbyDistanceMatrixDemand.supplyNearbyDistanceMatrix qui l'explique.
 * Schedule (ou tout non-Order) = position 0 par convention (tete de liste).
 * Pour ListSwapMoveSelectorConfig (ValueSelectorConfig.nearbySelectionConfig,
 * chemin ListValueNearbyDistanceMatrixDemand different), destination est
 * toujours un Order -- la branche non-Order ne se declenche jamais la, sans
 * consequence.
 */
public class OrderPositionNearbyDistanceMeter implements NearbyDistanceMeter<Order, Object> {

    @Override
    public double getNearbyDistance(Order origin, Object destination) {
        int[] positions = VerticalSliceIncrementalScoreCalculator.LIVE_X_POSITION;
        int originPosition = positions[(int) origin.getId()];
        if (destination instanceof Order destinationOrder) {
            return Math.abs(originPosition - positions[(int) destinationOrder.getId()]);
        }
        return originPosition;
    }
}
