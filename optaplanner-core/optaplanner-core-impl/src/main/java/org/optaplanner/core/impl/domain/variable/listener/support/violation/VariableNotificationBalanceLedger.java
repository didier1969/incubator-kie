package org.optaplanner.core.impl.domain.variable.listener.support.violation;

import java.util.IdentityHashMap;
import java.util.Map;

import org.optaplanner.core.impl.domain.variable.descriptor.VariableDescriptor;

/**
 * REQ-KKI-047 — compte les notifications ouvertes par couple (entité, variable) et refuse qu'un
 * pas se termine sur un déséquilibre.
 *
 * <p>
 * <b>Le défaut que ce registre attrape.</b> Un {@code VariableListener} qui appelle deux fois
 * {@code beforeVariableChanged} au lieu de {@code before} puis {@code after} laisse un
 * {@code IncrementalScoreCalculator} avec deux ouvertures et aucune fermeture — violation directe
 * de son contrat. Quatre occurrences de ce copier-coller ont vécu dans
 * {@code NextElementVariableListener} et {@code PreviousElementVariableListener}.
 *
 * <p>
 * <b>Pourquoi aucun outil existant ne le voyait.</b> {@code EnvironmentMode.FULL_ASSERT} compare
 * le score incrémental à un recalcul complet : il vérifie les VALEURS des variables ombres. Le
 * {@code setValue} étant correct, les valeurs étaient bonnes et le défaut restait latent. Ce
 * registre ne regarde pas les valeurs, il regarde le PROTOCOLE.
 *
 * <p>
 * <b>Pourquoi pas la file de notifications.</b> Compter sur {@code AbstractNotifiable} ne marche
 * dans aucun des deux sens : la file par défaut est un {@code ArrayDeque}, dont {@code add} rend
 * toujours {@code true}, si bien que deux ouvertures y entrent comme deux notifications distinctes
 * et paraissent équilibrées. Le seul point qui porte le contrat est la frontière du directeur de
 * score, là où l'appel de l'utilisateur passe.
 *
 * <p>
 * <b>Coût.</b> Nul quand le registre est éteint : {@link #open} et {@link #close} rendent la main
 * sans allouer, et la table n'est créée qu'au premier couple ouvert. C'est un mode de diagnostic,
 * pas un chemin chaud — il n'est jamais armé par {@code EnvironmentMode}.
 *
 * <p>
 * Ce registre n'est pas thread-safe, et n'a pas à l'être : chaque fil de résolution possède son
 * propre directeur de score, donc son propre registre.
 */
public final class VariableNotificationBalanceLedger {

    private final boolean enabled;
    private Map<Object, Map<VariableDescriptor<?>, int[]>> openByEntity;

    public VariableNotificationBalanceLedger(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Une notification d'ouverture vient d'être émise pour ce couple. */
    public void open(VariableDescriptor<?> variableDescriptor, Object entity) {
        if (!enabled) {
            return;
        }
        if (openByEntity == null) {
            openByEntity = new IdentityHashMap<>();
        }
        openByEntity.computeIfAbsent(entity, ignored -> new IdentityHashMap<>())
                .computeIfAbsent(variableDescriptor, ignored -> new int[1])[0]++;
    }

    /**
     * La fermeture correspondante vient d'être émise.
     *
     * <p>
     * Une fermeture sans ouverture lève immédiatement : c'est le symétrique du défaut visé, et le
     * signaler ici nomme le couple fautif, alors qu'attendre la fin du pas ne le nommerait pas.
     */
    public void close(VariableDescriptor<?> variableDescriptor, Object entity) {
        if (!enabled) {
            return;
        }
        int[] count = openByEntity == null ? null
                : openByEntity.getOrDefault(entity, Map.of()).get(variableDescriptor);
        if (count == null || count[0] == 0) {
            throw new IllegalStateException(describe(variableDescriptor, entity)
                    + " est fermée sans avoir été ouverte : le listener émet un after sans son"
                    + " before, et le calculateur de score incrémental reçoit un appel orphelin.");
        }
        count[0]--;
    }

    /**
     * Vérifie qu'aucun couple ne reste ouvert, puis remet le registre à zéro.
     *
     * <p>
     * S'appelle à l'ENTRÉE d'un déclenchement de listeners, pas seulement à la sortie. Plusieurs
     * chemins du moteur laissent un couple ouvert en propageant une exception venue du calculateur
     * de l'utilisateur ; vérifier seulement en sortie ferait qu'un faux déséquilibre MASQUE
     * l'exception d'origine — exactement le mal que ce registre existe pour éviter.
     */
    public void assertBalancedAndReset() {
        if (!enabled || openByEntity == null) {
            return;
        }
        for (Map.Entry<Object, Map<VariableDescriptor<?>, int[]>> byEntity : openByEntity.entrySet()) {
            for (Map.Entry<VariableDescriptor<?>, int[]> byVariable : byEntity.getValue().entrySet()) {
                int open = byVariable.getValue()[0];
                if (open != 0) {
                    Object entity = byEntity.getKey();
                    VariableDescriptor<?> variableDescriptor = byVariable.getKey();
                    openByEntity = null;
                    throw new IllegalStateException(describe(variableDescriptor, entity)
                            + " reste ouverte " + open + " fois à la frontière du pas."
                            + " Un listener a émis before sans le after correspondant :"
                            + " le calculateur de score incrémental voit un changement annoncé"
                            + " et jamais confirmé.");
                }
            }
        }
        openByEntity = null;
    }

    /** Abandonne l'état courant sans rien vérifier — pour une remise à zéro de solution. */
    public void clear() {
        openByEntity = null;
    }

    private static String describe(VariableDescriptor<?> variableDescriptor, Object entity) {
        return "La variable (" + variableDescriptor.getVariableName() + ") de l'entité (" + entity + ")";
    }
}
