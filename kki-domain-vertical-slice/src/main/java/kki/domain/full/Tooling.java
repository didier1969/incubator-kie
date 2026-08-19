package kki.domain.full;

/**
 * Un exemplaire d'outillage du pool partagé — troisième ressource consommée par une mise en
 * train, après la machine et le metteur.
 *
 * <p>
 * `CPT-KKI-006` : la mise en train consomme « potentiellement de l'outillage emprunté à un
 * <b>pool fini partagé</b> ». Le pool est fini, donc deux mises en train qui exigent le même
 * exemplaire ne peuvent pas se chevaucher — c'est exactement la contrainte de capacité du
 * metteur, sur un objet différent.
 *
 * <p>
 * Un outillage a un <b>type</b> : plusieurs clés (article, passe) partagent le même montage, et
 * le pool en détient plusieurs exemplaires. C'est ce qui rend le mouvement (7) de `CPT-KKI-010`
 * — « swap sur outillage partagé » — non trivial : réaffecter un emprunt vers un autre exemplaire
 * du <b>même type</b> est légal, vers un autre type ne l'est pas.
 *
 * <p>
 * <b>Pas de calendrier, et c'est délibéré.</b> Machine et metteur en ont un parce que l'opérateur
 * l'a dit ; l'outillage n'est décrit que comme un pool fini. Lui inventer des heures d'ouverture
 * serait ajouter au modèle une contrainte que l'atelier n'a pas énoncée. Si la mise hors service
 * pour calibrage entre un jour dans le domaine, {@link WorkCalendar} est déjà là et le
 * branchement tient en une ligne — mais ce jour n'est pas venu.
 */
public final class Tooling {

    private final long id;
    private final int type;

    public Tooling(long id, int type) {
        this.id = id;
        this.type = type;
    }

    public long getId() {
        return id;
    }

    /** Les exemplaires interchangeables partagent ce type ; les autres ne le sont pas. */
    public int getType() {
        return type;
    }

    @Override
    public String toString() {
        return "Tool" + id + "(t" + type + ")";
    }
}
