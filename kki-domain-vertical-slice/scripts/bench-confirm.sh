#!/usr/bin/env bash
# Étape D — confirmation à 900 s des points retenus par la sonde de forme (REQ-KKI-033).
# Sérielle, comme le reste de la campagne.
set -euo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${MODULE}/target/campaign}"
START="$(cat "${OUT}/best_start" 2>/dev/null || echo GEN)"
export PATH=/home/dstadel/projects/kie/.devenv/profile/bin:$PATH
cd "${MODULE}"
CP="target/classes:$(cat target/cp.txt)"
for spec in "1.0 42" "0.70 42" "1.0 7"; do
  set -- ${spec}
  tag="D-share$1-seed$2"
  echo "[$(date -Is)] ${tag} : 5000 ordres, 900 s, part=$1, départ=${START}, graine=$2"
  java -cp "${CP}" kki.domain.full.FullRunner 5000 900 M5 2.0 5 "$1" "${START}" "$2" \
      > "${OUT}/${tag}.log" 2>"${OUT}/${tag}.err"
  grep -hE '^(full_result|moves_emitted)' "${OUT}/${tag}.log" | sed "s/^/${tag} /" \
      | tee -a "${OUT}/campaign.tsv"
done
echo "[$(date -Is)] étape D terminée"
