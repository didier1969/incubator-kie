# Le critère d'acceptation n'avait jamais été choisi

Note de mesure du 2026-08-21. À reporter dans le SOLL (REQ à créer) dès que le MCP répond —
le backend Axon était indisponible au moment de l'écriture.

## Le constat

`FullRunner` ne configurait **aucun** critère d'acceptation. Il prenait donc le défaut du
moteur — **Late Acceptance, taille 400** (`DefaultLocalSearchPhaseFactory:144`,
`AcceptorFactory:256`). Toutes les mesures du projet, y compris le −94,75 % de REQ-KKI-031 et
les campagnes A/B/C/D, portent sur ce réglage hérité. Jamais comparé à quoi que ce soit.

## Balayage — 5000 ordres / 120 s / M5 part 1,0 / départ GEN / graine 42

| critère | coût atteint (CHF) | écart au défaut | mvt/s |
|---|---|---|---|
| **HILL_CLIMBING** | **3,029e12** | **−46,1 %** | 910 |
| LATE_ACCEPTANCE 5 | 3,532e12 | −37,2 % | 681 |
| LATE_ACCEPTANCE 20 | 3,607e12 | −35,8 % | 934 |
| LATE_ACCEPTANCE 50 | 4,001e12 | −28,8 % | 1094 |
| **LATE_ACCEPTANCE 400** (défaut moteur) | **5,621e12** | — | 1230 |
| LATE_ACCEPTANCE 2000 | 7,131e12 | +26,9 % | 1439 |
| LATE_ACCEPTANCE 10000 | 1,031e13 | +83,4 % | 1205 |

**Monotone sur trois ordres de grandeur** : plus la file est courte, meilleur est le plan.

Sonde à 300 ordres / 20 s pour situer les autres familles :
LAHC 3,752e8 · GREAT_DELUGE 3,836e8 · HILL_CLIMBING 4,061e8 · **TABU_SEARCH 6,464e8**.
Le tabou est loin derrière partout.

## Pourquoi

Late Acceptance accepte un mouvement s'il bat le score d'il y a N pas. C'est une machine à
**s'extraire des optima locaux**, et elle se paie en dégradation acceptée. Notre plan de départ
est à 5000 ordres, très loin de tout optimum : dans 900 s le solveur n'atteint jamais un optimum
local dont il faudrait sortir. La diversification ne sert à rien et la dégradation coûte plein
tarif.

**Corollaire mesuré** : ici le débit varie à l'INVERSE de la qualité — LAHC-2000 est le plus
rapide ET le plus mauvais. Illustration directe de DEC-KKI-005 : mesurer des IPS mesurerait
l'inverse de ce qu'on cherche.

## Ce que ça ne conclut pas

1. **À 900 s le classement peut s'inverser.** Ces points sont à 120 s. Plus le budget grandit,
   plus le solveur approche du régime où la diversification paie. Pratique #1172 — deux verdicts
   se sont déjà inversés dans ce projet.
2. **On ne fige pas Hill Climbing.** VIS-KKI-001 : un réglage mesuré sur une instance devient un
   paramètre exposé, jamais un défaut codé en dur.
3. `SIMULATED_ANNEALING` non mesuré : il exige une température de départ à l'échelle du score, et
   le moteur l'exclut lui-même de ses blueprints (`LocalSearchType.getBluePrintTypes`,
   contournement PLANNER-1294).

## À faire

- confirmer à **900 s** sur ≥ 2 graines : HILL_CLIMBING, LAHC-5, LAHC-50, LAHC-400
- `-Dkki.acceptor=` / `-Dkki.acceptorSize=` sont câblés ; `null` conserve le défaut moteur pour
  que les mesures antérieures restent rejouables telles quelles
- une fois REQ-KKI-037 livrée, ce balayage appartient à `optaplanner-benchmark`
  (blueprint `EVERY_LOCAL_SEARCH_TYPE`), pas à un script maison
