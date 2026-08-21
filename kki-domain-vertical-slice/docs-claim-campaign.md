# Le coût de la butée — campagne close le 2026-08-22 à 01:13

Mise en œuvre : `REQ-KKI-065` (tranches V1–V6). Conception : `DEC-KKI-013`.
Journal brut : `docs-claim-campaign.tsv` (copie versionnée de `target/claim/claim.tsv`).

**6 bras** · parts `0,00 / 0,05 / 0,15` × graines `42 / 7` · 5000 ordres · variante M5 ·
budget en **TRAVAIL** (`-Dkki.budgetMode=work`, 170 000 calculs de score) ·
`cpu_over_wall` entre 0,98 et 1,01 sur les six.

Rejouer : `scripts/bench-claim.sh` (reprise incluse — un bras n'est « fait » que s'il a produit
un `full_result`).

## Ce que la campagne cherchait, et ce qu'elle a trouvé

Elle cherchait le coût de la butée. Elle a trouvé que **le coût atteint ne peut pas le porter**.

### Le contrôle était déjà dans les données

`generation_order_chf` est la **même décision** — ordre de génération, affectations du générateur,
prouvées identiques au caractère près par `theClaimShareAddsClaimsAndCHANGESNOTHINGELSEInTheInstance` —
évaluée sous les deux jeux de données. C'est la comparaison contrôlée, et elle ne coûte rien.

| part | graine 42 | graine 7 |
|---|---|---|
| 0,05 | **+0,00254 %** | **−0,00018 %** |
| 0,15 | **+0,00263 %** | **+0,02399 %** |

Le signe change. À décision figée la butée a **deux effets contraires** : elle repousse (retard en
hausse) mais elle peut aussi laisser le **bon article monté** sur la broche, auquel cas la remise en
train vaut zéro — `SetupMatrix.secondsBetween(k, k)`, ce que la tranche V4 a rendu exprimable. Les
deux se compensent à quelques centièmes de pour-cent au plus.

⚠️ **Portée.** Mesuré sur l'ordre de génération, un plan catastrophique : 25e12 contre 2,3e12
atteint, **facteur 11**. Sur un plan aussi engorgé tout est déjà en retard, donc repousser quelques
opérations n'y coûte presque rien. C'est un **plancher**, pas le coût sur un plan optimisé — où le
battement a justement été essoré. Suivi : `REQ-KKI-068`.

### Ce que le coût ATTEINT ne mesure pas

| part | graine 42 | graine 7 |
|---|---|---|
| 0,05 | **−6,41 %** | **+4,20 %** |
| 0,15 | **−3,99 %** | **+1,76 %** |

Signes **opposés** aux deux parts, et **non monotone** sur les deux graines. Une contrainte réelle
coûte de plus en plus cher à mesure qu'on l'ajoute ; celle-ci « rapporte » plus à 5 % qu'à 15 %. Et
le coût de départ ne bouge pas : à 5 %, 36 revendications sur 1000 machines ne contraignent
quasiment rien.

⇒ Ce n'est pas la butée. C'est la recherche qui tombe dans un **autre optimum local**.

## La correction de méthode, sur un chiffre déjà publié

`REQ-KKI-052` établit un plancher de bruit de **0,00 %** en budget TRAVAIL. Le chiffre tient ; sa
portée était implicite.

> **Le budget en travail rend un run REPRODUCTIBLE. Il ne le rend pas COMPARABLE à un run de
> configuration voisine.**

Un effet de 0,002 % produit ici un écart de 6 %. Et ce n'est pas du bruit aléatoire — c'est pire :
l'écart est **déterministe** (rejouer le même bras rend le même chiffre au cent près) mais de
**signe arbitraire**. Il a donc toutes les apparences d'une mesure.

Tout rapport de banc doit désormais distinguer :

| propriété | ce que le budget TRAVAIL donne | mesuré |
|---|---|---|
| **reproductible** — même configuration | oui | écart **0,00 %** (`REQ-KKI-052`, n=5) |
| **comparable** — configurations voisines | **non** | sensibilité de trajectoire **±6 %** |

## Verdict

| question | réponse |
|---|---|
| la butée coûte-t-elle ? | **non mesurable** : ≤ 0,024 %, de signe variable, sur décision figée |
| peut-on le lire sur le coût atteint ? | **non** — ±6 % de trajectoire noient un effet de 0,02 % |
| la rejouabilité des campagnes archivées est-elle préservée ? | **oui** : à part nulle, identité au bit près |

La butée est **livrable sans réserve de performance**. Ce qui reste à mesurer est son coût sur un
plan **optimisé**, et cela demande un instrument qui n'existe pas encore : évaluer la même décision
sous deux jeux de données, au lieu de comparer deux recherches — `REQ-KKI-068`.
