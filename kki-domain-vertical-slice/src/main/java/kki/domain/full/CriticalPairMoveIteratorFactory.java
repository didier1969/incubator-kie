package kki.domain.full;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;

/**
 * M3 — n'émet que les échanges susceptibles d'améliorer quelque chose.
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
 * L'état est lu sur le calculateur vivant ({@link FullScoreCalculator#LIVE}). Recalculer les dates
 * à chaque pas pour choisir un mouvement ruinerait le débit que ce choix sert à améliorer — le
 * pont d'OptaPlanner recrée l'itérateur à chaque pas, les arcs tendus sont donc toujours ceux de
 * l'instant.
 */
public final class CriticalPairMoveIteratorFactory
        implements MoveIteratorFactory<JobShopSolution, CriticalPairSwapMove> {

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

    @Override
    public long getSize(ScoreDirector<JobShopSolution> scoreDirector) {
        return scoreDirector.getWorkingSolution().getOrderList().size();
    }

    @Override
    public Iterator<CriticalPairSwapMove> createOriginalMoveIterator(
            ScoreDirector<JobShopSolution> scoreDirector) {
        throw new UnsupportedOperationException(
                "M3 est un sélecteur aléatoire guidé : l'énumération exhaustive des paires tendues"
                        + " n'a pas de sens à cette échelle.");
    }

    @Override
    public Iterator<CriticalPairSwapMove> createRandomMoveIterator(
            ScoreDirector<JobShopSolution> scoreDirector, Random workingRandom) {
        Schedule schedule = scoreDirector.getWorkingSolution().getScheduleList().get(0);
        int orderCount = schedule.getOrderSequence().size();
        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public CriticalPairSwapMove next() {
                FullScoreCalculator live = FullScoreCalculator.LIVE;
                if (live == null) {
                    throw new NoSuchElementException("aucun calculateur vivant");
                }
                if (!guided) {
                    return new CriticalPairSwapMove(schedule,
                            workingRandom.nextInt(orderCount), workingRandom.nextInt(orderCount));
                }
                for (int attempt = 0; attempt < SAMPLING_ATTEMPTS; attempt++) {
                    Order[] pair = live.sampleTightAdjacentPair(workingRandom);
                    if (pair != null) {
                        int left = live.positionOf(pair[0]);
                        int right = live.positionOf(pair[1]);
                        if (left != right) {
                            return new CriticalPairSwapMove(schedule, left, right);
                        }
                    }
                }
                // Repli assumé : plutôt un échange quelconque qu'un itérateur tari, qui se
                // lirait comme une convergence.
                return new CriticalPairSwapMove(schedule,
                        workingRandom.nextInt(orderCount), workingRandom.nextInt(orderCount));
            }
        };
    }
}
