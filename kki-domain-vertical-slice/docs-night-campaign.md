# Campagne nocturne 2026-08-21 — le budget réel change les conclusions

12 runs × 900 s, 5000 ordres, départ GEN, sériels et non contendus. `scripts/bench-night.sh`.
Tout ce qui avait été mesuré avant l'était à 120-180 s.

> ## ⚠️ LIRE D'ABORD — ce que la suite de la journée a corrigé dans ce rapport
>
> Ce document reste le **relevé** de la campagne, et les chiffres qu'il porte n'ont pas bougé.
> Trois de ses **énoncés** ont été corrigés depuis, par des mesures plus longues ou par l'audit de
> `REQ-KKI-055`. Ils sont corrigés **dans le texte**, pas annotés en marge.
>
> | ce que ce rapport disait | ce qui tient |
> |---|---|
> | « part 0,8 est l'optimum » | 0,8 est l'optimum **de Hill Climbing**. Avec LAHC-5 — le critère qui gagne le volet A — la courbe est monotone et **1,00 gagne**. L'optimum en `part` dépend du critère d'acceptation. |
> | « le multi-thread élargit là où nous étions limités en profondeur » | **contredit par la mesure** : à 8 fils il y a MOINS de mouvements par pas (1,82 contre 2,08), pas six fois plus. C'était une inférence tirée de deux coûts finaux. |
> | « le bruit inter-graines (±3 %) » | **n'avait jamais été mesuré.** Cité deux fois comme un fait, pour qualifier un écart. `REQ-KKI-052` le mesure. |
>
> **Et un fait qui traverse tout le rapport, découvert après coup** : les volets A, C et D tournent
> à `reassignmentShare = 1,00`, où `nextSwap` n'est jamais atteint. Le compteur `moves_emitted` de
> ces dix runs rend **`swaps = 0`**. `Schedule.orderSequence` — l'unique variable de planification
> du modèle — **n'a jamais été modifiée**. Ces volets mesurent un voisinage de réaffectation de
> ressource, à séquence gelée. Aucun n'est faux ; tous ont une portée plus étroite que leur énoncé
> d'origine.

## Le résultat qui commande les autres

**Un budget court ne déplace pas seulement les valeurs : il cache la FORME de la courbe.**
Trois dimensions paraissaient monotones à 120 s. À 900 s, deux ont un optimum intérieur et une
s'annule.

## A — Critère d'acceptation : classement partiellement inversé

| critère | coût à 900 s, graine 42 | rang 900 s | rang 120 s | graine 7 |
|---|---|---|---|---|
| **LAHC-5** | **1,155e12** | **1er** | 2e | **0,975e12** |
| LAHC-50 | 1,463e12 | 2e | 3e | 1,367e12 |
| HILL_CLIMBING | 1,488e12 | 3e | **1er** | 1,275e12 |
| LAHC-400 (défaut moteur) | 2,137e12 | 4e | 4e | 1,832e12 |

**S'inverse** : Hill Climbing passe de 1er à 3e. **Tient** : le défaut du moteur reste dernier.

**Confirmé sur deux graines** (campagne `REQ-KKI-045`, `target/lift/`) : LAHC-5 bat LAHC-400 de
**×1,85** à la graine 42 et de **×1,88** à la graine 7. Le sens et l'ordre de grandeur tiennent.
Le défaut du banc est passé à LAHC-5.

*Mécanisme* : à 120 s le solveur n'atteint jamais un optimum local, donc la diversification ne
rachète rien. À 900 s il l'atteint — d'où une file COURTE (5) meilleure qu'aucune file. Assez de
mémoire pour s'extraire, pas assez pour errer.

**Portée** : les six runs sont à `swaps = 0`. À lire comme *LAHC-5 est le meilleur critère
d'acceptation sur un voisinage de réaffectation seule*. La taille de file optimale dépend de la
structure du voisinage, pas seulement du budget.

## B — Part du second mouvement : l'optimum dépend du CRITÈRE, pas seulement du budget

C'est la correction la plus lourde de ce rapport. Le volet B a balayé `part` **à critère fixé**
(Hill Climbing), et publié son optimum comme une propriété de `part`. Le volet E l'a rejoué avec
LAHC-5 — le critère qui gagne le volet A — et la courbe change de FORME.

