#!/usr/bin/env bash
# Socle commun des campagnes du banc — classpath, budget, lanceur d'un run (GUI-PRO-013).
#
# SÉRIEL, sans exception. Le calcul de score repropage ~28 % du modèle par mouvement : c'est du
# parcours de pointeurs, hostile au cache. Deux JVM concurrentes se disputent le L3 et peuvent
# INVERSER un classement — un run contendu n'est pas une mesure (pratique gouvernée #1218).
#
# Reprenable : un run dont le journal est déjà non vide est sauté.

BENCH_BUDGET="${BENCH_BUDGET:-900}"   # DEC-KKI-005 : l'horizon de replanification glisse aux 15 min
BENCH_ORDERS="${BENCH_ORDERS:-5000}"  # l'échelle de la cible opérateur

# bench_setup <répertoire de sortie> <nom du journal tsv>
bench_setup() {
  BENCH_OUT="$1"
  BENCH_TSV="${BENCH_OUT}/$2"
  BENCH_MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  export PATH=/home/dstadel/projects/kie/.devenv/profile/bin:$PATH
  cd "${BENCH_MODULE}"
  mkdir -p "${BENCH_OUT}"
  mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
  BENCH_CP="target/classes:$(cat target/cp.txt)"
}

# bench_run <tag> <graine> <part du second mouvement> <départ GEN|EDD> <jours ouvrés metteur> [-D…]
bench_run() {
  local tag="$1" seed="$2" share="$3" start="$4" days="$5"; shift 5
  local log="${BENCH_OUT}/${tag}.log"
  [ -s "${log}" ] && { echo "[$(date -Is)] ${tag} déjà fait"; return 0; }
  echo "[$(date -Is)] ${tag}"
  java "$@" -cp "${BENCH_CP}" kki.domain.full.FullRunner \
      "${BENCH_ORDERS}" "${BENCH_BUDGET}" M5 2.0 "${days}" "${share}" "${start}" "${seed}" \
      > "${log}" 2>"${BENCH_OUT}/${tag}.err" || echo "  ÉCHEC — voir ${tag}.err"
  grep -hE '^(full_baseline|full_result|moves_emitted|cost_breakdown\[arrivee\])' "${log}" \
      | sed "s/^/${tag} /" >> "${BENCH_TSV}" || true
}
