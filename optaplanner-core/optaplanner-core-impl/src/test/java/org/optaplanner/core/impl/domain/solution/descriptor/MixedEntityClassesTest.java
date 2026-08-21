package org.optaplanner.core.impl.domain.solution.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.domain.variable.PlanningListVariable;
import org.optaplanner.core.api.domain.variable.PlanningVariable;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;

/**
 * Le fondement de DEC-KKI-004, mis à l'épreuve — REQ-KKI-034.
 *
 * <p>
 * DEC-KKI-004 énonce que cette version d'OptaPlanner ne sait pas faire coexister une entité à
 * variable de LISTE et une entité à variable SIMPLE, sur des classes DIFFÉRENTES. C'est de là que
 * vient tout le contournement : le second mouvement du paradigme ne passe pas par le directeur de
 * score mais par un champ {@code static volatile}, et c'est ce champ statique qui ferme le
 * parallélisme et {@code FULL_ASSERT}.
 *
 * <p>
 * Une décision d'architecture assise sur une limite du moteur doit pouvoir se relire sur une
 * exécution, pas sur un souvenir. Ce test est cette exécution.
 */
/**
 * Publique parce que son modèle de domaine imbriqué est RÉUTILISÉ par
 * {@code GenericListSelectorOnMixedModelTest}, qui vit dans le paquet du sélecteur.
 * Chacun des deux tests reste ainsi dans le paquet de son sujet — le descripteur ici,
 * la fabrique de sélecteur là-bas — au lieu d'être co-localisés par accident.
 */
public class MixedEntityClassesTest {

    @Test
    void twoEntityClassesWithDifferentVariableKindsCountTheirInitializationCorrectly() {
        // Une entité de liste qui porte TOUTES les valeurs de son domaine, et une entité à
        // variable simple déjà affectée : plus rien n'est à initialiser, le compte doit être 0.
        SolutionDescriptor<MixedSolution> descriptor = SolutionDescriptor
                .buildSolutionDescriptor(MixedSolution.class, Sequence.class, Assignment.class);

        Slot left = new Slot(0);
        Slot right = new Slot(1);
        Post post = new Post(0);
        Sequence sequence = new Sequence(List.of(left, right));
        Assignment assignment = new Assignment(post);
        MixedSolution solution = new MixedSolution(List.of(left, right), List.of(post),
                List.of(sequence), List.of(assignment));

        assertEquals(0, descriptor.countUninitialized(solution),
                "toutes les valeurs sont affectées : le moteur doit compter zéro non-initialisé."
                        + " Un compte non nul signifie qu'il applique le descripteur de variable"
                        + " de LISTE à une entité qui n'en déclare pas — c'est le défaut que"
                        + " REQ-KKI-034 vise, et le fondement réel de DEC-KKI-004");
    }

    @PlanningSolution
    public static class MixedSolution {

        @ProblemFactCollectionProperty
        @ValueRangeProvider(id = "slotRange")
        private List<Slot> slotList;

        @ProblemFactCollectionProperty
        @ValueRangeProvider(id = "postRange")
        private List<Post> postList;

        @PlanningEntityCollectionProperty
        private List<Sequence> sequenceList;

        @PlanningEntityCollectionProperty
        private List<Assignment> assignmentList;

        @PlanningScore
        private HardSoftLongScore score;

        public MixedSolution() {
        }

        public MixedSolution(List<Slot> slotList, List<Post> postList, List<Sequence> sequenceList,
                List<Assignment> assignmentList) {
            this.slotList = slotList;
            this.postList = postList;
            this.sequenceList = sequenceList;
            this.assignmentList = assignmentList;
        }

        public List<Slot> getSlotList() {
            return slotList;
        }

        public List<Post> getPostList() {
            return postList;
        }

        public List<Sequence> getSequenceList() {
            return sequenceList;
        }

        public List<Assignment> getAssignmentList() {
            return assignmentList;
        }

        public HardSoftLongScore getScore() {
            return score;
        }

        public void setScore(HardSoftLongScore score) {
            this.score = score;
        }
    }

    /** L'équivalent de {@code Schedule} : la file d'ordres. */
    @PlanningEntity
    public static class Sequence {

        @PlanningListVariable(valueRangeProviderRefs = "slotRange")
        private List<Slot> slots;

        public Sequence() {
        }

        public Sequence(List<Slot> slots) {
            this.slots = slots;
        }

        public List<Slot> getSlots() {
            return slots;
        }

        public void setSlots(List<Slot> slots) {
            this.slots = slots;
        }
    }

    /** L'équivalent d'{@code Operation} : le poste sur lequel elle tourne. */
    @PlanningEntity
    public static class Assignment {

        @PlanningVariable(valueRangeProviderRefs = "postRange")
        private Post post;

        public Assignment() {
        }

        public Assignment(Post post) {
            this.post = post;
        }

        public Post getPost() {
            return post;
        }

        public void setPost(Post post) {
            this.post = post;
        }
    }

    public static class Slot {

        private final int id;

        public Slot(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        @Override
        public String toString() {
            return "Slot" + id;
        }
    }

    public static class Post {

        private final int id;

        public Post(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        @Override
        public String toString() {
            return "Post" + id;
        }
    }
}
