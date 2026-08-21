package org.optaplanner.core.impl.domain.variable;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.simple.SimpleScore;
import org.optaplanner.core.impl.domain.variable.descriptor.ListVariableDescriptor;
import org.optaplanner.core.impl.score.director.InnerScoreDirector;
import org.optaplanner.core.impl.testdata.domain.list.shadow_history.TestdataListEntityWithShadowHistory;
import org.optaplanner.core.impl.testdata.domain.list.shadow_history.TestdataListSolutionWithShadowHistory;
import org.optaplanner.core.impl.testdata.domain.list.shadow_history.TestdataListValueWithShadowHistory;
import org.optaplanner.core.impl.testdata.util.PlannerTestUtils;

/**
 * REQ-KKI-047 / REQ-KKI-044 — non-régression du protocole de notification sur le chemin qui a
 * réellement porté le défaut.
 *
 * <p>
 * {@code NextElementVariableListener} et {@code PreviousElementVariableListener} appelaient
 * {@code beforeVariableChanged} deux fois au lieu de {@code before} puis {@code after}, dans
 * {@code afterEntityAdded} et {@code afterEntityRemoved}. Les deux boucles exigent
 * {@code size() >= 2} ; une entité ajoutée arrive normalement avec une liste VIDE, donc le
 * déclencheur réaliste est le RETRAIT d'un porteur de liste détenant encore au moins deux
 * éléments. C'est exactement le scénario ci-dessous.
 *
 * <p>
 * Ce test aurait été ROUGE avant {@code ca749bfe} et vert après. {@code FULL_ASSERT} ne l'aurait
 * pas vu : le {@code setValue} était correct, donc les valeurs des variables ombres l'étaient
 * aussi — seul le COMPTE des notifications était faux.
 */
class VariableNotificationBalanceOnListVariableTest {

    private final ListVariableDescriptor<TestdataListSolutionWithShadowHistory> variableDescriptor =
            TestdataListEntityWithShadowHistory.buildVariableDescriptorForValueList();

    private final InnerScoreDirector<TestdataListSolutionWithShadowHistory, SimpleScore> scoreDirector =
            PlannerTestUtils.mockScoreDirector(
                    variableDescriptor.getEntityDescriptor().getSolutionDescriptor(), true);

    @Test
    void addingAndRemovingAListHolderKeepsEveryNotificationBalanced() {
        TestdataListValueWithShadowHistory a = new TestdataListValueWithShadowHistory("A");
        TestdataListValueWithShadowHistory b = new TestdataListValueWithShadowHistory("B");
        TestdataListValueWithShadowHistory c = new TestdataListValueWithShadowHistory("C");
        TestdataListEntityWithShadowHistory ann = new TestdataListEntityWithShadowHistory("Ann", a, b, c);

        assertThatCode(() -> {
            scoreDirector.setWorkingSolution(ListVariableListenerTest.buildSolution(ann));

            scoreDirector.beforeEntityAdded(ann);
            scoreDirector.afterEntityAdded(ann);
            scoreDirector.triggerVariableListeners();

            // Le déclencheur réel du défaut : retirer un porteur qui détient encore trois éléments.
            scoreDirector.beforeEntityRemoved(ann);
            scoreDirector.afterEntityRemoved(ann);
            scoreDirector.triggerVariableListeners();

            // Déclenchement de chasse. La vérification a lieu à l'ENTRÉE d'un déclenchement, donc
            // les notifications émises par les listeners PENDANT le retrait ne sont contrôlées
            // qu'au tour suivant. Sans cette ligne, le test passerait sans avoir rien vérifié.
            scoreDirector.triggerVariableListeners();
        }).doesNotThrowAnyException();
    }
}
