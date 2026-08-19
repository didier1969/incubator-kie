package kki.domain.mseq;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.heuristic.move.AbstractMove;
import org.optaplanner.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;

import kki.domain.full.Operation;

/**
 * M3 au sens strict — inverser UN arc disjonctif, en échangeant deux opérations <b>consécutives</b>
 * dans la file d'une ressource.
 *
 * <p>
 * C'est le mouvement dont la littérature d'atelier garantit qu'il ne crée pas de cycle lorsque
 * l'arc inversé est sur un chemin le plus long (Van Laarhoven, Aarts &amp; Lenstra). Les échanges
 * et déplacements génériques d'OptaPlanner, eux, prennent deux positions quelconques dans deux
 * files quelconques : mesuré sur cette instance, <b>58,7 % d'entre eux produisent un plan
 * impossible</b>. Le solveur passe alors son budget à sortir de l'infaisable au lieu d'améliorer
 * quoi que ce soit.
 *
 * <p>
 * Restreindre à l'adjacence est donc ce qui rend cette représentation utilisable — et non un
 * réglage de confort.
 */
public final class AdjacentSwapMoveIteratorFactory
        implements MoveIteratorFactory<MachineSeqSolution, AdjacentSwapMoveIteratorFactory.AdjacentSwapMove> {

    @Override
    public long getSize(ScoreDirector<MachineSeqSolution> scoreDirector) {
        return scoreDirector.getWorkingSolution().getOperationList().size();
    }

    @Override
    public Iterator<AdjacentSwapMove> createOriginalMoveIterator(
            ScoreDirector<MachineSeqSolution> scoreDirector) {
        throw new UnsupportedOperationException("sélecteur aléatoire uniquement");
    }

    @Override
    public Iterator<AdjacentSwapMove> createRandomMoveIterator(
            ScoreDirector<MachineSeqSolution> scoreDirector, Random workingRandom) {
        List<MachineSequence> sequences = scoreDirector.getWorkingSolution().getSequenceList();
        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public AdjacentSwapMove next() {
                for (int attempt = 0; attempt < 16; attempt++) {
                    MachineSequence sequence = sequences.get(workingRandom.nextInt(sequences.size()));
                    int size = sequence.getOperations().size();
                    if (size < 2) {
                        continue;
                    }
                    int index = workingRandom.nextInt(size - 1);
                    if (sequence.getOperations().get(index).getOrder()
                            != sequence.getOperations().get(index + 1).getOrder()) {
                        return new AdjacentSwapMove(sequence, index);
                    }
                }
                return new AdjacentSwapMove(sequences.get(0), -1);
            }
        };
    }

    /** Échange deux opérations consécutives d'une même file. Sa propre inverse. */
    public static final class AdjacentSwapMove extends AbstractMove<MachineSeqSolution> {

        private final MachineSequence sequence;
        private final int index;

        AdjacentSwapMove(MachineSequence sequence, int index) {
            this.sequence = sequence;
            this.index = index;
        }

        @Override
        public boolean isMoveDoable(ScoreDirector<MachineSeqSolution> scoreDirector) {
            return index >= 0 && index + 1 < sequence.getOperations().size();
        }

        @Override
        protected AbstractMove<MachineSeqSolution> createUndoMove(
                ScoreDirector<MachineSeqSolution> scoreDirector) {
            return new AdjacentSwapMove(sequence, index);
        }

        @Override
        protected void doMoveOnGenuineVariables(ScoreDirector<MachineSeqSolution> scoreDirector) {
            scoreDirector.beforeListVariableChanged(sequence, "operations", index, index + 2);
            Collections.swap(sequence.getOperations(), index, index + 1);
            scoreDirector.afterListVariableChanged(sequence, "operations", index, index + 2);
        }

        @Override
        public Collection<?> getPlanningEntities() {
            return Collections.singletonList(sequence);
        }

        @Override
        public Collection<?> getPlanningValues() {
            List<Operation> operations = sequence.getOperations();
            return List.of(operations.get(index), operations.get(index + 1));
        }

        @Override
        public String toString() {
            return "AdjacentSwap(" + sequence + "@" + index + ")";
        }
    }
}
