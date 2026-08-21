#!/usr/bin/env bash
# REQ-KKI-055 — l'extrémité manquante : part = 0,00, recherche de SÉQUENCE pure.
#
# Le volet B a mesuré 0,50 et 0,80 ; le défaut du banc est 1,00. Personne n'a jamais pris le
# point où l'échange est le SEUL mouvement. Sans lui, « l'échange ne paie pas » reste une
# extrapolation de trois points intérieurs — et l'audit du 2026-08-21 a montré qu'au réglage
# par défaut la séquence n'est JAMAIS modifiée : 0 échange émis sur 14 runs archivés.
#
# ⚠️ POURQUOI DEUX LOTS, ET PAS UN SEUL EN TEMPS MUR
#
# REQ-KKI-055 pré-enregistre DEUX hypothèses concurrentes, et dit lui-même qu'elles
# « produiraient la même monotonie » :
#   H1 — le PRIX de propagation évince l'échange (un échange salit ≈ 3,5× une réaffectation) ;
#   H2 — l'échange est un mauvais OPÉRATEUR (une transposition déplace DEUX ordres là où un
#        seul devrait bouger).
#
# Sous budget en TEMPS MUR, le bras part=0,00 reçoit mécaniquement ≈ 3,5× moins de mouvements
# que le bras part=1,00, par le seul prix de propagation. Un mauvais résultat y est donc PRÉDIT
# PAR LES DEUX hypothèses : le lot ne sépare rien, et rend une quatrième courbe monotone.
#
# Le lot en TRAVAIL retire ce handicap PAR CONSTRUCTION (REQ-KKI-052, parade #1) : à compte de
# calculs de score égal, le prix de propagation ne peut plus voler de la recherche. Ce qui reste
# est la qualité PROPRE de l'opérateur.
#
#   | lot TEMPS MUR | lot TRAVAIL             | lecture                              |
#   |---------------|-------------------------|--------------------------------------|
#   | 0,00 pire     | 0,00 ≈ 1,00 ou meilleur | H1 — le prix évince                  |
#   | 0,00 pire     | 0,00 encore pire        | H2 — l'opérateur est faible          |
#   | 0,00 meilleur | —                       | le défaut du banc est faux           |
#
# Les deux lots portent chacun leurs propres témoins : ils ne se comparent PAS entre eux
# (deux métriques, deux usages — REQ-KKI-052).
#
# Plan d'expérience DANS chaque lot : A,B,A,B encadré par deux témoins identiques. La charge
# externe de cette machine n'est pas stationnaire ; enchaîner 0,00 puis 1,00 par blocs
# confondrait la dérive avec le traitement. L'écart entre les deux témoins EST la taxe du lot.
#
# Durée ≈ 2 h. NE RIEN LANCER D'AUTRE PENDANT (pratique gouvernée #1218).
set -euo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${MODULE}/scripts/bench-lib.sh"
bench_setup "${MODULE}/target/seq" seq.tsv

LAHC5=(-Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=5)

# Compte de calculs de score du lot TRAVAIL, dimensionné sur le bras le PLUS LENT.
#
# Un compte calibré sur le bras rapide ferait tourner le bras lent 3 à 4× plus longtemps en
# temps mur et ferait exploser le calendrier de la campagne. Dérivation, à partir des débits
# déjà mesurés (REQ-KKI-055, graine 42, LAHC-5, 900 s) :
#   part 1,00 → 707 mvt/s   (d_réaffectation ≈ 1 042 opérations salies)
#   part 0,50 → 260 mvt/s
#   part 0,00 → ≈ 190 mvt/s attendu (d_échange ≈ 3 917, résolu algébriquement sur 4 équations)
# 170 000 ≈ 190 × 900 : le bras lent tient dans ~900 s, le bras rapide en ~240 s.
#
# ⚠️ Cette valeur est une PRÉVISION de débit, pas une mesure. Le journal publie
# `budget_mode=work` et `cpu_over_wall` : si le bras 0,00 dépasse largement 900 s, c'est la
# prévision qui était fausse, pas le lot — la comparaison à compte égal reste valide.
SEQ_WORK_BUDGET="${SEQ_WORK_BUDGET:-170000}"

echo "############ LOT 1 — budget en TEMPS MUR (${BENCH_BUDGET} s) — l'axe PRODUIT (DEC-KKI-005)"

echo "=== témoin d'entrée — la référence part=1,00 mesurée DANS cette session ==="
bench_run "T1-avant-part1.0-s42"  42 1.0 GEN 5 "${LAHC5[@]}"

echo "=== l'extrémité manquante, graine 42 ==="
bench_run "S1-time-part0.0-s42"   42 0.0 GEN 5 "${LAHC5[@]}"

echo "=== la référence à la seconde graine, intercalée ==="
bench_run "R1-time-part1.0-s7"     7 1.0 GEN 5 "${LAHC5[@]}"

echo "=== l'extrémité manquante, graine 7 ==="
bench_run "S1-time-part0.0-s7"     7 0.0 GEN 5 "${LAHC5[@]}"

echo "=== témoin de sortie — l'écart au témoin d'entrée EST le bruit du lot ==="
bench_run "T1-apres-part1.0-s42"  42 1.0 GEN 5 "${LAHC5[@]}"

echo
echo "############ LOT 2 — budget en TRAVAIL (${SEQ_WORK_BUDGET} calculs) — l'axe MOTEUR"
echo "############ C'est CE lot qui sépare H1 (le prix évince) de H2 (l'opérateur est faible)."

BENCH_BUDGET="${SEQ_WORK_BUDGET}"
WORK=(-Dkki.budgetMode=work)

bench_run "T2-avant-part1.0-s42"  42 1.0 GEN 5 "${LAHC5[@]}" "${WORK[@]}"
bench_run "S2-work-part0.0-s42"   42 0.0 GEN 5 "${LAHC5[@]}" "${WORK[@]}"
bench_run "R2-work-part1.0-s7"     7 1.0 GEN 5 "${LAHC5[@]}" "${WORK[@]}"
bench_run "S2-work-part0.0-s7"     7 0.0 GEN 5 "${LAHC5[@]}" "${WORK[@]}"
bench_run "T2-apres-part1.0-s42"  42 1.0 GEN 5 "${LAHC5[@]}" "${WORK[@]}"

echo
echo "=== lecture ==="
"${MODULE}/scripts/bench-seq-report.sh" "${BENCH_OUT}"

echo "[$(date -Is)] campagne séquence terminée — journal ${BENCH_TSV}"
