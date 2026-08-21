#!/usr/bin/env bash
# REQ-KKI-045 — lever les réserves à UNE graine de la campagne du 2026-08-21, et mesurer
# l'interaction que le balayage une-dimension-à-la-fois a laissée dans un trou.
#
# La campagne nocturne a déplacé trois verdicts, tous sur la graine 42 seule. L'écart dépasse le
# bruit inter-graines (±3 %) donc le SENS est établi ; la VALEUR ne l'est pas, et un défaut de
# produit ne se fixe pas sur un point.
#
# Le trou : le volet B a balayé la part du second mouvement SOUS HILL_CLIMBING, or le volet A
# établit que HILL n'est pas le meilleur critère. Changer les deux défauts d'un coup livrerait une
# combinaison qui n'a jamais tourné (pratique gouvernée #942).
set -euo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${MODULE}/scripts/bench-lib.sh"
bench_setup "${MODULE}/target/lift" lift.tsv

LAHC5=(-Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=5)
HILL=(-Dkki.acceptor=HILL_CLIMBING)

echo "=== A. le critère : LAHC-5 tient-il sur une seconde graine ? ==="
# HILL-s7 (1,275e12) et LAHC400-s7 (1,832e12) sont déjà mesurés dans target/night.
bench_run "A-lahc5-s7"           7 1.0 GEN 5 "${LAHC5[@]}"

echo "=== B. la part du second mouvement, sur une seconde graine, même critère qu'au volet B ==="
bench_run "B-share0.5-s7"        7 0.5 GEN 5 "${HILL[@]}"
bench_run "B-share0.8-s7"        7 0.8 GEN 5 "${HILL[@]}"

echo "=== E. l'interaction — les deux gagnants ensemble, ce qu'aucun run n'a encore fait ==="
bench_run "E-lahc5-share0.8-s42" 42 0.8 GEN 5 "${LAHC5[@]}"
bench_run "E-lahc5-share0.8-s7"   7 0.8 GEN 5 "${LAHC5[@]}"

echo "=== D. le régime metteur-goulot (3 jours ouvrés) sur une seconde graine ==="
bench_run "D-goulot-s7-scarce0.0" 7 1.0 GEN 3 "${HILL[@]}" -Dkki.scarceShare=0.0
bench_run "D-goulot-s7-scarce0.3" 7 1.0 GEN 3 "${HILL[@]}" -Dkki.scarceShare=0.3

echo "[$(date -Is)] campagne de confirmation terminée — journal ${BENCH_TSV}"
