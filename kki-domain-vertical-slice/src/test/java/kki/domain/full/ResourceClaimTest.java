package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
    void anOperationThatWouldCrossAClaimIsPushedBehindIt() {
        // Tranche V2. Rien ne traverse une revendication : l'opération qui la croiserait est
        // REPOUSSÉE. C'est le report qui donne à la passe avant le terme de refus qu'un `max`
        // seul ne peut pas porter.
        JobShopSolution base = FullDataGenerator.generate(ORDERS, SEED);
        FullScoreCalculator before = new FullScoreCalculator();
        before.resetWorkingSolution(base);

        Operation target = base.getOperationList().get(base.getOperationList().size() / 2);
        int targetId = (int) target.getId();
        int machineId = (int) target.getMachineId();
        long windowFrom = before.setupStartOf(targetId) - 3600L;
        long windowTo = before.endOf(targetId) + 3600L;

        // La revendication couvre toute la fenêtre de l'opération, mise en train comprise.
        JobShopSolution withClaim = withMachineClaim(machineId, windowFrom, windowTo, 0);
        FullScoreCalculator after = new FullScoreCalculator();
        after.resetWorkingSolution(withClaim);

        assertTrue(after.setupStartOf(targetId) >= windowTo,
                "l'opération devait être repoussée derrière la revendication : mise en train à "
                        + after.setupStartOf(targetId) + ", revendication libérée à " + windowTo);
    }

    @Test
    void noOperationEverHoldsAMachineWhileAClaimHoldsIt() {
        // T3 — LE CHEVAUCHEMENT DEVIENT OBSERVABLE.
        //
        // Le garde `noMachineRunsTwoOperationsAtOnce` de ModelInvariantsTest est aujourd'hui une
        // TAUTOLOGIE : la passe avant n'est qu'un `max`, donc elle ne PEUT pas produire deux
        // opérations simultanées, donc le test ne peut rien détecter. La revendication est la
        // première chose du modèle qui rende ce test capable d'échouer — parce qu'elle occupe une
        // machine sans passer par la file, donc sans passer par le `max`.
        //
        // La fenêtre comparée commence à la MISE EN TRAIN : la machine est immobilisée dès là.
        JobShopSolution base = FullDataGenerator.generate(ORDERS, SEED);
        FullScoreCalculator before = new FullScoreCalculator();
        before.resetWorkingSolution(base);

        Operation target = base.getOperationList().get(base.getOperationList().size() / 2);
        int machineId = (int) target.getMachineId();
        long windowFrom = before.setupStartOf((int) target.getId()) - 3600L;
        long windowTo = before.endOf((int) target.getId()) + 3600L;

        JobShopSolution withClaim = withMachineClaim(machineId, windowFrom, windowTo, 0);
        FullScoreCalculator after = new FullScoreCalculator();
        after.resetWorkingSolution(withClaim);

        int examined = 0;
        for (Operation op : withClaim.getOperationList()) {
            if ((int) op.getMachineId() != machineId) {
                continue;
            }
            examined++;
            int opId = (int) op.getId();
            long from = after.setupStartOf(opId);
            long to = after.endOf(opId);
            assertTrue(to <= windowFrom || from >= windowTo,
                    "chevauchement avec la revendication : " + op + " tient M" + machineId
                            + " de " + from + " à " + to + ", revendiquée de " + windowFrom
                            + " à " + windowTo);
        }
        // Garde de non-vacuité : un invariant qui ne regarde rien est vrai pour rien.
        assertTrue(examined > 0,
                "aucune opération sur la machine revendiquée — le test serait vrai par vacuité");
    }

    @Test
    void aClaimOnTheSETTERAloneAlsoPushes() {
        // Le croisement est une intersection X × Y. Une opération peut croiser sur le metteur sans
        // croiser sur la broche, parce qu'elle partage le metteur et pas la machine. Si le clamp
        // ne regardait que la machine, cette revendication-ci ne bloquerait rien.
        JobShopSolution base = FullDataGenerator.generate(ORDERS, SEED);
        FullScoreCalculator before = new FullScoreCalculator();
        before.resetWorkingSolution(base);

        Operation target = base.getOperationList().get(base.getOperationList().size() / 2);
        int targetId = (int) target.getId();
        int setterId = (int) target.getSetter().getId();
        // ⚠️ DIX JOURS, pas deux heures. Le calendrier du metteur est ouvert 8 h/j, 5 j/7 : une
        // revendication courte est franchie par le seul arrondi de `occupancyEnd`, et le test
        // passerait sans que la butée y soit pour rien. Une assertion vraie pour la mauvaise
        // raison ne vaut pas mieux qu'une assertion absente — vérifié en falsifiant.
        long from = before.setupStartOf(targetId) - 3600L;
        long to = before.setupEndOf(targetId) + 10L * 24 * 3600L;

        JobShopSolution withClaim = withClaims(new ResourceClaim(-1L, ResourceClaim.NONE, setterId,
                ResourceClaim.NONE, 0, 0L, 0L, from, to, 0L, 0L));
        FullScoreCalculator after = new FullScoreCalculator();
        after.resetWorkingSolution(withClaim);

        assertTrue(after.setupStartOf(targetId) >= to,
                "une revendication portant SEULEMENT sur le metteur doit repousser la mise en "
                        + "train : commencée à " + after.setupStartOf(targetId) + ", metteur "
                        + "libéré à " + to);
    }

    @Test
    void aPushOnOneLayerCanCreateACrossingOnAnotherAndTheClampMustIterate() {
        // LE POINT FIXE. Repousser une opération derrière une revendication MACHINE décale sa mise
        // en train, qui peut alors tomber sur une revendication de METTEUR qu'elle ne croisait pas
        // avant. Une seule passe de clamp s'arrêterait à la première butée et rendrait un plan où
        // le metteur fait deux mises en train à la fois.
        //
        // ⚠️ Ce test n'a de valeur que sur une opération PREMIÈRE sur sa machine ET sans
        // prédécesseur de chaîne. Sur toute autre, le prédécesseur — déjà repoussé par la même
        // revendication — donne un `setupEnd` élevé dès la première passe, la seconde couche est
        // vue immédiatement, et UNE passe suffit : le test passerait sans rien prouver. Vérifié en
        // falsifiant, et c'est la seule raison de la sélection ci-dessous.
        JobShopSolution base = FullDataGenerator.generate(ORDERS, SEED);
        FullScoreCalculator before = new FullScoreCalculator();
        before.resetWorkingSolution(base);

        Operation target = firstOnItsMachineWithNoChainPredecessor(base, before);
        int targetId = (int) target.getId();
        int machineId = (int) target.getMachineId();
        int setterId = (int) target.getSetter().getId();

        long machineFrom = base.getOriginEpochSec();
        long machineTo = before.endOf(targetId) + 3600L;
        // La seconde revendication commence EXACTEMENT là où la première libère : l'opération ne
        // la croise pas AVANT le premier saut, elle la croise APRÈS. Trente jours, pour qu'aucun
        // arrondi de calendrier ne la franchisse — une revendication courte est noyée par les
        // 8 h/j du metteur, et le test redeviendrait vacant.
        long setterFrom = machineTo;
        long setterTo = machineTo + 30L * 24 * 3600L;

        JobShopSolution withBoth = withClaims(
                new ResourceClaim(-1L, machineId, ResourceClaim.NONE, ResourceClaim.NONE, 0,
                        machineFrom, machineTo, 0L, 0L, 0L, 0L),
                new ResourceClaim(-2L, ResourceClaim.NONE, setterId, ResourceClaim.NONE, 0,
                        0L, 0L, setterFrom, setterTo, 0L, 0L));
        FullScoreCalculator after = new FullScoreCalculator();
        after.resetWorkingSolution(withBoth);

        assertTrue(after.setupStartOf(targetId) >= setterTo,
                "le clamp doit ITÉRER : après le saut machine (jusqu'à " + machineTo + ") "
                        + target + " croise la revendication de metteur et doit être repoussée "
                        + "jusqu'à " + setterTo + " — observé " + after.setupStartOf(targetId));
    }

    @Test
    void theColdSweepMustSeeTheSameClaimsAsTheIncrementalPass() {
        // T-DIFF — l'oracle redevient un oracle.
        //
        // V2 et V3 ont posé la butée dans `recomputeOperation` SEULEMENT. `coldSweep` datait
        // encore par le seul `max` des trois disponibilités de file, sans jamais consulter une
        // revendication. Les deux passes rendaient donc deux plans différents dès qu'une
        // revendication existait — et rien ne le disait, parce que T1 compare les deux passes sur
        // une liste VIDE, où il n'y a rien à voir.
        //
        // Le désaccord se lit en CHIFFRES, pas en « pas égaux » : c'est l'écart qui nomme la
        // cause.
        JobShopSolution base = FullDataGenerator.generate(ORDERS, SEED);
        FullScoreCalculator before = new FullScoreCalculator();
        before.resetWorkingSolution(base);

        Operation target = base.getOperationList().get(base.getOperationList().size() / 2);
        int targetId = (int) target.getId();
        int machineId = (int) target.getMachineId();
        long from = before.setupStartOf(targetId) - 3600L;
        long to = before.endOf(targetId) + 30L * 24 * 3600L;

        JobShopSolution withClaim = withMachineClaim(machineId, from, to, 0);
        FullScoreCalculator after = new FullScoreCalculator();
        after.resetWorkingSolution(withClaim);

        HardSoftLongScore incremental = after.calculateScore();
        HardSoftLongScore oracle = after.fullSweepScore();

        assertEquals(oracle, incremental,
                "revendications NON VIDES : le chemin incrémental et le balayage à froid doivent "
                        + "rendre le MÊME score. incrémental=" + incremental + " oracle=" + oracle
                        + " écart_souple=" + (incremental.softScore() - oracle.softScore()));
    }

    @Test
    void theSetupKeyComesFromTheCLAIMWhenTheClaimIsTheLastOccupantOfTheMachine() {
        // T2 — LE FALSIFICATEUR PRINCIPAL DE LA CONCEPTION.
        //
        // Un trou de calendrier dit « fermé, état PRÉSERVÉ » : l'article reste monté, rien n'est à
        // remonter au matin. Une revendication dit l'inverse : « pris, état DÉTRUIT » — un autre
        // travail y est passé et a laissé SON article. Toute la conception tient dans cet écart,
        // et il ne se chiffre qu'ici : quand l'article laissé par la revendication est celui que
        // l'opération suivante demande, la remise en train vaut EXACTEMENT ZÉRO.
        //
        // ⚠️ L'opération est prise PREMIÈRE sur sa machine, et ce n'est pas un détail de mise en
        // scène. Sur toute autre, le prédécesseur de file pourrait porter le même article et la
        // mise en train vaudrait zéro sans que la revendication y soit pour rien. Première sur sa
        // machine, la seule autre valeur possible est le démarrage à froid — un nombre qu'aucun
        // autre mécanisme du modèle ne produit.
        //
        // SI CE TEST ÉCHOUE, C'EST LA CONCEPTION QUI EST FAUSSE, PAS L'IMPLÉMENTATION.
        JobShopSolution base = FullDataGenerator.generate(ORDERS, SEED);
        FullScoreCalculator before = new FullScoreCalculator();
        before.resetWorkingSolution(base);

        Operation target = firstOnItsMachineWithNoChainPredecessor(base, before);
        int targetId = (int) target.getId();
        int machineId = (int) target.getMachineId();
        long coldStart = base.getSetupMatrix().coldStartSeconds(target.getSetupKey());

        // La revendication tient la machine depuis l'origine, et laisse montré L'ARTICLE MÊME que
        // l'opération demande.
        long claimFrom = base.getOriginEpochSec();
        long claimTo = before.setupEndOf(targetId) + 30L * 24 * 3600L;
        JobShopSolution withClaim =
                withMachineClaim(machineId, claimFrom, claimTo, target.getSetupKey());
        FullScoreCalculator after = new FullScoreCalculator();
        after.resetWorkingSolution(withClaim);

        assertEquals(claimTo, after.setupStartOf(targetId),
                "l'opération doit reprendre la machine à l'instant EXACT où la revendication la "
                        + "rend");
        assertEquals(after.setupStartOf(targetId), after.setupEndOf(targetId),
                "l'article de la revendication est celui que l'opération demande : la mise en "
                        + "train doit valoir ZÉRO seconde. Sans la clé venue de la revendication, "
                        + "cette machine serait vue FROIDE et paierait " + coldStart
                        + " s de démarrage — observé " + (after.setupEndOf(targetId)
                                - after.setupStartOf(targetId)) + " s de temps mur");
        assertEquals(0L, after.resourceCentsOf(targetId),
                "mise en train nulle ET machine reprise à l'instant où la revendication la rend : "
                        + "le coût de ressource doit être EXACTEMENT nul. Un reste non nul est "
                        + "une immobilisation fantôme — la machine facturée à l'arrêt pendant que "
                        + "la revendication l'usinait");
    }

    @Test
    void aClaimThatPushesNothingIsStillTheLastOccupantOfTheMachine() {
        // Le dernier occupant N'EST PAS « la revendication franchie ». Une revendication peut se
        // terminer AVANT que l'opération ne démarre — parce que le metteur, lui, n'est libre que
        // plus tard — sans rien pousser du tout. C'est pourtant SON article qui est sur la broche.
        //
        // C'est le seul cas qui sépare la règle implémentée de sa formulation étroite, et c'est là
        // qu'un `>` glissé en `>=` se cacherait.
        JobShopSolution base = FullDataGenerator.generate(ORDERS, SEED);
        FullScoreCalculator before = new FullScoreCalculator();
        before.resetWorkingSolution(base);

        Operation target = firstOnItsMachineWithNoChainPredecessor(base, before);
        int targetId = (int) target.getId();
        int machineId = (int) target.getMachineId();
        int setterId = (int) target.getSetter().getId();
        long coldStart = base.getSetupMatrix().coldStartSeconds(target.getSetupKey());

        // Le metteur est retenu trente jours : c'est LUI qui commande la date de départ.
        long setterTo = base.getOriginEpochSec() + 30L * 24 * 3600L;
        // La revendication machine, elle, rend la broche BIEN AVANT — elle ne pousse rien.
        long machineTo = base.getOriginEpochSec() + 10L * 24 * 3600L;

        JobShopSolution withBoth = withClaims(
                new ResourceClaim(-1L, machineId, ResourceClaim.NONE, ResourceClaim.NONE,
                        target.getSetupKey(), base.getOriginEpochSec(), machineTo, 0L, 0L, 0L, 0L),
                new ResourceClaim(-2L, ResourceClaim.NONE, setterId, ResourceClaim.NONE, 0,
                        0L, 0L, base.getOriginEpochSec(), setterTo, 0L, 0L));
        FullScoreCalculator after = new FullScoreCalculator();
        after.resetWorkingSolution(withBoth);

        assertTrue(after.setupStartOf(targetId) >= setterTo,
                "c'est le metteur qui commande la date : mise en train à "
                        + after.setupStartOf(targetId) + ", metteur libéré à " + setterTo);
        assertEquals(after.setupStartOf(targetId), after.setupEndOf(targetId),
                "la revendication machine ne pousse RIEN — elle rend la broche dix jours avant — "
                        + "mais elle reste le DERNIER OCCUPANT, et son article est celui que "
                        + "l'opération demande : la mise en train doit valoir zéro. Une règle "
                        + "limitée aux revendications FRANCHIES facturerait ici " + coldStart
                        + " s de démarrage à froid");
    }

    @Test
    void bothPassesAgreeWhenTheClaimAlsoCarriesTheSetupKey() {
        // Le même contrôle différentiel que ci-dessus, mais sur le cas où la butée touche AUSSI la
        // clé de mise en train. Les deux passes portent la formule en DOUBLE — c'est ce qui fait
        // du balayage à froid un oracle indépendant, et c'est aussi ce qui les laisse diverger si
        // une seule des deux apprend la règle. Rien d'autre ne le dirait.
        JobShopSolution base = FullDataGenerator.generate(ORDERS, SEED);
        FullScoreCalculator before = new FullScoreCalculator();
        before.resetWorkingSolution(base);

        Operation target = firstOnItsMachineWithNoChainPredecessor(base, before);
        int machineId = (int) target.getMachineId();
        long claimTo = before.setupEndOf((int) target.getId()) + 30L * 24 * 3600L;

        JobShopSolution withClaim = withMachineClaim(machineId, base.getOriginEpochSec(), claimTo,
                target.getSetupKey());
        FullScoreCalculator after = new FullScoreCalculator();
        after.resetWorkingSolution(withClaim);

        HardSoftLongScore incremental = after.calculateScore();
        HardSoftLongScore oracle = after.fullSweepScore();

        assertEquals(oracle, incremental,
                "la clé venue de la revendication doit être vue par les DEUX passes. "
                        + "incrémental=" + incremental + " oracle=" + oracle + " écart_souple="
                        + (incremental.softScore() - oracle.softScore()));
    }

    @Test
    void thePassesStayInAgreementUnderMOVESAndNotOnlyAtReset() {
        // Les contrôles différentiels précédents comparent les deux passes SUR UN RESET. Ils ne
        // disent rien de la propagation : un nœud sali et oublié après un mouvement laisserait la
        // butée juste au départ et fausse dès le premier échange. C'est cette moitié-là que le
        // moteur exercera réellement.
        //
        // Les quatre familles de prédécesseurs sont sollicitées — séquence, machine, metteur,
        // outillage — parce qu'une butée qui ne serait recalculée que sur la file machine
        // passerait ce test avec trois branches mortes.
        JobShopSolution solution = withMixedClaims(150, 23L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);

        // Garde de non-vacuité : des revendications qui ne mordraient sur rien laisseraient ce
        // test vrai pour la même raison que T1, c'est-à-dire pour rien.
        FullScoreCalculator free = new FullScoreCalculator();
        free.resetWorkingSolution(FullDataGenerator.generate(150, 23L));
        assertNotEquals(free.calculateScore(), calculator.calculateScore(),
                "les revendications ne déplacent rien sur cette instance — le test serait vacant");

        List<Order> sequence = solution.getScheduleList().get(0).getOrderSequence();
        List<Operation> operations = solution.getOperationList();
        Random random = new Random(29L);

        for (int move = 0; move < 160; move++) {
            int kind = random.nextInt(4);
            if (kind == 0) {
                int a = random.nextInt(sequence.size());
                int b = random.nextInt(sequence.size());
                if (a == b) {
                    continue;
                }
                int from = Math.min(a, b);
                int to = Math.max(a, b) + 1;
                calculator.beforeListVariableChanged(null, "orderSequence", from, to);
                Collections.swap(sequence, a, b);
                calculator.afterListVariableChanged(null, "orderSequence", from, to);
            } else if (kind == 1) {
                Operation op = operations.get(random.nextInt(operations.size()));
                List<Machine> candidates = op.getCompatibleMachines();
                calculator.reassignMachine(op, candidates.get(random.nextInt(candidates.size())));
            } else if (kind == 2) {
                Operation op = operations.get(random.nextInt(operations.size()));
                List<Tooling> pool = op.getCompatibleToolings();
                if (pool.isEmpty()) {
                    continue;
                }
                calculator.reassignTooling(op, pool.get(random.nextInt(pool.size())));
            } else {
                Operation op = operations.get(random.nextInt(operations.size()));
                List<Setter> competent = solution.getSetterList().stream()
                        .filter(setter -> setter.canSetUp(op.getMachine()))
                        .toList();
                calculator.reassignSetter(op, competent.get(random.nextInt(competent.size())));
            }
            HardSoftLongScore incremental = calculator.calculateScore();
            HardSoftLongScore oracle = calculator.fullSweepScore();
            assertEquals(oracle, incremental, "divergence au mouvement " + move
                    + " : incrémental=" + incremental + " oracle=" + oracle + " écart_souple="
                    + (incremental.softScore() - oracle.softScore()));
        }
    }

    @Test
    void theBACKWARDPassMustRespectTheClaimsToo() {
        // T4 — LA PASSE AMONT. Elle date le MÊME plan au plus tard. Si elle ignore les
        // revendications, elle rend des dates au plus tard ANTÉRIEURES aux dates au plus tôt : un
        // battement négatif, c'est-à-dire un plan que les deux passes décrivent différemment.
        // L'invariant « au plus tôt jamais après au plus tard » de BackwardPassTest tombe, et il
        // tombe silencieusement parce qu'aucun test ne l'exerce avec des revendications.
        //
        // Second invariant, celui que la butée existe pour rendre exprimable : une opération
        // datée au plus tard ne doit pas plus chevaucher une revendication qu'une opération datée
        // au plus tôt.
        JobShopSolution solution = withMixedClaims(150, 23L);
        FullScoreCalculator calculator = new FullScoreCalculator();
        calculator.resetWorkingSolution(solution);
        FullScoreCalculator.BackwardSweep backward = calculator.backwardSweep();

        for (Operation op : solution.getOperationList()) {
            int opId = (int) op.getId();
            assertTrue(backward.latestEnd()[opId] >= calculator.endOf(opId),
                    op + " : fin au plus tard " + backward.latestEnd()[opId]
                            + " ANTÉRIEURE à la fin au plus tôt " + calculator.endOf(opId)
                            + " — la passe amont ne voit pas les revendications");
            assertTrue(backward.latestSetupStart()[opId] >= calculator.setupStartOf(opId),
                    op + " : mise en train au plus tard " + backward.latestSetupStart()[opId]
                            + " ANTÉRIEURE à celle au plus tôt " + calculator.setupStartOf(opId));
        }

        int examined = 0;
        for (ResourceClaim claim : solution.getClaimList()) {
            if (claim.getMachineId() == ResourceClaim.NONE) {
                continue;
            }
            for (Operation op : solution.getOperationList()) {
                if ((int) op.getMachineId() != claim.getMachineId()) {
                    continue;
                }
                int opId = (int) op.getId();
                examined++;
                long from = backward.latestSetupStart()[opId];
                long to = backward.latestEnd()[opId];
                assertTrue(to <= claim.getMachineFromEpochSec()
                        || from >= claim.getMachineToEpochSec(),
                        "datée au plus tard, " + op + " tient M" + claim.getMachineId() + " de "
                                + from + " à " + to + " — revendiquée de "
                                + claim.getMachineFromEpochSec() + " à "
                                + claim.getMachineToEpochSec());
            }
        }
        assertTrue(examined > 0, "aucune opération sur une machine revendiquée — test vacant");
    }

    /**
     * La même instance avec des revendications sur les QUATRE familles de ressources.
     *
     * <p>
     * Trois revendications à trois couches — la forme d'un ordre réellement lancé, qui immobilise
     * machine, metteur et outillage à des instants différents mais liés — et quelques
     * mono-couches, qui sont la forme d'une indisponibilité subie.
     */
    private static JobShopSolution withMixedClaims(int orders, long seed) {
        JobShopSolution fresh = FullDataGenerator.generate(orders, seed);
        long origin = fresh.getOriginEpochSec();
        long day = 24L * 3600L;
        List<ResourceClaim> claims = new ArrayList<>();
        List<Operation> operations = fresh.getOperationList();
        for (int i = 0; i < 3; i++) {
            Operation source = operations.get(i * 37 % operations.size());
            claims.add(new ResourceClaim(-1L - i, (int) source.getMachineId(),
                    (int) source.getSetter().getId(),
                    source.getTooling() == null ? ResourceClaim.NONE
                            : (int) source.getTooling().getId(),
                    source.getSetupKey(),
                    origin + 5 * day, origin + (12 + i) * day,
                    origin + 5 * day, origin + (8 + i) * day,
                    origin + 5 * day, origin + (8 + i) * day));
        }
        for (int i = 3; i < 9; i++) {
            Operation source = operations.get(i * 53 % operations.size());
            claims.add(new ResourceClaim(-1L - i, (int) source.getMachineId(), ResourceClaim.NONE,
                    ResourceClaim.NONE, source.getSetupKey(),
                    origin + (20 + i) * day, origin + (26 + i) * day, 0L, 0L, 0L, 0L));
        }
        for (int s = 0; s < 2; s++) {
            claims.add(new ResourceClaim(-100L - s, ResourceClaim.NONE, s, ResourceClaim.NONE, 0,
                    0L, 0L, origin + 30 * day, origin + 34 * day, 0L, 0L));
        }
        return new JobShopSolution(fresh.getOrderList(), operations, fresh.getMachineList(),
                fresh.getSetterList(), fresh.getToolingList(), fresh.getScheduleList(), claims,
                fresh.getSetupMatrix(), origin);
    }

    /**
     * Une opération que rien ne précède : ni sur sa machine, ni dans sa gamme.
     *
     * <p>
     * C'est la seule population sur laquelle le point fixe est OBSERVABLE. Ailleurs, le
     * prédécesseur absorbe le premier saut et la seconde couche est vue dès la première passe.
     */
    private static Operation firstOnItsMachineWithNoChainPredecessor(JobShopSolution solution,
            FullScoreCalculator calculator) {
        for (Operation op : solution.getOperationList()) {
            if (op.getPassIndex() != 0) {
                continue;
            }
            if (calculator.setupStartOf((int) op.getId()) != solution.getOriginEpochSec()) {
                continue;
            }
            return op;
        }
        throw new IllegalStateException(
                "aucune opération première sur sa machine — le test serait vacant");
    }

    /** La même instance, à la graine près, avec les revendications données. */
    private static JobShopSolution withClaims(ResourceClaim... claims) {
        JobShopSolution fresh = FullDataGenerator.generate(ORDERS, SEED);
        return new JobShopSolution(fresh.getOrderList(), fresh.getOperationList(),
                fresh.getMachineList(), fresh.getSetterList(), fresh.getToolingList(),
                fresh.getScheduleList(), List.of(claims), fresh.getSetupMatrix(),
                fresh.getOriginEpochSec());
    }

    private static JobShopSolution withMachineClaim(int machineId, long from, long to,
            int setupKey) {
        return withClaims(new ResourceClaim(-1L, machineId, ResourceClaim.NONE,
                ResourceClaim.NONE, setupKey, from, to, 0L, 0L, 0L, 0L));
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
