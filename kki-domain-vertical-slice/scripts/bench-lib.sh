#!/usr/bin/env bash
# Socle commun des campagnes du banc — classpath, budget, lanceur d'un run (GUI-PRO-013).
#
# SÉRIEL, sans exception. Le calcul de score repropage ~28 % du modèle par mouvement : c'est du
# parcours de pointeurs, hostile au cache. Deux JVM concurrentes se disputent le L3 et peuvent
# INVERSER un classement — un run contendu n'est pas une mesure (pratique gouvernée #1218).
#
# Reprenable : un run dont le journal est déjà non vide est sauté.

BENCH_BUDGET="${BENCH_BUDGET:-900}"   # DEC-KKI-005 : l'horizon de replanification glisse aux 15 min
BENCH_ORDERS="${BENCH_ORDERS:-5000}"  # l'échelle de la cible opérateur

# bench_setup <répertoire de sortie> <nom du journal tsv>
bench_setup() {
  BENCH_OUT="$1"
  BENCH_TSV="${BENCH_OUT}/$2"
  BENCH_MODULE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  export PATH=/home/dstadel/projects/kie/.devenv/profile/bin:$PATH
  cd "${BENCH_MODULE}"
  mkdir -p "${BENCH_OUT}"

  # ── Précondition 1 : EXCLUSIVITÉ ───────────────────────────────────────────────
  # « Ne rien lancer de lourd pendant une campagne » était une consigne tenue de tête. Elle a
  # déjà coûté une campagne tuée et un classement INVERSÉ (REQ-KKI-052, pratique #1218) : deux
  # JVM se disputent le L3, et notre calcul de score est du parcours de pointeurs. Une consigne
  # non câblée n'est pas tenue (GUI-PRO-118) — elle l'est ici.
  # Le motif exige le `java ` : sans lui, tout shell dont la ligne de commande CITE le nom de
  # classe — un `pgrep` de contrôle, un `grep` dans un journal — se dénonce lui-même, et un garde
  # qui crie à tort finit désarmé.
  local intruders
  intruders="$(pgrep -f 'java .* kki\.domain\.full\.' 2>/dev/null || true)"
  if [ -n "${intruders}" ]; then
    echo "⛔ un banc tourne déjà (PID $(echo "${intruders}" | tr '\n' ' ')) — la contention" >&2
    echo "   inverse les classements. Attendre, ou BENCH_FORCE=1 pour passer outre." >&2
    [ "${BENCH_FORCE:-0}" = "1" ] || return 1
    echo "   BENCH_FORCE=1 : les runs de ce lot sont CONTENDUS et ne se comparent à rien." >&2
  fi

  # ── Précondition 2 : LES CLASSES SONT CELLES DE L'ARBRE ────────────────────────
  # `dependency:build-classpath` ne compile pas. Une campagne pouvait donc mesurer, en silence,
  # du code qui n'est plus celui du dépôt — la pire classe de défaut ici : le résultat a l'air
  # juste. On compile plutôt que de détecter, et on échoue fort.
  if ! mvn -o -q compile; then
    echo "⛔ compilation échouée — aucune mesure ne serait interprétable. Campagne annulée." >&2
    return 1
  fi

  mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
  BENCH_CP="target/classes:$(cat target/cp.txt)"
}

# bench_field <tag> <préfixe de ligne> <clé> — lit UNE valeur d'un journal de run.
# bench_have  <tag>                        — vrai si le run a produit un résultat complet.
#
# Ces deux lecteurs étaient recopiés à l'identique dans night-report.sh et depth-width-report.sh
# (GUI-PRO-013). Ils remontent ici pour que tout nouveau rapport les prenne au lieu de les
# recopier une quatrième fois. Les deux rapports existants ne sont PAS touchés : ils sont en
# service sur des campagnes archivées, et les réécrire n'est pas ce que la mesure demande.
bench_field() {
  grep -m1 "^$2" "${BENCH_OUT}/$1.log" 2>/dev/null | tr ' ' '\n' | grep -m1 "^$3=" | cut -d= -f2-
}

bench_have() {
  [ -s "${BENCH_OUT}/$1.log" ] && grep -q "^full_result" "${BENCH_OUT}/$1.log" 2>/dev/null
}

# bench_witness <suffixe> — le TÉMOIN : configuration fixe, rejouée dans chaque lot.
#
# Sur cette machine la charge externe est permanente (load 20 sur 16 cœurs, mesuré le 2026-08-21)
# et rien ne garantit qu'elle soit stationnaire d'un lot à l'autre. Un témoin identique intercalé
# donne une lecture DIRECTE de la taxe payée par son lot : c'est lui qui dit si deux lots se
# comparent, au lieu de l'espérer. Sans lui, une dérive lente de la charge externe se confond
# parfaitement avec le traitement mesuré.
bench_witness() {
  bench_run "T-temoin-$1" 42 1.0 GEN 5 -Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=5
}

# bench_run <tag> <graine> <part du second mouvement> <départ GEN|EDD> <jours ouvrés metteur> [-D…]
#
# ⚠️ ORDRE DES BRAS : entrelacer A,B,A,B — jamais AAA puis BBB. Enchaîner les bras par blocs est
# le pire plan d'expérience possible sous dérive de charge : la dérive se confond alors exactement
# avec le traitement, et corriger cela ne coûte QUE l'ordre des appels.
bench_run() {
  local tag="$1" seed="$2" share="$3" start="$4" days="$5"; shift 5
  local log="${BENCH_OUT}/${tag}.log"
  [ -s "${log}" ] && { echo "[$(date -Is)] ${tag} déjà fait"; return 0; }
  echo "[$(date -Is)] ${tag}"
  java "$@" -cp "${BENCH_CP}" kki.domain.full.FullRunner \
      "${BENCH_ORDERS}" "${BENCH_BUDGET}" M5 2.0 "${days}" "${share}" "${start}" "${seed}" \
      > "${log}" 2>"${BENCH_OUT}/${tag}.err" || echo "  ÉCHEC — voir ${tag}.err"
  grep -hE '^(full_baseline|full_result|moves_emitted|cost_breakdown\[arrivee\])' "${log}" \
      | sed "s/^/${tag} /" >> "${BENCH_TSV}" || true
}
