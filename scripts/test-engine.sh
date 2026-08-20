#!/usr/bin/env bash
# Suite de tests du LIVRABLE : les modules optaplanner-*, hors ligne.
#
# Périmètre arrêté avec l'opérateur le 2026-08-20 : Drools/KIE est construit comme dépendance
# mais PAS testé. C'est de l'amont qu'on ne modifie pas, et ses échecs hérités noieraient nos
# propres régressions — or c'est exactement ce qu'on cherche à voir.
#
# Un build qui s'arrête au premier module rouge ne permet pas de constituer une baseline,
# d'où le `-fae` plus bas.
#
#   usage : scripts/test-engine.sh [fichier_de_sortie]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="${ROOT}/.devenv/profile/bin:$PATH"
cd "${ROOT}"

OUT="${1:-${ROOT}/target/engine-tests.log}"
mkdir -p "$(dirname "${OUT}")"

MODULES="optaplanner-core/optaplanner-core-impl,optaplanner-core,optaplanner-benchmark,optaplanner-test,optaplanner-persistence,optaplanner-examples"

# DEUX temps, et la séparation est le fond du sujet. `-am` construit les modules dont on
# dépend — mais avec `test` il les TESTE aussi, ce qui fait entrer Drools/KIE dans le
# périmètre par la bande. Mesuré : le build s'arrêtait sur
# `kie-no-dependency-management-enforcer-rule`, un module d'infrastructure amont, faute d'une
# dépendance de test qui ne nous concerne pas. L'amont est une DÉPENDANCE, pas une cible.
echo "[$(date -Is)] amont — install sans tests"
set +e
mvn -o -q install -DskipTests -pl "${MODULES}" -am > "${OUT}" 2>&1
status=$?
set -e
if [ "${status}" != "0" ]; then
  echo "l'installation de l'amont a échoué — voir ${OUT}" >&2
  tail -20 "${OUT}" >&2
  exit "${status}"
fi

echo "[$(date -Is)] suite optaplanner-* — hors ligne, fail-at-end"
# `-fae` (fail at end) est délibéré : on veut la PHOTO complète des échecs, pas le premier.
set +e
mvn -o -fae test -pl "${MODULES}" -DfailIfNoTests=false >> "${OUT}" 2>&1
status=$?
set -e

echo "--- récapitulatif ---"
grep -E "^\[INFO\] Tests run:.*Failures|^\[ERROR\] Tests run:.*Failures" "${OUT}" | tail -3 || true
echo "--- modules en échec ---"
grep -E "^\[INFO\] .*(FAILURE|SKIPPED)" "${OUT}" | head -20 || echo "  aucun"
echo "--- tests en échec ---"
grep -E "^\[ERROR\]   [A-Za-z]" "${OUT}" | sort -u | head -40 || echo "  aucun"

echo "[$(date -Is)] journal complet : ${OUT} (statut mvn ${status})"
exit "${status}"
