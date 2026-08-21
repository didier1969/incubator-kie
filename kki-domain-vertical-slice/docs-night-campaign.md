# Campagne nocturne 2026-08-21 — le budget réel change les conclusions

12 runs × 900 s, 5000 ordres, départ GEN, sériels et non contendus. `scripts/bench-night.sh`.
Tout ce qui avait été mesuré avant l'était à 120-180 s.

## Le résultat qui commande les autres

**Un budget court ne déplace pas seulement les valeurs : il cache la FORME de la courbe.**
Trois dimensions paraissaient monotones à 120 s. À 900 s, deux ont un optimum intérieur et une
s'annule.

## A — Critère d'acceptation : classement partiellement inversé

| critère | coût à 900 s | rang 900 s | rang 120 s |
|---|---|---|---|
| **LAHC-5** | **1,155e12** | **1er** | 2e |
| LAHC-50 | 1,463e12 | 2e | 3e |
| HILL_CLIMBING | 1,488e12 | 3e | **1er** |
| LAHC-400 (défaut moteur) | 2,137e12 | 4e | 4e |

Graine 7 : HILL 1,275e12 vs LAHC-400 1,832e12 — même écart de 1,4×, l'ordre tient.

**S'inverse** : Hill Climbing passe de 1er à 3e. **Tient** : le défaut du moteur reste dernier,
1,85× plus cher que le meilleur.

*Mécanisme* : à 120 s le solveur n'atteint jamais un optimum local, donc la diversification ne
rachète rien. À 900 s il l'atteint — d'où une file COURTE (5) meilleure qu'aucune file. Assez de
mémoire pour s'extraire, pas assez pour errer.

## B — Part du second mouvement : optimum intérieur, et une mesure corrigée

| part | coût à 900 s | écart au défaut 0,5 |
|---|---|---|
| 0,5 (défaut) | 1,458e12 | — |
| **0,8** | **1,196e12** | **−18,0 %** |
| 1,0 | 1,488e12 | +2,1 % |

**Corrige l'étape D de la veille**, qui donnait 1,0 gagnant d'un facteur 2 : c'était un artefact
de contention (deux points sur trois tournaient pendant des builds). Au propre, 1,0 ≈ 0,5, et
0,8 les bat tous les deux. Les logs contendus avaient été supprimés plutôt que publiés.

## C — Parallélisme : ×5,7 de débit, ZÉRO gain de coût

| fils | coût à 900 s | écart | mvt/s |
|---|---|---|---|
| 1 | 1,488e12 | — | 760 |
| 4 | 1,420e12 | −4,6 % | 2 664 |
| 8 | 1,590e12 | **+6,9 %** | 4 336 |

Point de rendement décroissant **entre 1 et 4**, pas à 8. Le −4,6 % de 4 fils est de l'ordre du
bruit inter-graines (±3 %).

*Mécanisme* : le multi-thread évalue les mouvements d'un MÊME pas en parallèle puis n'en applique
qu'un. Il accélère la sélection du pas, pas la progression — bornée par la chaîne séquentielle
appliquer→propager→décider. Nous étions limités par la PROFONDEUR, pas la LARGEUR.

**Conséquence pour PIL-KKI-003** : la piste TornadoVM/GPU vise à multiplier le débit d'évaluation.
Rendement attendu dans ce régime : nul. À prouver que le goulot a changé avant d'y investir.

Le travail reste justifié : suppression du `static LIVE` (défaut réel, cassé en mono-thread),
`rebase()` + `@PlanningId` exigés par le contrat, et argument produit — Timefold vend le
multi-thread en Enterprise, il est ici dans le cœur libre.

## D — Régime metteur-goulot : VERDICT INVERSÉ

`setterWorkingDays=3` — le metteur devient le goulot à la place de la machine.

| configuration | coût à 900 s | émis |
|---|---|---|
| sans (6)(7) | 3,771e12 | 0 |
| **avec (6)(7) à 0,3** | **2,928e12** | 72 204 metteurs + 30 344 outillages |

**−22,3 %.** Le verdict négatif de la veille (+13,5 % à +23,8 %) était exact POUR SON RÉGIME, et
s'inverse dès que la ressource rare est réellement contrainte.

**La décision de ne pas les retirer était la bonne.** Les avoir jetés aurait coûté 22,3 % à tout
client dont l'atelier manque de metteurs — le cas industriel le plus banal.

## Ce qui traverse les quatre volets

**Le débit varie à l'inverse de la qualité, quatre fois indépendamment** :
LAHC-400 1241 mvt/s → le pire plan · 8 fils 4336 mvt/s → +6,9 % · part 1,0 760 mvt/s → +2,1 % ·
(6)(7) actifs 710 mvt/s → −22,3 %. `DEC-KKI-005` n'est pas une précaution de langage.

**Aucun défaut du banc n'avait été mesuré** : départ EDD (5,4 à 6,9× plus cher), part 0,5
(+18 % vs 0,8), critère LAHC-400 (+85 % vs LAHC-5). Trois suppositions qui avaient l'air de
décisions.

## Réserves, à lever avant d'agir

- **LAHC-5 et 0,8 n'ont qu'UNE graine.** L'écart dépasse le bruit, donc le SENS est établi ; la
  VALEUR demande une seconde graine. Aucun défaut ne bouge sur un point.
- Le volet D est à deux points sur une graine — même réserve, écart bien plus large (22,3 %).
- Aucun run en échec, 12/12 complets, stderr propres.

## Suite

1. Confirmer LAHC-5 et part 0,8 sur graine 7, puis changer les défauts du banc.
2. Brancher le détecteur de régime (REQ-KKI-011) sur le choix du jeu de mouvements, comme
   REQ-KKI-023 le prévoit pour la fonction objectif. Le volet D le justifie par la mesure.
3. REQ-KKI-037 — `optaplanner-benchmark` remplacerait ces scripts (GUI-PRO-013).