| part | Hill Climbing, s42 | Hill Climbing, s7 | LAHC-5, s42 | LAHC-5, s7 |
|---|---|---|---|---|
| 0,50 | 1,458e12 | 1,389e12 | 1,980e12 | 1,706e12 |
| **0,80** | **1,196e12** | **1,179e12** | 1,415e12 | 1,373e12 |
| **1,00** | 1,488e12 | 1,275e12 | **1,155e12** | **0,975e12** |

| critère | forme de la courbe | optimum | confirmé sur |
|---|---|---|---|
| Hill Climbing | **optimum intérieur** | **0,80** — −18,0 % / −15,1 % contre 0,50 | 2 graines |
| LAHC-5 | **monotone** | **1,00** — 0,80 coûte +22,5 % / +40,8 % | 2 graines |

**Un balayage à un facteur à la fois ne pouvait pas voir cela.** Les deux dimensions interagissent :
à `part = 1,00` le voisinage est pauvre mais chaque mouvement est bon marché, et LAHC-5 en tire
mieux parti qu'un Hill Climbing qui a besoin de diversité de voisinage pour ne pas se bloquer.
Le défaut du banc est passé au couple mesuré le meilleur : **LAHC-5 + part 1,00**.

**Corrige aussi l'étape D de la veille**, qui donnait 1,0 gagnant d'un facteur 2 : c'était un
artefact de contention (deux points sur trois tournaient pendant des builds). Les logs contendus
avaient été supprimés plutôt que publiés.

**Portée, et elle est sévère** : le meilleur plan jamais mesuré sur ce banc — **0,975e12**, LAHC-5,
graine 7 — est un run à `part = 1,00`, donc à **`swaps = 0`**. Le meilleur réglage connu du banc est
celui qui ne touche jamais à la variable de décision. `REQ-KKI-055` en tire la question, et
`scripts/bench-seq.sh` la mesure : l'échange est-il évincé par son PRIX — il salit ≈ 3,5× ce que
salit une réaffectation — ou est-il un mauvais opérateur ?

## C — Parallélisme : ×5,7 de débit, quelques pour cent de coût au mieux

| fils | coût à 900 s | écart | mvt/s |
|---|---|---|---|
| 1 | 1,488e12 | — | 760 |
| 4 | **1,420e12** | −4,6 % | 2 664 |
| 8 | 1,590e12 | **+6,9 %** | 4 336 |

Point de rendement décroissant **entre 1 et 4**, pas à 8.

**Le −4,6 % de 4 fils ne doit pas être cité comme un gain** : une graine, un point, et le plancher
de bruit du banc n'était pas mesuré à ce moment-là (`REQ-KKI-052`). Ce qui survit sans réserve :
×3,5 de débit rend au mieux quelques pour cent, là où `REQ-KKI-043` — réduire le coût d'UNE
propagation — a rendu **−22,7 %**. C'est le rapport de levier qui compte, pas le signe.

*Mécanisme — l'explication publiée ici a été CONTREDITE par la mesure.* Ce rapport affirmait que
« le multi-thread évalue les mouvements d'un MÊME pas en parallèle puis n'en applique qu'un », donc
que « nous étions limités par la PROFONDEUR, pas la LARGEUR ». Cette phrase prédit **plus** de
mouvements par pas à 8 fils. `DepthVsWidthBenchmark` en donne **moins** : 1,82 contre 2,08, avec
0,62× de pas et 0,54× de mouvements. Les 8 fils n'ont pas élargi, ils ont fait moins de tout. La
cause n'est pas établie ; deux différences non contrôlées restent en lice — le coût des listeners
de statistique, et la pression mémoire de 8 clones de la solution dans la même JVM.

**Conséquence pour `PIL-KKI-003`** : la piste TornadoVM/GPU vise à multiplier le débit d'évaluation.
Rendement mesuré dans ce régime : quelques pour cent au mieux. À prouver que le goulot a changé de
nature avant d'y investir — et la courbe coût-vs-fils est à REFAIRE sur un voisinage qui contient un
opérateur de séquence, puisque celui-ci déplace le rapport travail/pas.

Le travail reste justifié, pour trois raisons indépendantes du gain de coût : suppression du
`static LIVE` (défaut réel, démontré cassé en mono-thread), `rebase()` + les six `@PlanningId`
exigés par le contrat du moteur, et l'argument produit — Timefold vend le multi-thread en
Enterprise, il est ici dans le cœur libre.

## D — Régime metteur-goulot : VERDICT INVERSÉ, confirmé sur deux graines

`setterWorkingDays=3` — le metteur devient le goulot à la place de la machine.

