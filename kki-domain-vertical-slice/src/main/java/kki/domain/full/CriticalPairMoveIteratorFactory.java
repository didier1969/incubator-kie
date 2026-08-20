package kki.domain.full;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.heuristic.move.Move;
import org.optaplanner.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;

/**
 * Le sélecteur des DEUX mouvements du paradigme — échange de position X, et déplacement d'une
 * opération vers un autre workcenter compatible.
 *
 * <p>
 * Il n'en émettait qu'un. Le second avait été relégué dans une phase exécutée une seule fois, et
 * le rapport mesuré atteignait 2 750 contre 1 : la moitié du jeu de mouvements était absente de
 * la recherche.
 *
 * <p>
 * Le sélecteur uniforme d'OptaPlanner tire deux positions au hasard parmi 5000 : la quasi-totalité
 * de ces paires ne se disputent aucune ressource, l'échange est alors un <b>no-op exact</b> et
 * l'évaluation est payée pour rien. Ici on ne propose que des paires <b>adjacentes et tendues sur
 * une ressource partagée</b> — aucune oisiveté entre les deux opérations, donc l'arc disjonctif
 * est contraignant et l'inverser peut changer le coût.
 *
 * <p>
 * Le filtrage a lieu dans l'itérateur et non dans {@code isMoveDoable} : un candidat écarté ne
 * coûte alors même pas la construction du mouvement.
 *
 * <p>
 * L'état est lu sur le calculateur du directeur de score courant. Recalculer les dates
 * à chaque pas pour choisir un mouvement ruinerait le débit que ce choix sert à améliorer — le
 * pont d'OptaPlanner recrée l'itérateur à chaque pas, les arcs tendus sont donc toujours ceux de
 * l'instant.
 */
public final class CriticalPairMoveIteratorFactory
        implements MoveIteratorFactory<JobShopSolution, Move<JobShopSolution>> {

    /**
     * Bornes de la recherche d'une paire utile. Au-delà, on rend un mouvement quelconque plutôt
     * que rien : un itérateur qui se tarit ferait croire à une convergence alors qu'il n'a
     * simplement pas trouvé de candidat.
     */
    private static final int SAMPLING_ATTEMPTS = 12;

    /**
     * Injecté par OptaPlanner depuis les propriétés du config. {@code false} = tirage uniforme
     * (M1), {@code true} = guidage vers les arcs tendus (M3). Deux modes d'UNE même fabrique
     * plutôt que deux sélecteurs : c'est le même mouvement, l'un au hasard et l'autre en sachant
     * pourquoi, et les comparer exige que le reste soit identique.
     */
    private boolean guided = true;

    public void setGuided(String guided) {
        this.guided = Boolean.parseBoolean(guided);
    }

    /**
     * Part des tirages consacrée au SECOND mouvement — le déplacement d'une opération vers un
     * autre workcenter compatible. Zéro le désactive, ce qui redonne exactement le comportement
     * mesuré jusqu'ici : huit cent mille échanges de position et rien d'autre.
     *
     * <p>
     * C'est une dimension du banc au sens de `DEC-KKI-005`, donc à balayer et jamais à deviner.
     * La valeur par défaut partage le budget également entre les deux mouvements que le paradigme
     * autorise, faute d'une mesure qui dise autre chose.
     */
    private double reassignmentShare = 0.5;

    public void setReassignmentShare(String reassignmentShare) {
        this.reassignmentShare = Double.parseDouble(reassignmentShare);
    }

    /** Comptés pour que la mesure puisse dire si les DEUX mouvements sont réellement tirés. */
    public static final java.util.concurrent.atomic.AtomicLong SWAPS_EMITTED =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong REASSIGNMENTS_EMITTED =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public long getSize(ScoreDirector<JobShopSolution> scoreDirector) {
        return scoreDirector.getWorkingSolution().getOrderList().size();
    }

    @Override
    public Iterator<Move<JobShopSolution>> createOriginalMoveIterator(
            ScoreDirector<JobShopSolution> scoreDirector) {
        throw new UnsupportedOperationException(
                "M3 est un sélecteur aléatoire guidé : l'énumération exhaustive des paires tendues"
                        + " n'a pas de sens à cette échelle.");
    }

    @Override
    public Iterator<Move<JobShopSolution>> createRandomMoveIterator(
            ScoreDirector<JobShopSolution> scoreDirector, Random workingRandom) {
        Schedule schedule = scoreDirector.getWorkingSolution().getScheduleList().get(0);
        int orderCount = schedule.getOrderSequence().size();
        FullScoreCalculator calculator = WorkcenterReassignmentMove.calculatorOf(scoreDirector);
        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Move<JobShopSolution> next() {
                // Le calculateur du directeur QUI NOUS A CRÉÉS, résolu une fois à la
                // construction de l'itérateur. Le champ statique qui vivait ici désignait un
                // directeur quelconque dès que le moteur en tient plusieurs (FULL_ASSERT,
                // multi-thread) : le guidage lisait alors l'état d'une autre solution.
                FullScoreCalculator live = calculator;
                if (workingRandom.nextDouble() < reassignmentShare) {
                    Move<JobShopSolution> reassignment = nextReassignment(live, workingRandom);
                    if (reassignment != null) {
                        return reassignment;
                    }
                    // Aucune réaffectation utile trouvée : on retombe sur l'échange plutôt que
                    // de rendre un itérateur tari, qui se lirait comme une convergence.
                }
                SWAPS_EMITTED.incrementAndGet();
                return nextSwap(live, workingRandom);
            }

            private Move<JobShopSolution> nextReassignment(FullScoreCalculator live,
                    Random random) {
                for (int attempt = 0; attempt < SAMPLING_ATTEMPTS; attempt++) {
                    FullScoreCalculator.Reassignment candidate =
                            live.sampleOverloadedReassignment(random);
                    if (candidate != null) {
                        REASSIGNMENTS_EMITTED.incrementAndGet();
                        return new WorkcenterReassignmentMove(schedule, candidate.operation(),
                                candidate.target());
                    }
                }
                return null;
            }

            private Move<JobShopSolution> nextSwap(FullScoreCalculator live, Random random) {
                if (!guided) {
                    return new CriticalPairSwapMove(schedule,
                            random.nextInt(orderCount), random.nextInt(orderCount));
                }
                for (int attempt = 0; attempt < SAMPLING_ATTEMPTS; attempt++) {
                    Order[] pair = live.sampleTightAdjacentPair(random);
                    if (pair != null) {
                        int left = live.positionOf(pair[0]);
                        int right = live.positionOf(pair[1]);
                        if (left != right) {
                            return new CriticalPairSwapMove(schedule, left, right);
                        }
                    }
                }
                // Repli assumé, même raison.
                return new CriticalPairSwapMove(schedule,
                        random.nextInt(orderCount), random.nextInt(orderCount));
            }
        };
    }
}
