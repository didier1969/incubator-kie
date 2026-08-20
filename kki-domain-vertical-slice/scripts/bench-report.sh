#!/usr/bin/env bash
# Rend la campagne lisible : une table par étape, coûts ABSOLUS.
#
# Un pourcentage de réduction n'est PAS comparable d'un départ à l'autre — il a deux
# dénominateurs différents. Ce qui se compare, c'est le coût atteint à budget égal.
#
#   usage : scripts/bench-report.sh [dossier_de_campagne]
set -euo pipefail

OUT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/target/campaign}"

value() { tr ' ' '\n' < /dev/stdin | grep -m1 "^$1=" | cut -d= -f2- || true; }
of() { grep -m1 "^$2" "${OUT}/$1.log" 2>/dev/null | value "$3"; }

echo "== A. le départ EDD contre l'ordre de génération, au coût de départ =="
printf '%-8s %16s %16s %8s\n' graine génération EDD rapport
for log in "${OUT}"/A-baseline-seed*.log; do
  [ -e "${log}" ] || continue
  tag="$(basename "${log}" .log)"
  printf '%-8s %16.4e %16.4e %8s\n' "${tag##*seed}" \
      "$(of "${tag}" full_baseline generation_order_chf)" \
      "$(of "${tag}" full_baseline earliest_due_date_chf)" \
      "$(of "${tag}" full_baseline edd_over_generation_x)"
done

echo
echo "== B. coût ATTEINT à 900 s, par départ et par graine =="
printf '%-6s %-6s %16s %16s %10s %10s\n' départ graine départ_chf arrivée_chf réduction mvt/s
for log in "${OUT}"/B-*.log; do
  [ -e "${log}" ] || continue
  tag="$(basename "${log}" .log)"
  start="$(of "${tag}" full_result start)"
  printf '%-6s %-6s %16.4e %16.4e %9s%% %10s\n' "${start}" "$(of "${tag}" full_result seed)" \
      "$(of "${tag}" full_result start_cost_chf)" "$(of "${tag}" full_result end_cost_chf)" \
      "$(of "${tag}" full_result soft_reduction_pct)" "$(of "${tag}" full_result moves_per_sec)"
done

echo
echo "== B'. d'où vient le coût au départ, par départ =="
printf '%-14s %12s %12s %12s %12s %10s\n' run retard avance mise_en_train machine_idle horizon_j
for log in "${OUT}"/B-*.log; do
  [ -e "${log}" ] || continue
  tag="$(basename "${log}" .log)"
  printf '%-14s %12s %12s %12s %12s %10s\n' "${tag#B-}" \
      "$(of "${tag}" 'cost_breakdown\[depart\]' tardiness_chf)" \
      "$(of "${tag}" 'cost_breakdown\[depart\]' earliness_chf)" \
      "$(of "${tag}" 'cost_breakdown\[depart\]' setter_chf)" \
      "$(of "${tag}" 'cost_breakdown\[depart\]' machine_idle_chf)" \
      "$(of "${tag}" 'resources\[depart\]' horizon_d)"
done

echo
echo "== C. sonde de FORME sur la part du second mouvement (budget court — PAS une mesure) =="
printf '%-8s %16s %10s %10s %14s\n' part arrivée_chf réduction mvt/s ratio_émis
for log in "${OUT}"/C-share*.log; do
  [ -e "${log}" ] || continue
  tag="$(basename "${log}" .log)"
  swaps="$(of "${tag}" moves_emitted swaps)"
  reass="$(of "${tag}" moves_emitted reassignments)"
  printf '%-8s %16.4e %9s%% %10s %14s\n' "${tag#C-share}" \
      "$(of "${tag}" full_result end_cost_chf)" \
      "$(of "${tag}" full_result soft_reduction_pct)" \
      "$(of "${tag}" full_result moves_per_sec)" \
      "$(awk -v r="${reass:-0}" -v s="${swaps:-0}" 'BEGIN{t=r+s; printf (t>0 ? "%.3f" : "n/a"), (t>0 ? r/t : 0)}')"
done
