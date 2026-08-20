#!/usr/bin/env bash
# Reconstruit le livrable d'un trait, HORS LIGNE. Aucun réseau : si un artefact manque,
# c'est `scripts/bootstrap-offline.sh` qui n'a pas été passé, et l'échec doit le dire.
#
#   usage : scripts/build-all.sh [--with-tests]
#
# Par défaut le moteur est installé sans ses tests (ils durent des dizaines de minutes) et le
# module KKI est testé. `--with-tests` passe la suite optaplanner-* complète.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="${ROOT}/.devenv/profile/bin:$PATH"
cd "${ROOT}"

WITH_TESTS=0
[ "${1:-}" = "--with-tests" ] && WITH_TESTS=1

echo "[$(date -Is)] moteur — install sans tests"
mvn -o -q install -DskipTests -pl optaplanner-core/optaplanner-core-impl,optaplanner-core -am

if [ "${WITH_TESTS}" = "1" ]; then
  echo "[$(date -Is)] suite optaplanner-* — voir scripts/test-engine.sh pour le détail"
  "${ROOT}/scripts/test-engine.sh"
fi

echo "[$(date -Is)] module KKI — build + tests"
cd "${ROOT}/kki-domain-vertical-slice"
mvn -o -q test
mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt

echo "[$(date -Is)] build complet"
