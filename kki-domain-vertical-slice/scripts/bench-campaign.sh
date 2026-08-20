#!/usr/bin/env bash
# Campagne de mesure du banc APS3D.
#
#   A. la référence de départ, sur plusieurs graines      (REQ-KKI-032)
#   B. le coût atteint à 900 s depuis chaque départ        (REQ-KKI-032)
#   C. sonde de FORME sur reassignmentShare, budget court  (REQ-KKI-033)
#
# Les runs sont SÉRIELS et jamais concurrents : `FullScoreCalculator` repropage ~23 % du
# modèle par mouvement — du parcours de pointeurs, hostile au cache. Deux JVM en parallèle
# ne se pénalisent pas « également », elles se disputent le L3 et la bande passante, et
# peuvent inverser le classement du paramètre balayé.
#
#   usage : scripts/bench-campaign.sh [dossier_de_sortie]
set -euo pipefail

MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${MODULE}/target/campaign}"
ORDERS=5000
BUDGET=900
PROBE=180
SKEW=2.0
DAYS=5
SHARE=0.5

export PATH=/home/dstadel/projects/kie/.devenv/profile/bin:$PATH
cd "${MODULE}"
mkdir -p "${OUT}"

mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt)"

# Un run = une ligne de journal nommée. Sans le nom, deux runs identiques en apparence ne se
# distinguent plus une fois le terminal fermé.
run() {
  local tag="$1" seconds="$2" share="$3" start="$4" seed="$5"
  local log="${OUT}/${tag}.log"
  echo "[$(date -Is)] ${tag} : ${ORDERS} ordres, ${seconds} s, part=${share}, départ=${start}, graine=${seed}"
  java -cp "${CP}" kki.domain.full.FullRunner \
      "${ORDERS}" "${seconds}" M5 "${SKEW}" "${DAYS}" "${share}" "${start}" "${seed}" \
      > "${log}" 2>"${OUT}/${tag}.err"
  grep -hE '^(full_baseline|full_result|moves_emitted)' "${log}" | sed "s/^/${tag} /" \
      | tee -a "${OUT}/campaign.tsv"
}

field() { grep -m1 "^full_result" "${OUT}/$1.log" | tr ' ' '\n' | grep "^$2=" | cut -d= -f2; }

echo "=== A. la référence de départ, ${ORDERS} ordres, budget minimal ==="
for seed in 42 7 13 29 101 1969; do
  run "A-baseline-seed${seed}" 1 "${SHARE}" EDD "${seed}"
done

echo "=== B. coût atteint à ${BUDGET} s depuis chaque départ ==="
for seed in 42 7; do
  for start in EDD GEN; do
    run "B-${start}-seed${seed}" "${BUDGET}" "${SHARE}" "${start}" "${seed}"
  done
done

# Le départ retenu pour la suite est celui qui rend le plan le moins cher à budget égal,
# moyenné sur les deux graines — pas celui qu'on attendait.
edd=$(( $(field B-EDD-seed42 end_cost_chf | cut -d. -f1) + $(field B-EDD-seed7 end_cost_chf | cut -d. -f1) ))
gen=$(( $(field B-GEN-seed42 end_cost_chf | cut -d. -f1) + $(field B-GEN-seed7 end_cost_chf | cut -d. -f1) ))
BEST_START=EDD
[ "${gen}" -lt "${edd}" ] && BEST_START=GEN
echo "départ retenu pour la sonde : ${BEST_START} (EDD=${edd} vs GEN=${gen}, somme des deux graines)"
echo "${BEST_START}" > "${OUT}/best_start"

echo "=== C. sonde de FORME sur reassignmentShare — ${PROBE} s, PAS une mesure ==="
for share in 0.0 0.15 0.30 0.50 0.70 1.0; do
  run "C-share${share}" "${PROBE}" "${share}" "${BEST_START}" 42
done

echo "[$(date -Is)] campagne A/B/C terminée — journal : ${OUT}/campaign.tsv"
