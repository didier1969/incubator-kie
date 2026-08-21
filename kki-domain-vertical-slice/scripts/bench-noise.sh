#!/usr/bin/env bash
# REQ-KKI-052 — MESURER le plancher de bruit, au lieu de le supposer.
#
# Le « bruit inter-graines ±3 % » cité dans docs-night-campaign.md, REQ-KKI-042 et REQ-KKI-045
# n'a JAMAIS été mesuré. C'est une inférence publiée comme un fait, et trois verdicts de la
# journée s'appuient dessus pour décider si un écart « dépasse le bruit ».
#
# Ce script rejoue N fois la MÊME configuration, même graine comprise. Tout écart observé est
# alors du bruit par construction : ni la graine, ni le réglage, ni l'algorithme ne varient.
# Seule varie la machine.
#
# Deux passes, et c'est le contraste entre les deux qui porte l'information :
#   - budget en TEMPS MUR : le bruit inclut la contention, puisqu'elle vole de la recherche.
#   - budget en TRAVAIL   : la contention ne peut plus voler de recherche ; ce qui reste est le
#                           bruit irréductible du solveur (ordonnancement des tirages, GC).
# L'écart entre les deux planchers CHIFFRE ce que la machine partagée coûte à nos mesures.
set -euo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${MODULE}/scripts/bench-lib.sh"
bench_setup "${MODULE}/target/noise" noise.tsv

REPEATS="${1:-5}"
LAHC5=(-Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=5)

echo "=== plancher de bruit, budget en TEMPS MUR (${BENCH_BUDGET} s) x ${REPEATS} ==="
for i in $(seq 1 "${REPEATS}"); do
  bench_run "N-time-${i}" 42 1.0 GEN 5 "${LAHC5[@]}"
done

echo "=== plancher de bruit, budget en TRAVAIL x ${REPEATS} ==="
# Le compte est calibré sur ce qu'un run à 900 s atteint sur cette machine chargée : ~700 mvt/s,
# soit de l'ordre de 630 000 appels au calcul de score. Volontairement le même ordre de grandeur,
# pour que les deux passes restent comparables en durée.
BENCH_BUDGET=630000
for i in $(seq 1 "${REPEATS}"); do
  bench_run "N-work-${i}" 42 1.0 GEN 5 "${LAHC5[@]}" -Dkki.budgetMode=work
done

echo
echo "=== dispersion ==="
python3 - "${BENCH_OUT}/noise.tsv" <<'PY'
import re, sys, collections
groups = collections.defaultdict(list)
for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
    if 'full_result' not in line:
        continue
    tag = line.split()[0]
    cost = re.search(r'end_cost_chf=(\d+)', line)
    cpu = re.search(r'cpu_over_wall=([0-9.]+)', line)
    if cost:
        groups[tag.rsplit('-', 1)[0]].append((int(cost.group(1)), float(cpu.group(1)) if cpu else -1.0))
for tag, rows in sorted(groups.items()):
    costs = [c for c, _ in rows]
    lo, hi, mean = min(costs), max(costs), sum(costs) / len(costs)
    cpus = [r for _, r in rows if r >= 0]
    print(f"{tag:10s} n={len(costs)}  moyenne={mean:.4g}  min={lo}  max={hi}"
          f"  amplitude={100.0*(hi-lo)/mean:.2f}%"
          + (f"  cpu/mur={sum(cpus)/len(cpus):.2f}" if cpus else ""))
print()
print("L'amplitude EST le plancher de bruit. Aucun écart inférieur ne doit être interprété.")
PY
