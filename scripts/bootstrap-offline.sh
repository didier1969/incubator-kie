#!/usr/bin/env bash
# Rapatrie ce qui manque au dépôt Maven local pour que TOUT le reste tourne en `-o`.
#
# Le seul script du projet qui touche au réseau, et il ne s'exécute qu'une fois par machine.
# Motif (REQ-KKI-038) : `mvn -o test` échouait sur optaplanner-core-impl faute du provider
# `surefire-junit-platform:3.3.1` — présent en 3.2.5, absent en 3.3.1. Ce n'est pas une
# dépendance du moteur mais un artefact que surefire résout à l'exécution, donc `install`
# passait et `test` seul échouait.
#
# GUI-PRO-118 : le geste corrigé à la main n'est pas livré tant qu'il n'est pas un script.
#
#   usage : scripts/bootstrap-offline.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="${ROOT}/.devenv/profile/bin:$PATH"
cd "${ROOT}"

# Le périmètre du livrable. Drools/KIE reste construit comme dépendance mais n'est pas testé :
# c'est de l'amont qu'on ne modifie pas, et ses échecs hérités noieraient nos régressions.
MODULES="optaplanner-core/optaplanner-core-impl,optaplanner-core,optaplanner-benchmark,optaplanner-test,optaplanner-persistence,optaplanner-examples"

echo "[$(date -Is)] fenêtre réseau — résolution des dépendances de test"
# `-Dsilent` coupe l'arbre de dépendances, qui fait des milliers de lignes sans rien apprendre.
mvn -q dependency:go-offline -Dsilent=true -pl "${MODULES}" -am || {
  echo "go-offline a échoué — le dépôt local reste incomplet" >&2
  exit 1
}

# go-offline ne rapatrie PAS les providers surefire : ils ne sont pas des dépendances déclarées,
# surefire les résout lui-même au lancement. Il faut donc les demander nommément.
# Le parent kie n'expose PAS la version de surefire en propriété (vérifié) : `help:evaluate`
# rend « null object or invalid expression ». On rapatrie donc le provider pour CHAQUE version
# du plugin présente dans le dépôt local — c'est peu coûteux et ça survit à un changement de
# version en amont, là où une constante en dur casserait au prochain rebase.
PLUGIN_DIR="${HOME}/.m2/repository/org/apache/maven/plugins/maven-surefire-plugin"
for version_dir in "${PLUGIN_DIR}"/*/; do
  [ -d "${version_dir}" ] || continue
  version="$(basename "${version_dir}")"
  echo "[$(date -Is)] provider surefire ${version}"
  for artifact in surefire-junit-platform surefire-junit4 surefire-junit47; do
    # Toutes les combinaisons n'existent pas en amont (junit4/junit47 manquent en 3.6.0-M1) :
    # une absence est normale, et seul junit-platform est réellement requis ici. On coupe donc
    # la sortie de mvn, qui crache une trace complète là où une ligne suffit.
    if mvn -q dependency:get -Dartifact="org.apache.maven.surefire:${artifact}:${version}" \
            > /dev/null 2>&1; then
      echo "  ${artifact}:${version}"
    else
      echo "  (${artifact}:${version} inexistant en amont — ignoré)"
    fi
  done
done

echo "[$(date -Is)] amorçage terminé — tout le reste doit tourner en \`mvn -o\`"
