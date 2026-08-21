#!/usr/bin/env bash
# REQ-KKI-045, seconde passe — fermer la colonne VIDE de la table du croisement.
#
# La première passe a arrêté deux verdicts : LAHC-5 devient le critère par défaut (2 graines,
# 1,85× d'écart avec le défaut du moteur), et la part 0,8 est RÉFUTÉE comme défaut — sous
# LAHC-5, la part 1,0 l'écrase de 22,5 % puis 40,8 %.
#
#   coût atteint à 900 s     part 0,5     part 0,8     part 1,0
#   graine 42 — HILL         1,458e12     1,196e12     1,488e12
#   graine 42 — LAHC-5          VIDE      1,415e12     1,155e12
#   graine 7  — HILL         1,389e12     1,179e12     1,275e12
#   graine 7  — LAHC-5          VIDE      1,373e12     0,975e12
#
# Ce qui reste ouvert : 1,0 bat 0,8 nettement, mais la FORME de la courbe de part sous le critère
# RETENU n'est pas établie. Rien n'exclut un optimum du côté des parts basses — sous HILL la
# courbe a justement un optimum intérieur. Deux runs ferment la question ; sans eux, le défaut
# 1,0 repose sur une comparaison à deux points.
#
# Le troisième run complète le classement des quatre acceptors sur la graine 7. Il ne décide
# d'aucun défaut : c'est du repli, il tourne en dernier.
#
# Écrit dans le MÊME répertoire que la première passe : bench_run saute tout run déjà journalisé.
set -euo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${MODULE}/scripts/bench-lib.sh"
bench_setup "${MODULE}/target/lift" lift.tsv

LAHC5=(-Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=5)

echo "=== la colonne manquante : part 0,5 sous le critère retenu ==="
bench_run "E-lahc5-share0.5-s42" 42 0.5 GEN 5 "${LAHC5[@]}"
bench_run "E-lahc5-share0.5-s7"   7 0.5 GEN 5 "${LAHC5[@]}"

echo "=== repli : le classement à quatre sur la seconde graine ==="
bench_run "A-lahc50-s7"           7 1.0 GEN 5 -Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=50

echo "[$(date -Is)] seconde passe terminée — journal ${BENCH_TSV}"
