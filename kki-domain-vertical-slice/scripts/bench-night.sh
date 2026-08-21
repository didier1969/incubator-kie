#!/usr/bin/env bash
# Campagne nocturne 2026-08-21 — le banc APS3D à budget RÉEL (900 s), sur les dimensions dont le
# classement n'a jamais été établi au budget qui compte. TERMINÉE : 12/12 runs, résultats dans
# `docs-night-campaign.md`. Conservée telle quelle, elle est rejouable et reprenable.
#
# Tout ce qui a été mesuré avant l'était à 120-180 s. `DEC-KKI-005` fixe la cible à 900 s
# (l'horizon de replanification glisse toutes les 15 min), et la pratique gouvernée #1172 dit
# qu'un verdict ne vaut que pour le régime où il a été pris — trois se sont inversés ici.
#
# La suite est `scripts/bench-lift.sh` (REQ-KKI-045) : lever les réserves à une graine.
set -euo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${MODULE}/scripts/bench-lib.sh"
bench_setup "${MODULE}/target/night" night.tsv

HILL=(-Dkki.acceptor=HILL_CLIMBING)

echo "=== A. critère d'acceptation à 900 s — REQ-KKI-042 était à 120 s ==="
bench_run "A-hill-s42"      42 1.0 GEN 5 "${HILL[@]}"
bench_run "A-lahc5-s42"     42 1.0 GEN 5 -Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=5
bench_run "A-lahc50-s42"    42 1.0 GEN 5 -Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=50
bench_run "A-lahc400-s42"   42 1.0 GEN 5
bench_run "A-hill-s7"        7 1.0 GEN 5 "${HILL[@]}"
bench_run "A-lahc400-s7"     7 1.0 GEN 5

echo "=== B. part du second mouvement à 900 s, sur le meilleur critère connu ==="
bench_run "B-share0.5-s42"  42 0.5 GEN 5 "${HILL[@]}"
bench_run "B-share0.8-s42"  42 0.8 GEN 5 "${HILL[@]}"

echo "=== C. parallélisme — la courbe coût-à-900s selon le nombre de fils (REQ-KKI-036) ==="
bench_run "C-threads4-s42"  42 1.0 GEN 5 "${HILL[@]}" -Dkki.threads=4
bench_run "C-threads8-s42"  42 1.0 GEN 5 "${HILL[@]}" -Dkki.threads=8

echo "=== D. régime METTEUR-GOULOT — là où (6) et (7) devraient enfin payer ==="
# 3 jours ouvrés au lieu de 5 : le metteur devient le goulot, pas la machine.
bench_run "D-goulot-scarce0.0" 42 1.0 GEN 3 "${HILL[@]}" -Dkki.scarceShare=0.0
bench_run "D-goulot-scarce0.3" 42 1.0 GEN 3 "${HILL[@]}" -Dkki.scarceShare=0.3

echo "[$(date -Is)] campagne nocturne terminée — journal ${BENCH_TSV}"
