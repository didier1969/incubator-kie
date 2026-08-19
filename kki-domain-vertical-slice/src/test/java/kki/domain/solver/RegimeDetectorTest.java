package kki.domain.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import kki.domain.Schedule;
import kki.domain.VerticalSliceSolution;

/**
 * REQ-KKI-011 — le détecteur doit trancher correctement aux DEUX bornes du domaine mesuré,
 * pas sur un point. Un détecteur qui ne se trompe que d'un côté est pire qu'aucun détecteur :
 * il enferme le produit dans un régime en donnant l'impression d'avoir choisi.
 *
 * <p>
 * Les instances de test ne sont pas inventées : ce sont les extrémités du balayage de
 * REQ-KKI-010, dont les indicateurs sont déjà connus et publiés.
 */
class RegimeDetectorTest {

    @Test
    void underLoadedInstanceWithEarlinessDominanceChoosesDatation() {
        // Durées historiques, 1000 machines : atelier occupé à 0,65 %, 99,3 % du coût est de
        // l'avance, et le plan tient largement dans l'horizon — il reste de la place devant.
        RegimeDetector.Regime regime = detect(SyntheticDataGenerator.generate(5000, 1000, 42L));
        assertTrue(regime.earlinessShare() > 0.9,
                "instance sous-chargée : le coût doit être dominé par l'avance, mesuré "
                        + regime.earlinessShare());
        assertTrue(regime.makespanOverHorizon() < 1.0, "le plan doit tenir dans l'horizon");
        assertEquals(RegimeDetector.Strategy.DATATION, regime.strategy(),
                "réordonner n'adresse ici que 0,7 % du coût : la datation doit l'emporter");
    }

    @Test
    void loadedInstanceWithTardinessDominanceChoosesSequencement() {
        // Mêmes ordres, durées ×24 : le plan déborde de l'horizon, tout est en retard, il n'y
        // a plus rien à retarder.
        RegimeDetector.Regime regime =
                detect(SyntheticDataGenerator.generate(5000, 1000, 42L, 24.0, 1000, 0.0));
        assertTrue(regime.earlinessShare() < 0.1, "instance saturée : le retard doit dominer");
        assertTrue(regime.makespanOverHorizon() > 1.0, "le plan doit déborder de l'horizon");
        assertEquals(RegimeDetector.Strategy.SEQUENCEMENT, regime.strategy());
    }

    @Test
    void datationRequiresRoomAheadNotJustEarliness() {
        // Garde-fou de la règle mécaniste : une part d'avance élevée ne suffit pas si le plan
        // déborde déjà. Sans la seconde condition, le détecteur choisirait de retarder des
        // ordres qui n'ont nulle part où aller.
        RegimeDetector.Regime regime =
                detect(SyntheticDataGenerator.generate(5000, 1000, 42L, 24.0, 1000, 0.0));
        assertTrue(regime.makespanOverHorizon() > 1.0);
        assertEquals(RegimeDetector.Strategy.SEQUENCEMENT, regime.strategy(),
                "aucune datation n'est possible quand le plan déborde déjà de l'horizon");
    }

    @Test
    void uniformDemandLeavesBottleneckMachineryOff() {
        // Compatibilité ascendante, tous les sous-types également demandés : la criticité est
        // répartie sur tout le parc. Il n'y a aucune minorité de ressources à traiter en
        // premier, donc rien à gagner à les ordonner.
        RegimeDetector.Regime regime = detect(SyntheticDataGenerator.generateAscendingCompatibility(
                5000, 42L, 10.0, 5, 10, 20, 0.0));
        assertFalse(regime.bottleneckMachineryWorthwhile(),
                "criticité étalée sur le parc : engager la machinerie de goulot serait dépenser"
                        + " sans cible, concentration mesurée " + regime.criticalityConcentration());
    }

    @Test
    void skewedDemandTurnsBottleneckMachineryOn() {
        // Mêmes machines, mêmes durées : seule la distribution des niveaux requis change. Le
        // détecteur doit basculer sur ce seul changement.
        RegimeDetector.Regime regime = detect(SyntheticDataGenerator.generateAscendingCompatibility(
                5000, 42L, 10.0, 5, 10, 20, 2.0));
        assertTrue(regime.bottleneckMachineryWorthwhile(),
                "articles simples dominants : une minorité de machines porte l'essentiel de la"
                        + " criticité, concentration mesurée " + regime.criticalityConcentration());
        assertTrue(regime.criticalityOverLoad() < 1.5,
                "et cette concentration est ENTIÈREMENT expliquée par la charge — c'est bien un"
                        + " goulot de capacité, pas un goulot de structure : "
                        + regime.criticalityOverLoad());
    }

    @Test
    void criticalityOverLoadIsOneWhenCriticalityMerelyFollowsLoad() {
        // Le second indicateur, celui qui n'a PAS le droit de décider : il répond à « des
        // machines sont-elles critiques sans être chargées ? ». Vaut 1 par construction quand
        // la criticité est proportionnelle à la charge, quelles que soient les valeurs absolues.
        long[] load = { 10L, 20L, 30L, 40L, 100L, 5L, 5L, 5L, 5L, 5L };
        long[] critical = new long[load.length];
        for (int i = 0; i < load.length; i++) {
            critical[i] = load[i] * 3L;
        }
        assertEquals(1.0, RegimeDetector.concentrationRatio(critical, load), 1e-9);
    }

    private static RegimeDetector.Regime detect(VerticalSliceSolution generated) {
        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>(generated.getOrderList()));
        VerticalSliceSolution solution = new VerticalSliceSolution(generated.getOrderList(),
                generated.getOperationList(), generated.getMachineList(), List.of(schedule));
        return RegimeDetector.detect(new SlackReporter(solution));
    }
}
