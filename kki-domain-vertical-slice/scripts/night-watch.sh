#!/usr/bin/env bash
# FILET DE SÉCURITÉ du rapport de 08:00.
#
# Le réveil programmé qui doit rédiger le rapport est SESSION-ONLY : il meurt avec la session
# Claude. Si celle-ci tombe cette nuit, la campagne aurait mesuré 3 heures pour rien — personne
# ne lirait les résultats.
#
# Ce script attend la fin de la campagne et écrit le rapport SUR DISQUE, sans dépendre d'aucune
# session. L'opérateur le trouvera à `target/night/RAPPORT.md` quoi qu'il arrive.
#
# Ne PAS éditer pendant qu'il tourne : bash relit un script par offset d'octets en cours
# d'exécution, et un décalage casse la lecture (constaté sur test-engine.sh ce soir).
set -uo pipefail

MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${MODULE}/target/night"

while pgrep -f "bench-night.sh" > /dev/null 2>&1; do
  sleep 60
done

{
  echo "# Rapport de campagne nocturne — $(date -Is)"
  echo
  echo "Écrit automatiquement à la fin de la campagne. Chiffres bruts ; l'analyse est dans les REQ."
  echo
  echo '```'
  "${MODULE}/scripts/night-report.sh" "${OUT}" 2>&1
  echo '```'
  echo
  echo "## Journal brut"
  echo '```'
  cat "${OUT}/night.tsv" 2>/dev/null || echo "(journal absent)"
  echo '```'
} > "${OUT}/RAPPORT.md" 2>&1

echo "[$(date -Is)] rapport écrit : ${OUT}/RAPPORT.md"
