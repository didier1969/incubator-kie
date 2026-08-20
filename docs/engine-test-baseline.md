# Baseline de la suite `optaplanner-*`

Photo de référence prise **après** les correctifs `REQ-KKI-034` et `REQ-KKI-040`, avant les
travaux de la phase 3. Sans elle, on ne peut pas distinguer une régression introduite ici d'un
échec hérité de l'amont.

Rejouer : `scripts/test-engine.sh` (hors ligne, après `scripts/bootstrap-offline.sh` une fois).

## 2026-08-20 — HEAD `6a5adf63`

| Module | Tests | Échecs | Erreurs | Ignorés |
|---|---|---|---|---|
| `optaplanner-core-impl` | 1752 | 0 | 0 | 12 |
| `optaplanner-persistence` | — | — | — | — |
| `optaplanner-benchmark` | 117 | 0 | 0 | 0 |
| `optaplanner-test` | 12 | 0 | 0 | 0 |
| `optaplanner-examples` | 1310 | 0 | 0 | 18 |
| **total** | **3191** | **0** | **0** | **30** |

`optaplanner-persistence` est un module parent sans tests propres.

**Aucun échec hérité.** La règle de non-régression est donc stricte : tout échec futur dans ce
périmètre vient de nous, sans exception à discuter.

Les 30 tests ignorés le sont par les `@Disabled` d'amont — ils ne sont pas un symptôme, mais leur
nombre doit rester stable : une hausse signifierait qu'on a désactivé un test au lieu de le réparer.

## Périmètre

`optaplanner-*` seulement. Drools/KIE est construit comme **dépendance** (`install -DskipTests`)
et n'est pas testé : c'est de l'amont qu'on ne modifie pas, et ses échecs — son checkstyle crache
des centaines de lignes sur des imports mal triés — noieraient les nôtres.

Les deux journaux sont séparés pour cette raison : `target/engine-tests.log` pour les tests,
`target/engine-tests-upstream.log` pour l'installation de l'amont.
