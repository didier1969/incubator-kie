#!/usr/bin/env bash
# Campagne nocturne — le banc APS3D à budget RÉEL (900 s), sur les dimensions dont le classement
# n'a jamais été établi au budget qui compte.
#
# Tout ce qui a été mesuré jusqu'ici l'a été à 120-180 s. `DEC-KKI-005` fixe la cible à 900 s
# (l'horizon de replanification glisse toutes les 15 min), et la pratique gouvernée #1172 dit
# qu'un verdict ne vaut que pour le régime où il a été pris — deux se sont déjà inversés ici.
#
# SÉRIEL, sans exception. Le calcul de score repropage ~28 % du modèle par mouvement : c'est du
# parcours de pointeurs, hostile au cache. Deux JVM concurrentes se disputent le L3 et peuvent
# INVERSER un classement. Un run contendu n'est pas une mesure.
set -euo pipefail

MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${MODULE}/target/night"
export PATH=/home/dstadel/projects/kie/.devenv/profile/bin:$PATH
cd "${MODULE}"
mkdir -p "${OUT}"

mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt)"
BUDGET=900
ORDERS=5000

run() {  # tag, graine, part_reassign, depart, [-D...]
  local tag="$1" seed="$2" share="$3" start="$4"; shift 4
  local log="${OUT}/${tag}.log"
  [ -s "${log}" ] && { echo "[$(date -Is)] ${tag} déjà fait"; return 0; }
  echo "[$(date -Is)] ${tag}"
  java "$@" -cp "${CP}" kki.domain.full.FullRunner \
      "${ORDERS}" "${BUDGET}" M5 2.0 5 "${share}" "${start}" "${seed}" \
      > "${log}" 2>"${OUT}/${tag}.err" || echo "  ÉCHEC — voir ${tag}.err"
  grep -hE '^(full_baseline|full_result|moves_emitted|cost_breakdown\[arrivee\])' "${log}" \
      | sed "s/^/${tag} /" >> "${OUT}/night.tsv" || true
}

echo "=== A. critère d'acceptation à 900 s — REQ-KKI-042 était à 120 s ==="
run "A-hill-s42"      42 1.0 GEN -Dkki.acceptor=HILL_CLIMBING
run "A-lahc5-s42"     42 1.0 GEN -Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=5
run "A-lahc50-s42"    42 1.0 GEN -Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=50
run "A-lahc400-s42"   42 1.0 GEN
run "A-hill-s7"        7 1.0 GEN -Dkki.acceptor=HILL_CLIMBING
run "A-lahc400-s7"     7 1.0 GEN

echo "=== B. part du second mouvement à 900 s, sur le meilleur critère connu ==="
run "B-share0.5-s42"  42 0.5 GEN -Dkki.acceptor=HILL_CLIMBING
run "B-share0.8-s42"  42 0.8 GEN -Dkki.acceptor=HILL_CLIMBING

echo "=== C. parallélisme — la courbe coût-à-900s selon le nombre de fils (REQ-KKI-036) ==="
run "C-threads4-s42"  42 1.0 GEN -Dkki.acceptor=HILL_CLIMBING -Dkki.threads=4
run "C-threads8-s42"  42 1.0 GEN -Dkki.acceptor=HILL_CLIMBING -Dkki.threads=8

echo "=== D. régime METTEUR-GOULOT — là où (6) et (7) devraient enfin payer ==="
# 3 jours ouvrés au lieu de 5 : le metteur devient le goulot, pas la machine.
runDays() {
  local tag="$1" scarce="$2"
  local log="${OUT}/${tag}.log"
  [ -s "${log}" ] && { echo "[$(date -Is)] ${tag} déjà fait"; return 0; }
  echo "[$(date -Is)] ${tag}"
  java -Dkki.acceptor=HILL_CLIMBING -Dkki.scarceShare="${scarce}" -cp "${CP}" \
      kki.domain.full.FullRunner "${ORDERS}" "${BUDGET}" M5 2.0 3 1.0 GEN 42 \
      > "${log}" 2>"${OUT}/${tag}.err" || echo "  ÉCHEC"
  grep -hE '^(full_result|moves_emitted|cost_breakdown\[arrivee\])' "${log}" \
      | sed "s/^/${tag} /" >> "${OUT}/night.tsv" || true
}
runDays "D-goulot-scarce0.0" 0.0
runDays "D-goulot-scarce0.3" 0.3

echo "[$(date -Is)] campagne nocturne terminée — journal ${OUT}/night.tsv"
