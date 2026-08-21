# Campagne de confirmation 2026-08-21 — lever les réserves, et le run qui a sauvé un défaut

7 runs × 900 s, 5000 ordres, départ GEN, sériels. `scripts/bench-lift.sh`.
Suite de `docs-night-campaign.md`, dont trois conclusions ne tenaient que sur la graine 42.

## Le résultat qui commande les autres

**Un balayage une-dimension-à-la-fois ne mesure jamais la case du croisement.**

La campagne nocturne avait balayé le critère d'acceptation à part fixe 1,0, puis la part sous
Hill Climbing. Elle en avait tiré deux gagnants : LAHC-5 et part 0,8. Or Hill Climbing n'est
pas le critère gagnant — la case (LAHC-5, 0,8) n'avait jamais tourné.

| coût atteint à 900 s | part 0,5 | part 0,8 | part 1,0 |
|---|---|---|---|
| **graine 42** — HILL | 1,458e12 | **1,196e12** | 1,488e12 |
| **graine 42** — LAHC-5 | *non mesuré* | 1,415e12 | **1,155e12** |
| **graine 7** — HILL | 1,389e12 | **1,179e12** | 1,275e12 |
| **graine 7** — LAHC-5 | *non mesuré* | 1,373e12 | **0,975e12** |

**Sous Hill Climbing, 0,8 est un optimum intérieur. Sous LAHC-5, la part 1,0 écrase 0,8 de
22,5 % puis 40,8 %.** L'effet de la part change de SENS selon le critère d'acceptation.

Livrer les deux gagnants ensemble aurait produit une configuration **22 à 40 % plus chère que
celle déjà mesurée**, en croyant livrer deux améliorations. Deux runs de 15 minutes l'ont évité.

## A — le critère tient sur une seconde graine

| critère | graine 42 | graine 7 |
|---|---|---|
| **LATE_ACCEPTANCE 5** | **1,155e12** | **0,975e12** |
| LATE_ACCEPTANCE 50 | 1,463e12 | *non mesuré* |
| HILL_CLIMBING | 1,488e12 | 1,275e12 |
| LATE_ACCEPTANCE 400 — défaut du moteur | 2,137e12 | 1,832e12 |

LAHC-5 bat Hill Climbing de 22,4 % puis 23,5 %, et le défaut du moteur de 45,9 % puis 46,8 %.

**Formulation soutenue** : LAHC-5 bat Hill Climbing et le défaut du moteur sur deux graines.
**Formulation interdite** : « LAHC-5 est 1er sur deux graines » — LAHC-50 n'a pas tourné à la
graine 7. Le classement à quatre reste à une graine.

## Défauts du banc — ce qui bouge et ce qui ne bouge pas

| champ | avant | après | appui |
|---|---|---|---|
| `acceptorType` / `acceptorSize` | `null` → LAHC-400 hérité du moteur | `LATE_ACCEPTANCE` / `5` | 2 graines, 1,85× d'écart |
| `reassignmentShare` | `0.5`, jamais mesuré | `1.0` | 2 graines sous le critère retenu |
| `scarceResourceShare` | `0.0` | **inchangé** | régime-dépendant, cf. volet D |

### Deux pièges de rétrocompatibilité, tous deux évités

**La taille du critère ne prend son défaut que si le critère n'est pas nommé.** Sans cette
précaution, `-Dkki.acceptor=HILL_CLIMBING` seul — ce que toutes nos campagnes passent — aurait
hérité d'une taille 5 et basculé de `setLocalSearchType` vers `setAcceptorConfig`, deux chemins
qui ne configurent pas le même forager. Les campagnes seraient restées *nominalement* rejouables
tout en ne mesurant plus la même chose.

**`-Dkki.scarceShare=0.0` était indistinguable de l'absence de propriété.** La forme
`getProperty("kki.scarceShare", "0.0")` écrase la seule surcharge qui signifie « désactivé » —
et nos deux campagnes passent précisément cette valeur. Corrigé avant qu'une dérivation
automatique ne s'en serve.

