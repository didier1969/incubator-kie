#!/usr/bin/env bash
# REQ-KKI-055 — lecture de la campagne « extrémité part=0,00 », sur les DEUX axes de budget.
#
# RÉUTILISE : scripts/bench-lib.sh (bench_field/bench_have) pour la lecture des journaux.
#   Vérifié via axon query "rapport de campagne banc lecture tsv coût par bras témoin" +
#   inventaire de scripts/ : night-report.sh lit les tags A-/B-/C-/D- de la campagne nocturne et
#   depth-width-report.sh lit les CSV d'optaplanner-benchmark — ni l'un ni l'autre ne connaît les
#   tags de ce lot ni la notion de taxe-de-témoin. Les deux lecteurs communs sont remontés dans
#   bench-lib.sh par ce commit plutôt que recopiés une quatrième fois (GUI-PRO-013).
#
# Séparé du lanceur pour être rejouable sans relancer les deux heures de mesure :
#   scripts/bench-seq-report.sh [dossier]
#
# Trois choses que ce rapport impose, et qui ne sont pas cosmétiques :
#   1. la TAXE DU LOT — l'écart entre les deux témoins identiques — est imprimée AVANT les bras.
#      Tout écart de bras inférieur à cette taxe est de la dérive de charge, pas un traitement.
#   2. le compteur d'ÉCHANGES est affiché par bras. Un bras part=0,00 qui rend swaps=0 mesure
#      autre chose que ce qu'il annonce : c'est exactement le défaut trouvé sur les 14 runs
#      archivés, et il doit crever l'écran sans relire un journal.
#   3. la grille de lecture est imprimée telle qu'elle a été PRÉ-ENREGISTRÉE, jamais reconstruite
#      après coup à partir des chiffres obtenus.
set -uo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${MODULE}/scripts/bench-lib.sh"
BENCH_OUT="${1:-${MODULE}/target/seq}"

# lot <titre> <témoin_entrée> <témoin_sortie> <bras...>
lot() {
  local titre="$1" tin="$2" tout="$3"; shift 3
  echo
  echo "--- ${titre} ---"
  if bench_have "${tin}" && bench_have "${tout}"; then
    awk -v a="$(bench_field "${tin}" full_result end_cost_chf)" \
        -v b="$(bench_field "${tout}" full_result end_cost_chf)" \
        'BEGIN{ t=100*(b-a)/a;
                printf "témoins : %.4e -> %.4e   taxe du lot = %+.2f %%\n", a, b, t;
                printf "          tout écart de bras inférieur à %.2f %% n'\''est PAS interprétable.\n", (t<0?-t:t) }'
  else
    echo "témoins : incomplets — taxe du lot inconnue, aucun écart n'est qualifiable."
  fi

  printf '%-26s %14s %9s %8s %11s %8s %11s\n' \
      bras coût_atteint vs_réf mvt/s sale/prop cpu/mur échanges
  local ref=""
  for tag in "$@"; do
    if ! bench_have "${tag}"; then
      printf '%-26s %14s\n' "${tag}" "$([ -f "${BENCH_OUT}/${tag}.log" ] && echo 'EN COURS' || echo 'non exécuté')"
      continue
    fi
    local cost; cost="$(bench_field "${tag}" full_result end_cost_chf)"
    [ -z "${ref}" ] && ref="${cost}"
    local swaps; swaps="$(bench_field "${tag}" moves_emitted swaps)"
    awk -v t="${tag}" -v c="${cost}" -v r="${ref}" \
        -v m="$(bench_field "${tag}" full_result moves_per_sec)" \
        -v d="$(bench_field "${tag}" full_result dirty_per_propagation)" \
        -v u="$(bench_field "${tag}" full_result cpu_over_wall)" \
        -v s="${swaps:--1}" \
        'BEGIN{ printf "%-26s %14.4e %+8.1f%% %8s %11s %8s %11d%s\n", t, c, 100*(c-r)/r, m, d, u, s,
                (index(t,"part0.0")>0 && s==0 ? "  ⚠️ AUCUN ÉCHANGE — LOT INVALIDE" : "") }'
  done
}

lot "LOT 1 — temps mur (axe PRODUIT, DEC-KKI-005)" \
    T1-avant-part1.0-s42 T1-apres-part1.0-s42 \
    T1-avant-part1.0-s42 S1-time-part0.0-s42 R1-time-part1.0-s7 S1-time-part0.0-s7

lot "LOT 2 — travail égal (axe MOTEUR, REQ-KKI-052) — c'est LUI qui tranche" \
    T2-avant-part1.0-s42 T2-apres-part1.0-s42 \
    T2-avant-part1.0-s42 S2-work-part0.0-s42 R2-work-part1.0-s7 S2-work-part0.0-s7

cat <<'GRILLE'

Grille PRÉ-ENREGISTRÉE (REQ-KKI-055), à ne pas réécrire après lecture :

  LOT 1 pire ET LOT 2 ≈ ou meilleur -> H1. C'est le PRIX de propagation qui évince l'échange.
                                       Correctif : rendre un changement de séquence moins cher
                                       — la voie de REQ-KKI-043, qui a rendu −22,7 % — et PAS
                                       ajouter des opérateurs.
  LOT 1 pire ET LOT 2 encore pire   -> H2. L'opérateur lui-même est faible. Correctif : un
                                       opérateur de séquence à grain plus fin. Le catalogue
                                       générique ouvert par REQ-KKI-040 porte ListChangeMove et
                                       SubListChangeMove ; nous n'avons ni l'un ni l'autre, et
                                       notre seul opérateur de séquence est une transposition.
  LOT 1 : 0,00 meilleur             -> le défaut du banc (part=1,00) est faux, et les 14 runs
                                       archivés à swaps=0 ont mesuré le mauvais réglage.
  un bras part=0,00 à swaps=0       -> lot INVALIDE. Ne rien conclure ; chercher pourquoi
                                       nextSwap n'est pas atteint.
GRILLE
