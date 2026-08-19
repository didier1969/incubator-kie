package kki.domain.full;

import java.util.List;
import java.util.Random;

import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.phase.custom.CustomPhaseCommand;

/**
 * Les trois décisions de ressource du domaine, rendues exerçables par le solveur.
 *
 * <p>
 * <b>Pourquoi une commande de phase et pas des mouvements.</b> `DEC-KKI-004` : cette version
 * d'OptaPlanner ne fait pas coexister une variable-liste et une variable simple, même portées par
 * des classes d'entités différentes — {@code SolutionDescriptor.countUnassignedValues} applique le
 * descripteur de liste à toutes les entités sans filtrer. Machine, metteur et outillage restent
 * donc des faits, et se réaffectent entre deux phases de recherche par un point d'extension réel
 * ({@link CustomPhaseCommand}), sans toucher {@code optaplanner-core} — ce qu'exige `PIL-KKI-002`.
 *
 * <p>
 * <b>Ce que cette classe EST et n'est pas.</b> C'est le <b>câblage</b> : elle prouve que les
 * mouvements (4), (5), (6) et (7) de `CPT-KKI-010` sont exprimables et exécutables sur ce modèle.
 * Sa règle d'acceptation est une descente stricte — on garde ce qui améliore, on annule le reste.
 * Ce n'est pas un algorithme de recherche : le choix des candidats est un tirage uniforme, sans
 * mémoire ni diversification. Le remplacer par quelque chose de plus fin est un travail
 * d'optimisation, mesurable séparément.
 */
public class ResourceReassignmentPhaseCommand implements CustomPhaseCommand<JobShopSolution> {

    /**
     * Nombre d'essais par appel. Chaque essai repropage un sous-graphe entier — à 17 489
     * opérations, c'est l'ordre du millier de nœuds. Le budget est donc petit par construction,
     * et c'est une dimension du banc, pas une constante de vérité.
     */
    public static int attempts = 300;

    /** Graine fixe : deux exécutions du même banc doivent donner le même verdict. */
    public static long seed = 1_000_003L;

    @Override
    public void changeWorkingSolution(ScoreDirector<JobShopSolution> scoreDirector) {
        JobShopSolution solution = scoreDirector.getWorkingSolution();
        FullScoreCalculator calculator = FullScoreCalculator.LIVE;
        if (calculator == null || calculator.getWorkingSolution() != solution) {
            // Échouer bruyamment plutôt que de réaffecter sur le mauvais directeur de score :
            // le score rapporté serait alors juste, et le plan modifié serait un autre.
            throw new IllegalStateException(
                    "le calculateur vivant ne porte pas la solution de travail de cette phase");
        }

        List<Operation> operations = solution.getOperationList();
        List<Setter> setters = solution.getSetterList();
        Random random = new Random(seed);
        int accepted = 0;

        for (int attempt = 0; attempt < attempts; attempt++) {
            Operation op = operations.get(random.nextInt(operations.size()));
            HardSoftLongScore before = calculator.calculateScore();
            boolean applied = switch (random.nextInt(3)) {
                case 0 -> tryMachine(calculator, op, random);
                case 1 -> trySetter(calculator, op, setters, random);
                default -> tryTooling(calculator, op, random);
            };
            if (!applied) {
                continue;
            }
            // Compté sur le MÊME compteur que le sélecteur : une réaffectation faite par un
            // autre chemin reste une réaffectation. Ne compter que le chemin du sélecteur faisait
            // afficher « reassignments=0 » pour cette variante, ce qui donne une ABSENCE à lire
            // là où il y a une insuffisance — trouvé par l'opérateur sur le relevé de M4.
            CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.incrementAndGet();
            if (calculator.calculateScore().compareTo(before) >= 0) {
                accepted++;
            } else {
                undo(calculator, op);
            }
        }
        // Les faits ont changé sous le directeur de score ; il doit recalculer avant de conclure.
        scoreDirector.triggerVariableListeners();
        ACCEPTED_LAST_RUN = accepted;
    }

    /** Réaffectations retenues au dernier passage — relevé du banc, pas un état de calcul. */
    public static volatile int ACCEPTED_LAST_RUN;

    // La valeur d'avant est mémorisée sur place : annuler, c'est rappeler la même réaffectation
    // avec l'ancienne ressource, donc la même propagation en sens inverse.
    private Machine previousMachine;
    private Setter previousSetter;
    private Tooling previousTooling;

    private boolean tryMachine(FullScoreCalculator calculator, Operation op, Random random) {
        List<Machine> candidates = op.getCompatibleMachines();
        if (candidates.size() < 2) {
            return false;
        }
        Machine target = candidates.get(random.nextInt(candidates.size()));
        if (target == op.getMachine()) {
            return false;
        }
        previousMachine = op.getMachine();
        previousSetter = null;
        previousTooling = null;
        calculator.reassignMachine(op, target);
        return true;
    }

    private boolean trySetter(FullScoreCalculator calculator, Operation op, List<Setter> setters,
            Random random) {
        // Un metteur tiré au hasard n'est compétent qu'une fois sur quelques-unes : on tire
        // jusqu'à en trouver un plutôt que de matérialiser la liste des compétents à chaque
        // essai, ce qui allouerait 300 listes par passage.
        for (int draw = 0; draw < 8; draw++) {
            Setter target = setters.get(random.nextInt(setters.size()));
            if (target == op.getSetter() || !target.canSetUp(op.getMachine())) {
                continue;
            }
            previousSetter = op.getSetter();
            previousMachine = null;
            previousTooling = null;
            calculator.reassignSetter(op, target);
            return true;
        }
        return false;
    }

    private boolean tryTooling(FullScoreCalculator calculator, Operation op, Random random) {
        List<Tooling> pool = op.getCompatibleToolings();
        if (pool.size() < 2) {
            return false;
        }
        Tooling target = pool.get(random.nextInt(pool.size()));
        if (target == op.getTooling()) {
            return false;
        }
        previousTooling = op.getTooling();
        previousMachine = null;
        previousSetter = null;
        calculator.reassignTooling(op, target);
        return true;
    }

    private void undo(FullScoreCalculator calculator, Operation op) {
        if (previousMachine != null) {
            calculator.reassignMachine(op, previousMachine);
        } else if (previousSetter != null) {
            calculator.reassignSetter(op, previousSetter);
        } else if (previousTooling != null) {
            calculator.reassignTooling(op, previousTooling);
        }
    }
}
