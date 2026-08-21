package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

/**
 * Tranche V1 de {@code REQ-KKI-065} : la revendication EXISTE et ne change RIEN.
 *
 * <p>
 * C'est le test qui autorise à livrer sans réserve. Une conception qui se juge sur ce qu'elle
 * ajoute doit d'abord prouver ce qu'elle ne touche pas — sans quoi tout écart mesuré plus tard sur
 * les tranches suivantes se lira contre une base qui a bougé sans qu'on le sache.
 *
 * <p>
 * Le plancher de bruit du banc en budget de TRAVAIL vaut <b>exactement zéro</b> (REQ-KKI-052, cinq
 * runs identiques, amplitude 0,00 %). Il n'y a donc rien à soustraire : sur le cas d'identité, tout
 * écart non nul serait un <b>défaut</b>, jamais du bruit. C'est ce qui rend T1 utile plutôt que
 * rassurant.
 */
class ResourceClaimTest {

    private static final int ORDERS = 300;
    private static final long SEED = 71L;

    @BeforeEach
    void resetDomainParameters() {
        // Les dimensions du domaine sont des statiques mutables partagés par toute la JVM de test.
        FullDataGenerator.reset();
    }

    @Test
    void theGeneratorEmitsNoClaimByDefault() {
        // VIS-KKI-001 : un réglage mesuré devient un paramètre exposé, jamais un défaut codé en
        // dur. Tant que la butée n'est pas mesurée, le banc doit rendre EXACTEMENT ce qu'il rendait
        // — sinon toutes les campagnes archivées cessent d'être rejouables, et on perd la seule
        // base de comparaison qu'on ait.
        JobShopSolution solution = FullDataGenerator.generate(ORDERS, SEED);
        assertTrue(solution.getClaimList().isEmpty(),
                "le générateur ne doit émettre AUCUNE revendication par défaut, sinon les "
                        + "campagnes antérieures ne se rejouent plus à l'identique");
    }

    @Test
    void anEmptyClaimListLeavesTheScoreExactlyWhereItWas() {
        // T1 — IDENTITÉ. L'oracle est le balayage à froid : il recalcule tout depuis rien, par un
        // chemin indépendant de la propagation incrémentale. Leur accord est l'invariant que ce
        // projet vérifie depuis le début ; s'il tient encore après l'ajout du champ, c'est que le
        // champ n'a touché à aucun des deux chemins.
        JobShopSolution solution = FullDataGenerator.generate(ORDERS, SEED);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        HardSoftLongScore incremental = calculator.calculateScore();
        HardSoftLongScore oracle = calculator.fullSweepScore();

        assertEquals(oracle, incremental,
                "à liste de revendications vide, le chemin incrémental et le balayage à froid "
                        + "doivent rendre le MÊME score — sinon l'ajout du champ a déplacé l'un "
                        + "des deux");
    }

    @Test
    void theClonerSharesClaimsByReferenceAndNeverCopiesThem() {
        // Une revendication est un FAIT immuable : le solveur la subit, il ne la décide pas. Elle
        // se partage donc par référence, comme Machine, Setter et SetupMatrix.
        //
        // Ce test garde les deux moitiés du contrat. Cloner une revendication serait la porte
        // ouverte à la classe de défaut qui a produit JobShopSolutionCloner : un champ oublié à la
        // copie, un score qui décrit un plan que personne n'a rendu. La partager par référence
        // rend cette faute IMPOSSIBLE plutôt que surveillée.
        JobShopSolution solution = FullDataGenerator.generate(ORDERS, SEED);
        JobShopSolution clone = new JobShopSolutionCloner().cloneSolution(solution);

        assertSame(solution.getClaimList(), clone.getClaimList(),
                "les revendications doivent être partagées par référence, jamais copiées");
    }

    @Test
    void aClaimReportsItsFreeInstantPerLayerAndIgnoresTheLayersItDoesNotHold() {
        // Le croisement est une intersection X × Y : être sur CETTE ressource, ET recouvrir sa
        // fenêtre. Une revendication qui n'emprunte pas une couche ne doit rien y imposer — et
        // rendre Long.MIN_VALUE plutôt que zéro, pour qu'un Math.max l'absorbe sans branche.
        ResourceClaim claim = new ResourceClaim(7L, 42, 17, ResourceClaim.NONE, 1234,
                100L, 900L, 100L, 400L, 0L, 0L);

        assertEquals(900L, claim.freeAtOn(42, 99, 99), "la couche machine doit être rendue");
        assertEquals(400L, claim.freeAtOn(99, 17, 99), "la couche metteur doit être rendue");
        assertEquals(900L, claim.freeAtOn(42, 17, 99),
                "deux couches touchées : c'est la PLUS TARDIVE qui commande");
        assertEquals(Long.MIN_VALUE, claim.freeAtOn(99, 99, 99),
                "aucune couche touchée : la revendication ne doit rien imposer");
        assertEquals(Long.MIN_VALUE, claim.freeAtOn(99, 99, 3),
                "l'outillage n'est pas emprunté (NONE) : il ne doit jamais matcher, "
                        + "surtout pas l'outillage d'identifiant -1");
    }

    @Test
    void theSolutionAcceptsANullClaimListWithoutBecomingNull() {
        // Un appelant historique qui passerait null ne doit pas transformer une absence en NPE au
        // premier reset. Le vide est un état légitime ; le null n'en est pas un.
        JobShopSolution solution = new JobShopSolution(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, null, 0L);
        assertTrue(solution.getClaimList().isEmpty(),
                "une liste de revendications nulle doit se lire comme vide");
    }
}
