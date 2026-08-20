#!/usr/bin/env bash
# Rend la campagne nocturne lisible. Lecture seule, aucune charge CPU — il peut tourner
# pendant que la campagne mesure encore.
#
# RÈGLE : le coût atteint est rapporté en ABSOLU. Un pourcentage de réduction n'est pas
# comparable d'une configuration à l'autre quand le départ change (deux dénominateurs). Le
# débit est relevé pour situer le moteur, jamais pour valider — DEC-KKI-005.
set -uo pipefail

OUT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/target/night}"

field() { grep -m1 "^$2" "${OUT}/$1.log" 2>/dev/null | tr ' ' '\n' | grep -m1 "^$3=" | cut -d= -f2-; }
have()  { [ -s "${OUT}/$1.log" ] && grep -q "^full_result" "${OUT}/$1.log" 2>/dev/null; }

row() { # tag, libellé
  if have "$1"; then
    printf '%-22s %14s %10s %12s %10s\n' "$2" \
        "$(field "$1" full_result end_cost_chf)" \
        "$(field "$1" full_result moves_per_sec)" \
        "$(field "$1" full_result verdict)" \
        "$(field "$1" full_result soft_reduction_pct)%"
  elif [ -f "${OUT}/$1.log" ]; then
    printf '%-22s %s\n' "$2" "EN COURS ou INCOMPLET"
  else
    printf '%-22s %s\n' "$2" "non exécuté"
  fi
}

hdr() { printf '\n%-22s %14s %10s %12s %10s\n' "$1" "coût_atteint" "mvt/s" "verdict" "réduction"; }

echo "===== CAMPAGNE NOCTURNE — 5000 ordres / 900 s / départ GEN ====="
echo "Référence 120 s (REQ-KKI-042) : HILL 3,029e12 < LAHC5 3,532e12 < LAHC50 4,001e12 < LAHC400 5,621e12"

hdr "A. critère d'acceptation"
row A-hill-s42    "HILL_CLIMBING  s42"
row A-lahc5-s42   "LAHC-5         s42"
row A-lahc50-s42  "LAHC-50        s42"
row A-lahc400-s42 "LAHC-400 défaut s42"
row A-hill-s7     "HILL_CLIMBING  s7"
row A-lahc400-s7  "LAHC-400 défaut s7"

hdr "B. part du second mouvement"
row B-share0.5-s42 "part 0,5"
row B-share0.8-s42 "part 0,8"
echo "  (A-hill-s42 ci-dessus = part 1,0, même config)"

hdr "C. parallélisme"
echo "  (A-hill-s42 ci-dessus = 1 fil, même config)"
row C-threads4-s42 "4 fils"
row C-threads8-s42 "8 fils"

hdr "D. régime METTEUR-GOULOT (3 jours ouvrés)"
row D-goulot-scarce0.0 "sans (6)(7)"
row D-goulot-scarce0.3 "avec (6)(7) à 0,3"
for t in D-goulot-scarce0.0 D-goulot-scarce0.3; do
  have "$t" && printf '    %-20s metteurs=%s outillages=%s\n' "$t" \
      "$(field "$t" moves_emitted setters)" "$(field "$t" moves_emitted toolings)"
done

echo
echo "--- runs en échec (stderr non vide hors SLF4J) ---"
found=0
for e in "${OUT}"/*.err; do
  [ -e "$e" ] || continue
  if grep -qvE "^SLF4J|^$" "$e" 2>/dev/null; then
    echo "  $(basename "$e" .err) :"; grep -vE "^SLF4J|^$" "$e" | head -3 | sed 's/^/      /'
    found=1
  fi
done
[ "$found" = "0" ] && echo "  aucun"

echo
echo "--- avancement ---"
echo "  runs complets : $(ls "${OUT}"/*.log 2>/dev/null | while read -r f; do grep -q '^full_result' "$f" && echo x; done | wc -l) / 12"
