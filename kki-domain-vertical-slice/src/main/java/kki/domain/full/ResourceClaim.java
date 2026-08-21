package kki.domain.full;

/**
 * Une occupation de ressource à dates FIXES, que le solveur subit et ne décide pas.
 *
 * <p>
 * <b>Pourquoi ce n'est pas un trou de calendrier.</b> {@link WorkCalendar} exprime « la ressource
 * est FERMÉE, son état est PRÉSERVÉ » — une nuit, un week-end, une maintenance : l'article reste
 * monté sur la broche, et rien n'est à remonter au matin. Une revendication exprime l'inverse :
 * « la ressource est PRISE, son état est DÉTRUIT ». Un autre travail y est passé, il a laissé SON
 * article, et l'opération suivante doit payer une remise en train réelle.
 *
 * <p>
 * Confondre les deux coûte de l'argent dans les deux sens, et le défaut est invisible : sous
 * {@code FULL_ASSERT} les deux directeurs de score exécutent le même calcul, donc s'accordent sur
 * le même chiffre faux. Mesuré sur une seule opération du banc : 3 020,69 CHF de mise en train
 * facturés zéro, et 2 880,00 CHF d'immobilisation facturés à une machine qui usinait
 * productivement. Voir {@code REQ-KKI-064}.
 *
 * <p>
 * <b>Une cause, trois couches.</b> Une maintenance machine ne bloque que la machine ; des vacances
 * de metteur ne bloquent que le metteur — ce sont des intervalles sans identité, et leur place est
 * dans le calendrier de LEUR ressource. Une opération réelle, elle, immobilise les trois à des
 * instants DIFFÉRENTS mais LIÉS : le metteur pendant la mise en train, l'outillage jusqu'à la fin
 * de cette mise en train (RENDU-OUTILLAGE), la machine depuis l'attente du metteur jusqu'à la fin
 * de l'usinage. Les écrire comme trois indisponibilités indépendantes détruirait ce qui les relie —
 * et l'identité est précisément ce qui permettra de RETIRER la revendication quand l'atelier
 * avancera. Une maintenance n'a jamais besoin d'être retirée ; un ordre lancé se termine toujours.
 *
 * <p>
 * <b>Les fins sont DÉRIVÉES, jamais publiées.</b> L'appelant (un MES, un générateur de banc) émet
 * un début et un reste-à-faire ; l'ingestion calcule chaque fin par {@code occupancyEnd} sur le
 * calendrier de sa PROPRE ressource. Le moteur ne cède jamais sa compétence calendaire à son
 * appelant : une fin publiée en temps mur ignorerait le calendrier du metteur, qui est le plus
 * contraignant du modèle.
 *
 * <p>
 * Immuable et jamais mutée en cours de résolution : le cloner la partage par référence, comme
 * {@link Machine}, {@link Setter} et {@link SetupMatrix}.
 *
 * <p>
 * Conception : {@code DEC-KKI-013}. Mise en œuvre : {@code REQ-KKI-065}.
 */
public final class ResourceClaim {

    /** Ressource non empruntée par cette revendication. */
    public static final int NONE = -1;

    private final long orderId;

    private final int machineId;
    private final int setterId;
    private final int toolingId;

    /**
     * L'article (article, passe) laissé MONTÉ sur la machine à la fin de cette revendication.
     *
     * <p>
     * C'est ce qu'un trou de calendrier ne peut pas porter, et c'est ce qui fait la différence
     * chiffrée : quand l'opération suivante demande le même article, la remise en train vaut
     * ZÉRO — {@code SetupMatrix.secondsBetween(k, k)} — au lieu d'un démarrage à froid fictif.
     */
    private final int setupKey;

    private final long machineFromEpochSec;
    private final long machineToEpochSec;
    private final long setterFromEpochSec;
    private final long setterToEpochSec;
    private final long toolingFromEpochSec;
    private final long toolingToEpochSec;

    public ResourceClaim(long orderId, int machineId, int setterId, int toolingId, int setupKey,
            long machineFromEpochSec, long machineToEpochSec,
            long setterFromEpochSec, long setterToEpochSec,
            long toolingFromEpochSec, long toolingToEpochSec) {
        this.orderId = orderId;
        this.machineId = machineId;
        this.setterId = setterId;
        this.toolingId = toolingId;
        this.setupKey = setupKey;
        this.machineFromEpochSec = machineFromEpochSec;
        this.machineToEpochSec = machineToEpochSec;
        this.setterFromEpochSec = setterFromEpochSec;
        this.setterToEpochSec = setterToEpochSec;
        this.toolingFromEpochSec = toolingFromEpochSec;
        this.toolingToEpochSec = toolingToEpochSec;
    }

    public long getOrderId() {
        return orderId;
    }

    public int getMachineId() {
        return machineId;
    }

    public int getSetterId() {
        return setterId;
    }

    public int getToolingId() {
        return toolingId;
    }

    public int getSetupKey() {
        return setupKey;
    }

    public long getMachineFromEpochSec() {
        return machineFromEpochSec;
    }

    public long getMachineToEpochSec() {
        return machineToEpochSec;
    }

    public long getSetterFromEpochSec() {
        return setterFromEpochSec;
    }

    public long getSetterToEpochSec() {
        return setterToEpochSec;
    }

    public long getToolingFromEpochSec() {
        return toolingFromEpochSec;
    }

    public long getToolingToEpochSec() {
        return toolingToEpochSec;
    }

    /**
     * Fin de la revendication sur la couche demandée, ou {@code Long.MIN_VALUE} si elle n'emprunte
     * pas cette couche — une valeur qu'un {@code Math.max} absorbe sans branche.
     */
    public long freeAtOn(int machine, int setter, int tooling) {
        long freeAt = Long.MIN_VALUE;
        if (machineId != NONE && machineId == machine) {
            freeAt = Math.max(freeAt, machineToEpochSec);
        }
        if (setterId != NONE && setterId == setter) {
            freeAt = Math.max(freeAt, setterToEpochSec);
        }
        if (toolingId != NONE && toolingId == tooling) {
            freeAt = Math.max(freeAt, toolingToEpochSec);
        }
        return freeAt;
    }

    @Override
    public String toString() {
        return "Claim(order=" + orderId + " M" + machineId + "[" + machineFromEpochSec + ".."
                + machineToEpochSec + "] S" + setterId + "[" + setterFromEpochSec + ".."
                + setterToEpochSec + "] T" + toolingId + "[" + toolingFromEpochSec + ".."
                + toolingToEpochSec + "] key=" + setupKey + ")";
    }
}
