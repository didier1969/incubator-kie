#!/usr/bin/env bash
# REQ-KKI-036 volet C — lecture des courbes du banc profondeur-contre-largeur.
#
# Deux coûts finaux ne disent pas OÙ le débit est parti. Quatre grandeurs le disent :
#   STEP_SCORE            combien de PAS chaque bras a réellement pris
#   MOVE_COUNT_PER_STEP   combien de mouvements par pas, et combien acceptés
#   BEST_SCORE            la pente en fin de run — plate = épuisement, descendante = il reste du gain
#   SCORE_CALCULATION_SPEED  les épisodes de famine
set -euo pipefail
RUN="${1:-$(ls -d target/depth-width/*/ | tail -1)}"
PROB="${RUN%/}/Problem_0"
printf "%-8s %10s %12s %12s %10s %10s %14s\n" BRAS pas mvt_selec mvt_accept taux mvt/pas cout_final_CHF
for arm in "${PROB}"/*/; do
  a="$(basename "$arm")"; d="${arm}sub0"
  [ -f "${d}/MOVE_COUNT_PER_STEP.csv" ] || { printf "%-8s  (pas de CSV)\n" "$a"; continue; }
  read -r pas sel acc <<<"$(awk -F, 'NR>1{s++;a+=$2;m+=$3} END{print s, m, a}' "${d}/MOVE_COUNT_PER_STEP.csv")"
  cout="$(awk -F, 'NR>1{v=$2} END{split(v,p,"hard/"); sub(/soft/,"",p[2]); printf "%.4e", -(p[2]+0)/100.0}' "${d}/BEST_SCORE.csv")"
  awk -v a="$a" -v p="$pas" -v s="$sel" -v c="$acc" -v k="$cout" \
     'BEGIN{printf "%-8s %10d %12d %12d %9.2f%% %10.2f %14s\n", a,p,s,c,100*c/s,s/p,k}'
done
echo
echo "=== pente du coût sur les 3 dernières minutes (720 s -> 900 s) ==="
for arm in "${PROB}"/*/; do
  a="$(basename "$arm")"; f="${arm}sub0/BEST_SCORE.csv"
  [ -f "$f" ] || continue
  awk -F, -v a="$a" 'NR>1{t=$1/1000; split($2,p,"hard/"); sub(/soft/,"",p[2]); v=-(p[2]+0)/100.0;
      if(t>=720){ if(!f){t0=t;v0=v;f=1} t1=t;v1=v } }
    END{ if(f) printf "%-8s  %.4e -> %.4e   %+.3f %% en %.0f s\n", a, v0, v1, -100*(v0-v1)/v0, t1-t0 }' "$f"
done
echo
echo "=== coût atteint aux jalons (CHF) ==="
printf "%-8s" BRAS; for T in 60 120 180 300 450 600 750 900; do printf "%12ss" $T; done; echo
for arm in "${PROB}"/*/; do
  a="$(basename "$arm")"; f="${arm}sub0/BEST_SCORE.csv"
  [ -f "$f" ] || continue
  printf "%-8s" "$a"
  for T in 60 120 180 300 450 600 750 900; do
    awk -F, -v T="$T" 'NR>1{t=$1/1000; if(t<=T){split($2,p,"hard/"); sub(/soft/,"",p[2]); v=-(p[2]+0)/100.0}}
        END{printf "%13.4e", v}' "$f"
  done; echo
done
echo
echo "=== vitesse de calcul par minute (calc/s) ==="
printf "%-8s" BRAS; for i in $(seq 0 14); do printf "%7d" $i; done; echo
for arm in "${PROB}"/*/; do
  a="$(basename "$arm")"; f="${arm}sub0/SCORE_CALCULATION_SPEED.csv"
  [ -f "$f" ] || continue
  printf "%-8s" "$a"
  awk -F, 'NR>1{b=int($1/60000); s[b]+=$2; n[b]++} END{for(i=0;i<15;i++) printf "%7.0f", (n[i]?s[i]/n[i]:0)}' "$f"; echo
done
