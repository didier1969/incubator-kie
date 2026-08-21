package org.optaplanner.core.impl.domain.variable.listener.support.violation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.optaplanner.core.impl.domain.variable.descriptor.VariableDescriptor;

/**
 * REQ-KKI-047 — le registre doit lever sur le motif exact du défaut corrigé en {@code ca749bfe},
 * et rester muet sur un protocole correct.
 */
class VariableNotificationBalanceLedgerTest {

    private static VariableDescriptor<?> variable(String name) {
        VariableDescriptor<?> variableDescriptor = Mockito.mock(VariableDescriptor.class);
        Mockito.when(variableDescriptor.getVariableName()).thenReturn(name);
        return variableDescriptor;
    }

    /**
     * Le défaut de {@code REQ-KKI-044} : {@code NextElementVariableListener} appelait
     * {@code beforeVariableChanged} deux fois au lieu de {@code before} puis {@code after}.
     * Aucun outil du moteur ne le voyait, parce que la VALEUR écrite était correcte.
     */
    @Test
    void twoOpeningsAndNoClosingIsTheDefectThatShipped() {
        VariableNotificationBalanceLedger ledger = new VariableNotificationBalanceLedger(true);
        VariableDescriptor<?> next = variable("next");
        Object element = new Object();

        ledger.open(next, element);
        ledger.open(next, element);

        assertThatIllegalStateException()
                .isThrownBy(ledger::assertBalancedAndReset)
                .withMessageContaining("next")
                .withMessageContaining("reste ouverte 2 fois");
    }

    @Test
    void theCorrectedProtocolIsSilent() {
        VariableNotificationBalanceLedger ledger = new VariableNotificationBalanceLedger(true);
        VariableDescriptor<?> next = variable("next");
        Object element = new Object();

        ledger.open(next, element);
        ledger.close(next, element);

        assertThatCode(ledger::assertBalancedAndReset).doesNotThrowAnyException();
    }

    /** Le symétrique du défaut : une fermeture orpheline nomme le couple tout de suite. */
    @Test
    void aClosingWithoutItsOpeningNamesTheCoupleImmediately() {
        VariableNotificationBalanceLedger ledger = new VariableNotificationBalanceLedger(true);
        VariableDescriptor<?> previous = variable("previous");

        assertThatIllegalStateException()
                .isThrownBy(() -> ledger.close(previous, new Object()))
                .withMessageContaining("previous")
                .withMessageContaining("sans avoir été ouverte");
    }

    /**
     * Deux entités distinctes portant la MÊME variable ne se compensent pas : sans identité par
     * entité, un before sur l'une et un after sur l'autre s'annuleraient et le défaut passerait.
     */
    @Test
    void twoEntitiesSharingAVariableDoNotOffsetEachOther() {
        VariableNotificationBalanceLedger ledger = new VariableNotificationBalanceLedger(true);
        VariableDescriptor<?> next = variable("next");

        ledger.open(next, new Object());
        assertThatIllegalStateException().isThrownBy(() -> ledger.close(next, new Object()));
    }

    /** Éteint, il ne compte rien et n'alloue rien — c'est la condition de son opt-in. */
    @Test
    void disabledItStaysCompletelySilent() {
        VariableNotificationBalanceLedger ledger = new VariableNotificationBalanceLedger(false);
        VariableDescriptor<?> next = variable("next");
        Object element = new Object();

        ledger.open(next, element);
        ledger.open(next, element);
        ledger.close(next, element);

        assertThatCode(ledger::assertBalancedAndReset).doesNotThrowAnyException();
    }

    /** La vérification remet à zéro : un pas fautif ne contamine pas le suivant. */
    @Test
    void theCheckResetsSoOneBadStepDoesNotPoisonTheNext() {
        VariableNotificationBalanceLedger ledger = new VariableNotificationBalanceLedger(true);
        VariableDescriptor<?> next = variable("next");
        Object element = new Object();

        ledger.open(next, element);
        assertThatIllegalStateException().isThrownBy(ledger::assertBalancedAndReset);
        assertThatCode(ledger::assertBalancedAndReset).doesNotThrowAnyException();
    }
}
