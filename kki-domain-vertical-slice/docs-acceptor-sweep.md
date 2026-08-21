# Le critère d'acceptation n'avait jamais été choisi

Note de mesure du 2026-08-21, **à 120 s**. Reportée dans le SOLL : `REQ-KKI-042`.

> ⚠️ **Ce balayage a été REFAIT à 900 s, et sa conclusion principale ne survit pas.**
> Le classement à 120 s est conservé ici comme relevé, et parce que l'écart entre les deux
> budgets est lui-même le résultat le plus utile du projet. Ce qui tient à 900 s :
> `docs-night-campaign.md`, volet A. Ne pas citer les rangs ci-dessous comme un verdict.

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

> **Faux à 900 s.** La monotonie est un artefact du budget court. À 900 s la courbe a un
> **optimum intérieur** : LAHC-5 (1,155e12) bat LAHC-50 (1,463e12), qui bat Hill Climbing
> (1,488e12) — c'est-à-dire qu'une file COURTE bat une file NULLE. « Plus court = meilleur »
> devient « assez de mémoire pour s'extraire d'un optimum local, pas assez pour errer ».
> Confirmé sur deux graines.

Sonde à 300 ordres / 20 s pour situer les autres familles :
LAHC 3,752e8 · GREAT_DELUGE 3,836e8 · HILL_CLIMBING 4,061e8 · **TABU_SEARCH 6,464e8**.
Le tabou est loin derrière partout.

## Pourquoi

Late Acceptance accepte un mouvement s'il bat le score d'il y a N pas. C'est une machine à
**s'extraire des optima locaux**, et elle se paie en dégradation acceptée. Notre plan de départ
est à 5000 ordres, très loin de tout optimum : dans 900 s le solveur n'atteint jamais un optimum
local dont il faudrait sortir. La diversification ne sert à rien et la dégradation coûte plein
tarif.

> **Corrigé par la mesure à 900 s.** La phrase « dans 900 s le solveur n'atteint jamais un optimum
> local » était une extrapolation depuis 120 s, écrite au futur sans avoir été vérifiée. **À 900 s
> il l'atteint** — c'est exactement pourquoi LAHC-5 y bat Hill Climbing de 22 %. L'explication
> était juste pour 120 s et fausse pour le budget réel du produit.

**Corollaire mesuré** : ici le débit varie à l'INVERSE de la qualité — LAHC-2000 est le plus
rapide ET le plus mauvais. Illustration directe de DEC-KKI-005 : mesurer des IPS mesurerait
l'inverse de ce qu'on cherche.

## Ce que ça ne concluait pas — et ce qui a été tranché depuis

1. **« À 900 s le classement peut s'inverser. »** La prédiction était écrite ; **elle s'est
   réalisée**. Hill Climbing, 1er à 120 s, tombe 3e à 900 s. Pratique #1172 : un verdict ne vaut
   que pour le régime où il a été mesuré. Ce fichier en est l'exemple le plus net du projet.
2. **On ne fige pas Hill Climbing.** `VIS-KKI-001` — tenu : `-Dkki.acceptor` et
   `-Dkki.acceptorSize` sont des **paramètres exposés**, et `null` conserve le défaut du moteur
   pour que les mesures antérieures restent rejouables telles quelles.
3. `SIMULATED_ANNEALING` **toujours non mesuré** : il exige une température de départ à l'échelle
   du score, et le moteur l'exclut lui-même de ses blueprints
   (`LocalSearchType.getBluePrintTypes`, contournement PLANNER-1294). Seul point ouvert du
   balayage.

## État

| point | état |
|---|---|
| confirmer à 900 s sur ≥ 2 graines | **fait** — `docs-night-campaign.md` volet A, et `target/lift/` pour la graine 7 |
| défaut du banc | **passé à LAHC-5**, avec `part = 1,00` — le couple mesuré le meilleur, sur deux graines |
| le balayage appartient à `optaplanner-benchmark` | `REQ-KKI-037` câblé ; blueprint `EVERY_LOCAL_SEARCH_TYPE` non encore utilisé pour ce balayage |

**Et un piège que ce fichier a lui-même tendu** : son optimum est celui de `part = 1,00`.
`REQ-KKI-045` établit que l'optimum en `part` dépend du critère d'acceptation, et réciproquement.
Un balayage à un facteur à la fois ne décide rien au-delà du niveau où les autres facteurs étaient
fixés — et il ne prévient pas qu'il ne le décide pas.
