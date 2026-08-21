#!/usr/bin/env bash
# Où en est la campagne — vue instantanée, ZÉRO CPU, aucune perturbation de la mesure.
#
# POURQUOI CE SCRIPT EXISTE
#
# Une campagne de plusieurs heures ne donnait AUCUN signe de vie observable :
#   - `bench-seq.sh` n'écrit dans son journal de chaîne qu'au DÉMARRAGE de chaque run ;
#   - `FullRunner` n'imprime `full_result` qu'à la FIN des 900 s ;
#   - entre les deux, le journal du run reste figé à ~1 171 octets d'en-tête d'instance.
#
# Or 1 171 octets, c'est EXACTEMENT ce qu'a laissé le run tué en vol le 2026-08-21 à 16:51.
# De l'extérieur, un run qui travaille et un run mort étaient donc indiscernables — et c'est
# précisément la confusion qui a failli faire compter un run tronqué pour fait (REQ-KKI-052).
#
# Le signal qui tranche n'est pas dans les fichiers, il est dans /proc : une JVM vivante brûle
# du CPU. `TIME/ELAPSED ≈ 1,0` dit « elle travaille » ; proche de 0, elle est bloquée ou morte.
#
# Usage :  scripts/bench-watch.sh            une vue
#          watch -n 30 scripts/bench-watch.sh   suivi continu
set -uo pipefail
MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${MODULE}/target/seq}"
BUDGET="${BENCH_BUDGET:-900}"

printf '\n\033[1m=== banc KKI — %s ===\033[0m\n\n' "$(date '+%H:%M:%S')"

# ── La JVM : vivante, et travaille-t-elle vraiment ? ───────────────────────────
pid="$(pgrep -f 'java .* kki\.domain\.full\.' | head -1)"
if [ -z "${pid}" ]; then
  if pgrep -f 'bench-(seq|noise|night|lift)' >/dev/null 2>&1; then
    echo "  ⏳ script de campagne vivant, aucune JVM — entre deux runs (compilation, classpath)"
  else
    echo "  ⛔ AUCUNE campagne en cours."
  fi
else
  read -r etime ptime pcpu rss < <(ps -o etimes=,times=,pcpu=,rss= -p "${pid}")
  # Le rapport temps-CPU / temps-écoulé EST le signe de vie. Un fichier figé ne prouve rien ;
  # une JVM qui a consommé 13 min de CPU en 13 min de mur travaille, sans ambiguïté possible.
  # ⚠️ Les parenthèses de `printf(...)` ne sont PAS cosmétiques. Dans une liste d'arguments de
  # `print`/`printf`, awk lit `>` comme une REDIRECTION : `printf "%.2f", e>0?c/e:0` écrit `e`
  # dans un fichier nommé `0?c/e:0` et ne rend RIEN sur stdout. Le ratio arrivait donc vide, et
  # ce script annonçait « BLOQUÉE ou MORTE » sur une JVM à 100 % de CPU — un garde qui crie faux,
  # c'est-à-dire exactement le défaut qu'il est censé rendre visible.
  # Un `>` n'est une comparaison que dans une condition (`if (e>0)`) ou entre parenthèses.
  ratio="$(awk -v c="${ptime}" -v e="${etime}" 'BEGIN{ if (e>0) printf("%.2f", c/e); else print "" }')"
  # Un garde dont le CALCUL échoue doit dire « je ne sais pas », jamais tomber dans sa branche
  # d'alarme : une alarme fausse le fait désarmer, et il ne servira plus le jour où elle est vraie.
  if [ -z "${ratio}" ]; then
    vie="ÉTAT INCONNU (ps n'a rien rendu — ce n'est PAS une preuve d'arrêt)"
    ratio="?"
  else
    vie="$(awk -v r="${ratio}" 'BEGIN{ if (r>0.5) print "TRAVAILLE"; else if (r>0.05) print "RALENTIE (contention ?)"; else print "BLOQUÉE ou MORTE" }')"
  fi
  printf '  PID %s — \033[1m%s\033[0m  ·  CPU/mur %s  ·  %s Mo résident\n' \
      "${pid}" "${vie}" "${ratio}" "$((rss / 1024))"
  printf '  écoulé %s   ' "$(printf '%02d:%02d' $((etime/60)) $((etime%60)))"
  if [ "${BUDGET}" -gt 60 ]; then
    reste=$((BUDGET - etime))
    [ "${reste}" -lt 0 ] && reste=0
    printf 'budget %s s → fin du run vers \033[1m%s\033[0m (reste %s)\n' \
        "${BUDGET}" "$(date -d "+${reste} seconds" '+%H:%M')" \
        "$(printf '%02d:%02d' $((reste/60)) $((reste%60)))"
  else
    printf '(budget en TRAVAIL — la fin ne se prédit pas au temps mur)\n'
  fi
