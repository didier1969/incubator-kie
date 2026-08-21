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
#
# Les deux passes n'ont PAS le même statut. La passe en TRAVAIL est une PRÉCONDITION : sans elle,
# le lot 2 de `bench-seq.sh` se lirait contre un « ±3 % » jamais mesuré. La passe en TEMPS MUR
# répond à une question intéressante mais séparée — ce que coûte la machine partagée — et ne
# bloque rien.
#
# Réserve explicite : les deux passes tournent à `part=1,00`, alors que le bras jugé par le lot 2
# est `part=0,00`, ~3,7x plus lent par mouvement. Le plancher rendu ici est donc celui du bras
# RAPIDE. Les deux témoins `T2-avant`/`T2-apres` de `bench-seq.sh` donnent la lecture au bon
# réglage, mais à n=2. Ne relever un plancher à `part=0,00` que si le lot 2 tombe dans la bande
# « ≈ » de la grille pré-enregistrée : s'il tranche nettement, ce plancher-là ne sert à rien.
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

# ⚠️ CORRIGÉ 2026-08-21 — un plancher se mesure AU BUDGET QU'IL ARBITRE
#
# La première écriture posait 630 000 calculs, « calibré pour que les deux passes restent
# comparables en DURÉE » (~700 mvt/s x 900 s). C'était répondre à une autre question que celle
# qui commande ce lot.
#
# Ce plancher ne sert qu'à une chose : dire quel écart est lisible dans le LOT 2 de
# `bench-seq.sh`, qui tourne à 170 000 calculs (REQ-KKI-055). Un plancher relevé à 3,7x ce
# budget n'est pas l'étalon de ce lot — et il penche du mauvais côté : un run plus COURT a moins
# de temps pour moyenner la gigue de GC et d'ordonnancement, donc le bruit réel à 170 000 est
# plausiblement PLUS large, jamais plus étroit. Un plancher trop optimiste rendrait significatif
# un écart qui ne l'est pas.
#
# Le budget est donc lu chez celui qu'il arbitre, et exposé — jamais recopié en dur.
NOISE_WORK_BUDGET="${NOISE_WORK_BUDGET:-${SEQ_WORK_BUDGET}}"   # bench-lib.sh

echo "=== plancher de bruit, budget en TRAVAIL (${NOISE_WORK_BUDGET} calculs) x ${REPEATS} ==="
BENCH_BUDGET="${NOISE_WORK_BUDGET}"
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