| configuration | graine 42 | graine 7 |
|---|---|---|
| sans (6)(7) | 3,771e12 | 3,748e12 |
| **avec (6)(7) à 0,3** | **2,928e12** — −22,4 % | **3,022e12** — −19,4 % |

Le verdict négatif de la veille (+13,5 % à +23,8 %, mesuré à 120 s **en régime par défaut**) était
exact pour son régime, et s'inverse dès que la ressource rare est réellement contrainte.
**La réserve « une seule graine » est levée** par la campagne `REQ-KKI-045`.

**La décision de ne pas les retirer était la bonne.** Les avoir jetés aurait coûté ~20 % à tout
client dont l'atelier manque de metteurs — le cas industriel le plus banal.

**Portée** : les quatre runs sont à `swaps = 0`. Or le régime metteur-goulot est précisément celui
où **l'ordre de passage commande les temps morts du metteur**. Le −20 % mesure donc ce que (6) et
(7) apportent *quand le levier le plus direct sur ce goulot est indisponible*. Trois issues restent
ouvertes à voisinage complet : l'écart se maintient, se réduit — les deux leviers se recouvrent —
ou s'inverse. Le câblage du détecteur de régime (`REQ-KKI-011`) sur le jeu de mouvements attend
cette mesure, sinon il arbitrerait entre deux leviers dont un seul a été exercé.

## Ce qui traverse les quatre volets

**Le débit varie à l'inverse de la qualité, quatre fois indépendamment** : LAHC-400 à 1 241 mvt/s
rend le pire plan · 8 fils à 4 336 mvt/s rendent +6,9 % · Hill Climbing à part 1,00, 760 mvt/s,
rend +24,4 % contre 0,80 · (6) et (7) actifs à 710 mvt/s rendent −22,4 %. `DEC-KKI-005` n'est pas
une précaution de langage.

**Aucun défaut du banc n'avait été mesuré** : départ EDD (5,4 à 6,9× plus cher), critère LAHC-400
(×1,85 contre LAHC-5), part 0,5. Trois suppositions qui avaient l'air de décisions. Les trois sont
désormais des valeurs mesurées et **exposées en paramètre** — `-Dkki.acceptor`,
`-Dkki.acceptorSize`, `<part>` — jamais codées en dur (`VIS-KKI-001`).

**Une leçon de méthode, qui a coûté quatre inférences dans la journée** : un constat mesuré et son
explication n'ont pas le même statut. Le mécanisme du volet C était une inférence tirée de deux
coûts finaux, publiée dans la même police que les chiffres. Il est faux. Les chiffres, eux, tiennent.

## État des réserves

| réserve d'origine | état |
|---|---|
| LAHC-5 sur une seule graine | **levée** — graine 7 : ×1,88 contre LAHC-400 |
| part 0,8 sur une seule graine | **levée, et l'énoncé corrigé** — 0,8 n'est l'optimum que de Hill Climbing ; LAHC-5 veut 1,00 |
| volet D à deux points sur une graine | **levée** — graine 7 : −19,4 % |
| « bruit inter-graines ±3 % » | **jamais mesuré** — `REQ-KKI-052`, campagne en cours |
| 12/12 runs complets, stderr propres | tient |
| `swaps = 0` sur les volets A, C et D | **découvert après coup** — `REQ-KKI-055`, campagne en cours |

## Suite

1. **`scripts/bench-noise.sh`** — le plancher de bruit, précondition de tout ce qui suit. Sans lui,
   un écart se lit contre un « ±3 % » qui n'a jamais été mesuré. Relevé au budget qu'il arbitre.
2. **`scripts/bench-seq.sh`** — l'extrémité `part = 0,00`, en DEUX lots : temps mur pour l'axe
   produit (`DEC-KKI-005`), travail égal pour l'axe moteur (`REQ-KKI-052`). Seul le lot en travail
   sépare H1 — le prix de propagation évince l'échange — de H2 — l'opérateur est faible. Grille de
   lecture pré-enregistrée, imprimée par le rapport lui-même.
3. Selon le verdict : rendre un changement de séquence moins cher (voie `REQ-KKI-043`), **ou**
   écrire un opérateur de séquence à grain plus fin — `ListChangeMove` et `SubListChangeMove` sont
   tous deux présents en amont depuis `REQ-KKI-040`, et nous n'avons ni l'un ni l'autre.
4. Refaire la courbe coût-vs-fils (volet C) et les deux bras du volet D **sur un voisinage complet**,
   une fois l'opérateur de séquence tranché.