Les deux ont la même forme : *un défaut qui rend une surcharge explicite inopérante n'est pas un
défaut, c'est une perte de contrôle.*

## Ce qui reste ouvert, et qui n'est pas une graine de plus

La colonne **0,5 sous LAHC-5** est vide sur les deux graines. La part 1,0 bat 0,8 nettement,
mais la FORME de la courbe sous le critère retenu n'est pas établie — et elle n'a pas la même
forme que sous Hill Climbing, où l'optimum est intérieur. `scripts/bench-lift2.sh` ferme la
colonne.

## Un fait qui appartient au produit

À part 1,0, `moves_emitted` donne `swaps=0` : **l'échange de position n'est jamais tiré**. La
meilleure configuration mesurée n'utilise qu'un des deux mouvements.

Ce n'est pas une raison de le retirer. `VIS-KKI-001` : un levier qui perd dans un régime devient
un paramètre exposé, jamais du code supprimé. Le volet D dit la même chose dans l'autre sens —
des mouvements qui dégradent de +23,8 % rendent −22,3 % dès que le régime change. C'est
exactement à ça que sert `reassignmentShare`.

## D — le régime metteur-goulot se reproduit, et c'est tout ce qu'il établit

`setterWorkingDays=3`, Hill Climbing, part 1,0.

| graine | sans (6)(7) | avec (6)(7) à 0,3 | écart | émis |
|---|---|---|---|---|
| 42 | 3,771e12 | 2,928e12 | **−22,3 %** | 72 204 metteurs + 30 344 outillages |
| 7 | 3,748e12 | 3,022e12 | **−19,4 %** | 65 217 metteurs + 30 527 outillages |

Les deux bases sont à 0,6 % l'une de l'autre : le bruit inter-graines est faible dans ce régime,
l'écart est donc lisible sans ambiguïté. **Le verdict inversé de la campagne nocturne tient sur
une seconde graine.**

### ⚠️ Ce que cela n'établit PAS, et il faut l'écrire

Ces deux points ne mesurent que la **reproductibilité** de l'effet. Ils ne disent rien de sa
**cause**, et j'avais prévu d'en tirer un seuil de décision — `setterLoad ≥ 1,0 → scarceShare = 0,3`.
Une réfutation adverse l'a interdit, et elle a raison :

| | charge metteur | charge moyenne |
|---|---|---|
| graine 42, 5 j → 3 j | 77 % → 134 % (**×1,74**) | 81 % → 126 % (**×1,56**) |
| graine 7, 5 j → 3 j | 80 % → 139 % (**×1,74**) | 64 % → 101 % (**×1,58**) |

**Les deux grandeurs franchissent 1,0 ensemble, sur les deux graines, par des facteurs quasi
identiques.** La cause est structurelle : `nominalHoldSeconds` étire le travail de mise en train
par le calendrier *du metteur*, puis ce produit entre dans le `required` de la *machine*. Bouger
`setterWorkingDays` bouge les deux par construction.

Le gain de 19 à 22 % est donc attribuable à la rareté du metteur **ou** à la charge générale —
les deux runs ne les séparent pas. Un troisième point le long du même axe n'y changerait rien.

C'est la faute de `REQ-KKI-011` qui rejoue : un seuil jamais exercé là où les deux grandeurs
divergent. Elle avait coûté une rétractation publique ; ici elle est arrêtée avant publication.

### L'expérience qui trancherait

Faire varier `FullDataGenerator.setterCount` à `setterWorkingDays` **constant** : le travail de
mise en train ne dépend pas du nombre de metteurs, tandis que la charge metteur y est
inversement proportionnelle. C'est la seule manipulation connue qui déplace l'une en laissant
l'autre fixe. Protocole : 4 runs à 900 s, régime 5 jours ouvrés, `setterCount` ∈ {242, 121, 60,
40}, part rare ∈ {0,0 ; 0,3} aux extrêmes. Suivi : `REQ-KKI-046`.

## Bilan

7/7 runs complets, aucun `.err` non vide. Deux défauts du banc changent, un troisième reste en
place faute d'attribution, et deux pièges de rétrocompatibilité sont fermés avant d'avoir mordu.
