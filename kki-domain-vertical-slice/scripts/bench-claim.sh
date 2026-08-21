#!/usr/bin/env bash
# REQ-KKI-065 V6 — le COÛT de la butée, mesuré et publié quel qu'il soit.
#
# Budget en TRAVAIL, et pas en secondes : le plancher de bruit de l'axe moteur vaut EXACTEMENT
# zéro (REQ-KKI-052, cinq runs identiques, amplitude 0,00 %), tandis que le budget en temps mur
# disperse de 8,43 %. Il n'y a donc aucun seuil à arbitrer ici — tout écart mesuré est réel.
#
# Trois parts et deux graines : une part qui ne bougerait rien se lirait « la butée est gratuite »,
# ce qui serait faux ; une seule graine ne dirait pas si l'écart est de la butée ou de l'instance.
set -euo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${MODULE}/scripts/bench-lib.sh"
BENCH_BUDGET="${SEQ_WORK_BUDGET}"
bench_setup "${MODULE}/target/claim" claim.tsv

WORK=(-Dkki.budgetMode=work)
LAHC5=(-Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=5)

for seed in 42 7; do
  for share in 0.00 0.05 0.15; do
    bench_run "claim${share}-s${seed}" "${seed}" 0.8 GEN 5 \
        "${WORK[@]}" "${LAHC5[@]}" "-Dkki.claimShare=${share}"
  done
done

echo "[$(date -Is)] campagne butée terminée — journal ${BENCH_TSV}"
