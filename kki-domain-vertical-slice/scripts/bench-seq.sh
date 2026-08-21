#!/usr/bin/env bash
# REQ-KKI-055 — l'extrémité manquante : part = 0,00, recherche de SÉQUENCE pure, à 900 s.
#
# Le volet B a mesuré 0,50 et 0,80 ; le défaut du banc est 1,00. Personne n'a jamais pris le
# point où l'échange est le SEUL mouvement. Sans lui, « l'échange ne paie pas » reste une
# extrapolation de trois points intérieurs — et l'audit du 2026-08-21 a montré qu'au réglage
# par défaut la séquence n'est JAMAIS modifiée : 0 échange émis sur 14 runs archivés.
#
# Ce que le lot borne :
#   part = 1,00  réaffectation seule    (référence, déjà mesurée mais pas dans CETTE session)
#   part = 0,00  séquence seule         (l'inconnue)
#
# Plan d'expérience : A,B,A,B encadré par deux témoins identiques. La charge externe de cette
# machine n'est pas stationnaire ; enchaîner 0,00 puis 1,00 par blocs confondrait la dérive avec
# le traitement. Les deux témoins disent la taxe payée par le lot.
#
# 5 runs × 900 s ≈ 75 min. NE RIEN LANCER D'AUTRE PENDANT (pratique gouvernée #1218).
set -euo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${MODULE}/scripts/bench-lib.sh"
bench_setup "${MODULE}/target/seq" seq.tsv

LAHC5=(-Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=5)

echo "=== témoin d'entrée — la référence part=1,00 mesurée DANS cette session ==="
bench_run "T-avant-part1.0-s42"  42 1.0 GEN 5 "${LAHC5[@]}"

echo "=== l'extrémité manquante, graine 42 ==="
bench_run "S-part0.0-s42"        42 0.0 GEN 5 "${LAHC5[@]}"

echo "=== la référence à la seconde graine, intercalée ==="
bench_run "R-part1.0-s7"          7 1.0 GEN 5 "${LAHC5[@]}"

echo "=== l'extrémité manquante, graine 7 ==="
bench_run "S-part0.0-s7"          7 0.0 GEN 5 "${LAHC5[@]}"

echo "=== témoin de sortie — l'écart au témoin d'entrée EST le bruit du lot ==="
bench_run "T-apres-part1.0-s42"  42 1.0 GEN 5 "${LAHC5[@]}"

echo "[$(date -Is)] campagne séquence terminée — journal ${BENCH_TSV}"