fi

# ── Quel run est EN COURS ? ────────────────────────────────────────────────────
#
# Le tag ("T1-avant-part1.0-s42") est une étiquette de SHELL : il n'apparaît nulle part dans la
# ligne de commande de la JVM, qui ne reçoit que des valeurs positionnelles. Chercher le tag dans
# /proc/<pid>/cmdline ne pouvait donc JAMAIS aboutir — et marquait le run actif « TRONQUÉ ».
#
# Le critère qui marche : tant qu'une JVM vit, le run en cours est le journal SANS `full_result`
# le plus récemment écrit. Les journaux tronqués d'un run tué lui sont forcément antérieurs.
encours=""
if [ -n "${pid:-}" ]; then
  encours="$(for l in "${OUT}"/*.log; do
        [ -e "${l}" ] || continue
        grep -q '^full_result' "${l}" 2>/dev/null || printf '%s\t%s\n' "$(stat -c%Y "${l}")" "${l}"
      done | sort -rn | head -1 | cut -f2)"
  [ -n "${encours}" ] && encours="$(basename "${encours}" .log)"
fi

# ── Les runs du lot : fait / en cours / tronqué ────────────────────────────────
echo
printf '  %-26s %-12s %14s %9s\n' run état coût mvt/s
for log in "${OUT}"/*.log; do
  [ -e "${log}" ] || { echo "  (aucun run dans ${OUT})"; break; }
  tag="$(basename "${log}" .log)"
  if grep -q '^full_result' "${log}" 2>/dev/null; then
    line="$(grep -m1 '^full_result' "${log}")"
    cost="$(tr ' ' '\n' <<<"${line}" | grep -m1 '^end_cost_chf=' | cut -d= -f2)"
    mps="$(tr ' ' '\n' <<<"${line}" | grep -m1 '^moves_per_sec=' | cut -d= -f2)"
    printf '  %-26s %-12s %14.4e %9s\n' "${tag}" "fait" "${cost}" "${mps}"
  elif [ "${tag}" = "${encours}" ]; then
    printf '  %-26s \033[1m%-12s\033[0m\n' "${tag}" "EN COURS"
  else
    # Un journal sans `full_result` alors qu'aucune JVM ne le porte : c'est un run TUÉ.
    # C'est exactement l'état que la garde de reprise de bench-lib.sh sait désormais rejouer.
    printf '  %-26s \033[31m%-12s\033[0m  (%s octets, sera rejoué)\n' \
        "${tag}" "TRONQUÉ" "$(stat -c%s "${log}")"
  fi
done

# ── Identité de l'arbre : les lots mesurent-ils le même code ? ─────────────────
if [ -s "${OUT}/identite-arbre.tsv" ]; then
  n="$(cut -f4 "${OUT}/identite-arbre.tsv" | sort -u | wc -l)"
  echo
  if [ "${n}" -le 1 ]; then
    echo "  ✓ identité d'arbre : un seul jeu de classes sur tout le lot"
  else
    echo "  ⚠️ identité d'arbre : ${n} jeux de classes DIFFÉRENTS — les runs ne se comparent pas tous"
    cat "${OUT}/identite-arbre.tsv"
  fi
fi
echo
