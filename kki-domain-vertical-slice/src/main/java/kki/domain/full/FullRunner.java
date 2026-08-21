package kki.domain.full;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.heuristic.selector.move.MoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.factory.MoveIteratorFactoryConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

/**
 * Mesure sur le domaine COMPLET : coût atteint dans un budget de temps, à l'échelle réelle.
 *
 * <p>
 * Le mouvement de position reste un <b>échange</b> et jamais un relocate : le concept autorise
 * l'échange, et un relocate décalerait toute la plage d'ordres entre les deux positions au lieu
 * de deux ordres — ce qui changerait la nature du mouvement mesuré. Depuis {@code REQ-KKI-031} la
 * variante par défaut M5 y ajoute le SECOND mouvement du paradigme, la réaffectation de
 * workcenter, tiré dans la même boucle.
 *
 * <pre>
 *   java ... kki.domain.full.FullRunner \
 *       [ordres] [secondes] [variante] [skew] [jours] [part] [depart] [graine]
 *   défaut : 5000 60 M5 2.0 5 0.5 EDD 42
 *
 *   # le classpath se régénère par :
 *   mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * </pre>
 */
public final class FullRunner {

    private FullRunner() {
    }

    /**
     * Graine de l'instance. Un verdict qui ne tient que sur une graine n'est pas un verdict :
     * c'est une observation sur un tirage.
     */
    public static long seed = 42L;

    /**
     * Ordre de la séquence AVANT la recherche. Ce n'est pas un détail d'instance : toute
     * réduction se mesure contre lui, donc il se choisit et se déclare (`REQ-KKI-032`).
     */
    public enum Start {
        /** L'ordre dans lequel le carnet est tombé — aucun tri. */
        GEN,
        /** Plus urgent d'abord, par date due. Départ historique du banc. */
        EDD
    }

    /** Jeu de mouvements activé, pour décomposer le gain par incrément (A4). */
    public enum Variant {
        /** M1 seul — échange de deux positions X tirées au hasard. Référence REQ-KKI-012. */
        M1,
        /** M3 — mêmes échanges, mais guidés vers les arcs disjonctifs tendus. */
        M3,
        /**
         * M4 — M3, coupé en deux par une phase de réaffectation de ressource. Le second mouvement
         * y est exécuté UNE fois, avec un budget d'essais fixe : mesuré, 300 réaffectations
         * contre 825 820 échanges.
         */
        M4,
        /**
         * M5 — les DEUX mouvements du paradigme dans la même boucle, tirés en alternance et
         * évalués au même titre. C'est ce que l'opérateur a spécifié dès le départ ; M3 et M4 sont
         * conservés pour que la comparaison tranche, et non une conviction.
         */
        M5
    }

    public static void main(String[] args) throws Exception {
        int orderCount = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 60L;
        // Le defaut est la MEILLEURE configuration connue, pas la plus ancienne. M3 l'etait
        // tant qu'un seul mouvement existait ; laisser M3 par defaut ferait lire -51 % comme
        // l'etat de l'art la ou M5 rend -94,75 % a budget egal (REQ-KKI-031).
        Variant variant = args.length > 2 ? Variant.valueOf(args[2]) : Variant.M5;
        if (args.length > 3) {
            FullDataGenerator.levelDemandSkew = Double.parseDouble(args[3]);
        }
        if (args.length > 4) {
            // Pour isoler un effet d'un autre : deux changements dans un même commit ne se
            // départagent pas en relisant le diff, seulement en refaisant la mesure.
            FullDataGenerator.setterWorkingDays = Integer.parseInt(args[4]);
        }
        if (args.length > 5) {
            reassignmentShare = Double.parseDouble(args[5]);
        }
        Start start = args.length > 6 ? Start.valueOf(args[6]) : Start.EDD;
        if (args.length > 7) {
            // Un verdict qui ne tient que sur une graine est une observation sur un tirage. La
            // graine était un champ public jamais relié à argv : impossible de rejouer ailleurs.
            seed = Long.parseLong(args[7]);
        }

        applyClaimShare();
        JobShopSolution problem = FullDataGenerator.generate(orderCount, seed);
        System.out.printf("full_instance orders=%d operations=%d machines=%d setters=%d"
                + " toolings=%d level_skew=%.1f%n",
                problem.getOrderList().size(), problem.getOperationList().size(),
                problem.getMachineList().size(), problem.getSetterList().size(),
                problem.getToolingList().size(), FullDataGenerator.levelDemandSkew);

        FullScoreCalculator oracle = new FullScoreCalculator();
        List<Order> sequence = problem.getScheduleList().get(0).getOrderSequence();
        List<Order> generationOrder = List.copyOf(sequence);

        // Les DEUX départs sont chiffrés à chaque run, quel que soit celui qui sert : sans les
        // deux, la réduction annoncée ne dit pas contre quoi elle est prise.
        long generationOrderCost = softCostOf(oracle, problem);
        applyStart(problem, Start.EDD);
        long earliestDueDateCost = softCostOf(oracle, problem);
        if (start == Start.GEN) {
            sequence.clear();
            sequence.addAll(generationOrder);
        }
        oracle.resetWorkingSolution(problem);
        long startCost = -oracle.fullSweepScore().getSoftScore();
        long startHard = -oracle.fullSweepScore().getHardScore();
        // Le nom dit le SENS de la valeur. `edd_gain_pct` imprimait ≈ −440 % là où le tri par
        // date due rend le plan 5,4 fois plus CHER (`REQ-KKI-030`) : un nom qui énonce le
        // contraire de sa valeur est la même faute que le défaut périmé corrigé en f7250188.
        System.out.printf("full_baseline start=%s seed=%d generation_order_chf=%.0f"
                + " earliest_due_date_chf=%.0f edd_over_generation_x=%.2f%n",
                start, seed, generationOrderCost / 100.0, earliestDueDateCost / 100.0,
                generationOrderCost == 0L ? 0.0
                        : earliestDueDateCost / (double) generationOrderCost);
        System.out.print(oracle.coldSweep().describe("depart"));
        System.out.print(oracle.backwardSweep().describe("depart", startCost));
        // La capacité se compte sur l'HORIZON DE PLANIFICATION et non sur le makespan : un
        // atelier qui met huit ans à écouler six mois de carnet est chargé à seize cents pour
        // cent, pas à cent.
        System.out.print(oracle.resourceUsage(FullDataGenerator.horizonSeconds).describe("depart"));
        System.out.print(oracle.latenessProfile("depart"));

        SolverConfig solverConfig = solverConfigOf(variant, seconds);
        // Le détecteur de corruption DU MOTEUR, activé par -Dkki.assert=FULL_ASSERT. Il compare
        // le score incrémental à un recalcul complet APRÈS CHAQUE MOUVEMENT : c'est exactement
        // la classe de défaut qui a produit `JobShopSolutionCloner` (score annoncé ≠ plan rendu),
        // et il la trouve au mouvement qui l'introduit, pas des heures plus tard.
        //
        // Jamais le mode de MESURE : le recalcul complet coûte des ordres de grandeur. Une passe
        // dédiée, sur une instance réduite, et le verdict est binaire — vert ou une trace.
        // Résolution multi-thread, activée par -Dkki.threads=N. Le moteur exige alors que chaque
        // Move sache se traduire dans la copie de solution du fil destinataire (`Move.rebase`) ;
        // sans cela il refuse de démarrer, explicitement.
        String threads = System.getProperty("kki.threads");
        if (threads != null) {
            solverConfig.setMoveThreadCount(threads);
            System.out.printf("move_thread_count %s%n", threads);
        }
        String assertMode = System.getProperty("kki.assert");
        if (assertMode != null) {
            solverConfig.setEnvironmentMode(
                    org.optaplanner.core.config.solver.EnvironmentMode.valueOf(assertMode));
            System.out.printf("environment_mode %s — recalcul complet à chaque mouvement,"
                    + " le débit mesuré ici ne vaut RIEN%n", assertMode);
        }

        CriticalPairMoveIteratorFactory.SWAPS_EMITTED.set(0L);
        CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.set(0L);
        CriticalPairMoveIteratorFactory.SETTER_MOVES_EMITTED.set(0L);
        CriticalPairMoveIteratorFactory.TOOLING_MOVES_EMITTED.set(0L);
        FullScoreCalculator.CALCULATE_SCORE_CALLS.set(0L);
        FullScoreCalculator.DIRTY_OPERATIONS.set(0L);
        FullScoreCalculator.ORDER_COMPLETION_CHANGES.set(0L);
        FullScoreCalculator.PROPAGATIONS.set(0L);
        FullScoreCalculator.ENQUEUED_OPERATIONS.set(0L);

        double loadAtStart = systemLoad();
        double cpuAtStart = processCpuSeconds();
        long startNanos = System.nanoTime();
        JobShopSolution solved;
        try (SolverManager<JobShopSolution, Long> manager = SolverManager.create(solverConfig)) {
            solved = manager.solve(1L, problem).getFinalBestSolution();
        }
        double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        double cpuSeconds = processCpuSeconds() - cpuAtStart;
        double loadAtEnd = systemLoad();

        oracle.resetWorkingSolution(solved);
        System.out.print(oracle.coldSweep().describe("arrivee"));
        System.out.print(oracle.backwardSweep()
                .describe("arrivee", -oracle.fullSweepScore().getSoftScore()));
        System.out.print(oracle.resourceUsage(FullDataGenerator.horizonSeconds)
                .describe("arrivee"));

        long endCost = -solved.getScore().getSoftScore();
        // `moves_per_sec` compte les MOUVEMENTS ÉVALUÉS, pas les propagations : chaque
        // mouvement en coûte environ 2,7 — l'appliquer, l'annuler, et rejouer celui qui est
        // retenu à chaque pas. L'ancien nom `moves` désignait les propagations et laissait lire
        // un débit trois fois trop élevé.
        long calls = FullScoreCalculator.CALCULATE_SCORE_CALLS.get();
        long propagations = Math.max(1L, FullScoreCalculator.PROPAGATIONS.get());
        long dirty = FullScoreCalculator.DIRTY_OPERATIONS.get();
        long orderChanges = FullScoreCalculator.ORDER_COMPLETION_CHANGES.get();

        // ⚠️ `reduction_pct` ne regarde que le SOUPLE, et le score est hard/soft avec le dur
        // prioritaire. Un solveur qui échange du dur contre du souple — remettre en place des
        // ordres à verrou dur au prix d'un peu de retard — fait exactement son travail et
        // affiche pourtant une « dégradation ». Le verdict lisible est donc la comparaison des
        // DEUX composantes, pas d'une seule.
        long endHard = -solved.getScore().getHardScore();
        String verdict;
        if (endHard <= startHard && endCost <= startCost) {
            verdict = "IMPROVED_BOTH";
        } else if (endHard < startHard) {
            verdict = "TRADED_SOFT_FOR_HARD";
        } else if (endCost < startCost) {
            verdict = "TRADED_HARD_FOR_SOFT";
        } else {
            verdict = "WORSE_BOTH";
        }
        System.out.printf(
                "full_result variant=%s start=%s seed=%d orders=%d seconds=%.2f"
                        + " moves_per_sec=%.1f propagations=%d "
                        + "start_cost_chf=%.0f end_cost_chf=%.0f soft_reduction_pct=%.2f "
                        + "hard_start=%d hard_end=%d hard_reduction_pct=%.2f verdict=%s "
                        + "dirty_per_propagation=%.1f order_changes_per_propagation=%.1f"
                        + " cost_relevant_pct=%.2f enqueued=%d wasted_recompute_pct=%.1f"
                        + " budget_mode=%s cpu_seconds=%.1f cpu_over_wall=%.2f"
                        + " load_start=%.1f load_end=%.1f cores=%d%n",
                variant, start, seed, orderCount, elapsed, calls / elapsed, propagations,
                startCost / 100.0, endCost / 100.0,
                startCost == 0L ? 0.0 : 100.0 * (startCost - endCost) / (double) startCost,
                startHard, endHard,
                startHard == 0L ? 0.0 : 100.0 * (startHard - endHard) / (double) startHard,
                verdict,
                (double) dirty / propagations, (double) orderChanges / propagations,
                dirty == 0L ? 0.0 : 100.0 * orderChanges / dirty,
                FullScoreCalculator.ENQUEUED_OPERATIONS.get(),
                // Part des recalculs qui ne changent RIEN : le travail que la propagation fait
                // pour constater qu'une opération n'a pas bougé.
                FullScoreCalculator.ENQUEUED_OPERATIONS.get() == 0L ? 0.0
                        : 100.0 * (FullScoreCalculator.ENQUEUED_OPERATIONS.get() - dirty)
                                / FullScoreCalculator.ENQUEUED_OPERATIONS.get(),
                // Le contexte de contention, sans lequel le coût atteint ne se compare à rien.
                BUDGET_IN_WORK ? "work" : "time", cpuSeconds,
                elapsed == 0.0 ? 0.0 : cpuSeconds / elapsed,
                loadAtStart, loadAtEnd, Runtime.getRuntime().availableProcessors());
        if (variant == Variant.M4) {
            System.out.printf("reassignment attempts=%d accepted=%d%n",
                    ResourceReassignmentPhaseCommand.attempts,
                    ResourceReassignmentPhaseCommand.ACCEPTED_LAST_RUN);
        }
        // Les DEUX mouvements sont-ils réellement tirés ? Sans ce compte, un second mouvement
        // câblé mais jamais émis se lirait comme un second mouvement exercé.
        System.out.printf("moves_emitted swaps=%d reassignments=%d setters=%d toolings=%d"
                + " reassignment_share=%.2f scarce_share=%.2f%n",
                CriticalPairMoveIteratorFactory.SWAPS_EMITTED.get(),
                CriticalPairMoveIteratorFactory.REASSIGNMENTS_EMITTED.get(),
                CriticalPairMoveIteratorFactory.SETTER_MOVES_EMITTED.get(),
                CriticalPairMoveIteratorFactory.TOOLING_MOVES_EMITTED.get(),
                variant == Variant.M5 ? reassignmentShare : 0.0, scarceResourceShare);
    }

    /**
     * La configuration de solveur du banc, en UN seul endroit.
     *
     * <p>
     * Partagée avec {@code FullBenchmark} : deux constructions parallèles du même solveur
     * finiraient par diverger, et la divergence se lirait comme un effet mesuré.
     *
     * <p>
     * Les réglages exposés ({@link #acceptorType}, {@link #acceptorSize},
     * {@link #reassignmentShare}, {@link #scarceResourceShare}) sont lus À LA CONSTRUCTION et
     * gelés dans la configuration produite. C'est ce qui permet au benchmark de matérialiser
     * plusieurs bras dans une même JVM en faisant varier ces champs entre deux appels.
     */
    static SolverConfig solverConfigOf(Variant variant, long seconds) {
        ScoreDirectorFactoryConfig scoreDirectorFactoryConfig = new ScoreDirectorFactoryConfig();
        scoreDirectorFactoryConfig.setIncrementalScoreCalculatorClass(FullScoreCalculator.class);

        SolverConfig solverConfig = new SolverConfig();
        solverConfig.setSolutionClass(JobShopSolution.class);
        solverConfig.setEntityClassList(List.of(Schedule.class));
        solverConfig.setScoreDirectorFactoryConfig(scoreDirectorFactoryConfig);
        solverConfig.setPhaseConfigList(phasesOf(variant, seconds));
        return solverConfig;
    }

    /**
     * M1 = échange X uniforme · M3 = échange X guidé vers les arcs tendus · M4 = M3 coupé par une
     * phase de réaffectation de ressource. M3 remplace M1 plutôt que de s'y ajouter : les deux
     * produisent le même type de mouvement, l'un au hasard et l'autre en sachant pourquoi.
     *
     * <p>
     * Le budget de temps est réparti à parts égales entre les phases de recherche de M4, pour que
     * la comparaison à M3 se fasse à budget total ÉGAL — sans quoi la phase de réaffectation
     * serait payée par du temps que M3 n'a pas eu.
     */
    private static List<org.optaplanner.core.config.phase.PhaseConfig> phasesOf(Variant variant,
            long seconds) {
        if (variant != Variant.M4) {
            return List.of(localSearchOf(variant, seconds));
        }
        org.optaplanner.core.config.phase.custom.CustomPhaseConfig reassignment =
                new org.optaplanner.core.config.phase.custom.CustomPhaseConfig();
        reassignment.setCustomPhaseCommandClassList(
                List.of(ResourceReassignmentPhaseCommand.class));
        return List.of(localSearchOf(Variant.M3, seconds / 2), reassignment,
                localSearchOf(Variant.M3, seconds - seconds / 2));
    }

    /** Vrai si l'opérateur a nommé le critère lui-même — voir {@link #acceptorSize}. */
    private static final boolean ACCEPTOR_NAMED_BY_CALLER = System.getProperty("kki.acceptor") != null;

    /**
     * Critère d'acceptation, réglable par {@code -Dkki.acceptor=…} et {@code -Dkki.acceptorSize=…}.
     *
     * <p>
     * Le banc n'en configurait AUCUN : il prenait le défaut du moteur — Late Acceptance, taille
     * 400 — sans que ce soit un choix. Mesuré au budget qui compte, ce réglage hérité est le PIRE
     * des quatre essayés :
     *
     * <pre>
     * coût atteint à 900 s     graine 42     graine 7
     *   LATE_ACCEPTANCE 5      1,155e12      0,975e12   &lt;- retenu
     *   LATE_ACCEPTANCE 50     1,463e12         —
     *   HILL_CLIMBING          1,488e12      1,275e12
     *   LATE_ACCEPTANCE 400    2,137e12      1,832e12   &lt;- défaut du moteur, 1,85x le meilleur
     * </pre>
     *
     * <p>
     * Le défaut du banc est donc une valeur MESURÉE sur deux graines, pas une préférence. Elle ne
     * vaut que pour ce régime : à 120 s le classement était différent — Hill Climbing gagnait et
     * LAHC-5 était deuxième. Un budget court ne déplace pas seulement les valeurs, il cache la
     * FORME de la courbe : la monotonie « file plus courte = meilleur » devient un optimum
     * intermédiaire dès que le solveur atteint un optimum local, ce qu'il ne fait pas à 120 s.
     *
     * <p>
     * Toute campagne antérieure reste rejouable en nommant la valeur :
     * {@code -Dkki.acceptor=LATE_ACCEPTANCE -Dkki.acceptorSize=400} rend le défaut du moteur.
     * Détail : {@code REQ-KKI-042}, {@code REQ-KKI-045}.
     */
    public static String acceptorType = System.getProperty("kki.acceptor", "LATE_ACCEPTANCE");

    /**
     * Taille de la file du critère (Late Acceptance, Step Counting, Tabu selon le type).
     *
     * <p>
     * Cinq : assez de mémoire pour s'extraire d'un optimum local, pas assez pour y errer.
     *
     * <p>
     * <b>La taille ne prend son défaut que si le critère n'a PAS été nommé.</b> Sans cette
     * précaution, {@code -Dkki.acceptor=HILL_CLIMBING} seul hériterait d'une taille 5 et basculerait
     * de {@code setLocalSearchType} vers {@code setAcceptorConfig} — deux chemins qui ne configurent
     * pas le même forager. Les campagnes qui ont nommé HILL_CLIMBING sans taille resteraient
     * nominalement rejouables tout en ne mesurant plus la même chose.
     */
    public static Integer acceptorSize = ACCEPTOR_NAMED_BY_CALLER
            ? Integer.getInteger("kki.acceptorSize")
            : Integer.getInteger("kki.acceptorSize", 5);

    private static LocalSearchPhaseConfig localSearchOf(Variant variant, long seconds) {
        TerminationConfig termination = new TerminationConfig();
        if (BUDGET_IN_WORK) {
            // Un budget en TEMPS MUR fait que la contention vole directement de la recherche :
            // sur une machine chargée, 900 s valent moins de mouvements évalués, donc un plan
            // plus cher — pour une raison étrangère à l'algorithme comparé. Le bruit entre alors
            // dans la grandeur mesurée elle-même.
            //
            // Un budget en TRAVAIL rend la comparaison immune par construction : une machine
            // chargée met plus longtemps, elle ne fait pas moins de recherche. C'est l'axe des
            // décisions de moteur. Le temps mur reste l'axe du PRODUIT (`DEC-KKI-005` fixe 900 s
            // sur l'horizon de replanification) — deux métriques, deux usages, jamais confondus.
            termination.setScoreCalculationCountLimit(seconds);
        } else {
            termination.setSecondsSpentLimit(seconds);
        }
        LocalSearchPhaseConfig localSearch = new LocalSearchPhaseConfig();
        localSearch.setTerminationConfig(termination);
        if (acceptorType != null) {
            localSearch.setLocalSearchType(
                    org.optaplanner.core.config.localsearch.LocalSearchType.valueOf(acceptorType));
            if (acceptorSize != null) {
                // Le TYPE et la TAILLE ne se configurent pas ensemble dans le moteur : passer un
                // acceptorConfig avec un localSearchType lève. On repasse donc par l'acceptor
                // explicite, en traduisant le type vers sa file correspondante.
                localSearch.setLocalSearchType(null);
                localSearch.setAcceptorConfig(acceptorOf(acceptorType, acceptorSize));
            }
        }
        localSearch.setMoveSelectorConfig(switch (variant) {
            case M1 -> selector(false, 0.0);
            case M5 -> selector(true, reassignmentShare);
            default -> selector(true, 0.0);
        });
        return localSearch;
    }

    /**
     * L'échange X passe par notre propre fabrique dans les deux modes. Avec deux classes
     * d'entités, le {@code ListSwapMoveSelector} d'OptaPlanner ne sait plus déduire l'entité à
     * laquelle il s'applique — mais surtout, comparer M1 et M3 exige que seul le CHOIX de la
     * paire diffère, pas la mécanique du mouvement.
     */
    private static MoveSelectorConfig<?> selector(boolean guided, double reassignmentShare) {
        MoveIteratorFactoryConfig config = new MoveIteratorFactoryConfig();
        config.setMoveIteratorFactoryClass(CriticalPairMoveIteratorFactory.class);
        config.setMoveIteratorFactoryCustomProperties(Map.of(
                "guided", Boolean.toString(guided),
                "reassignmentShare", Double.toString(reassignmentShare),
                "scarceResourceShare", Double.toString(scarceResourceShare)));
        return config;
    }

    /**
     * Part des tirages donnée aux mouvements (6) metteur et (7) outillage de {@code CPT-KKI-010},
     * réglable par {@code -Dkki.scarceShare=…}.
     *
     * <p>
     * Zéro par défaut, et c'est délibéré : activer un nouveau mouvement par défaut rendrait toute
     * comparaison avec les campagnes antérieures impossible. La valeur se mesure avant de servir
     * de défaut — c'est la leçon du défaut périmé corrigé en {@code f7250188} et celle de
     * {@code reassignmentShare}, dont le 0,5 n'avait jamais été mesuré.
     */
    /**
     * {@code -Dkki.budgetMode=work} termine sur un compte d'appels au calcul de score plutôt que
     * sur le temps mur. L'argument de budget garde sa position ; seule son unité change.
     */
    private static final boolean BUDGET_IN_WORK = "work".equals(System.getProperty("kki.budgetMode"));

    /**
     * Charge de la machine, moyennée sur la dernière minute. Rend -1 là où le système ne la
     * publie pas. Un run sur une machine dont la charge dépasse le nombre de cœurs n'a pas obtenu
     * la machine : sans ce relevé, le chiffre publié est ininterprétable hors de son contexte, et
     * la stationnarité ne se reconstruit qu'après coup, par chance.
     */
    private static double systemLoad() {
        return java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    }

    /**
     * Temps CPU consommé par le processus, en secondes ; -1 si la JVM ne l'expose pas.
     *
     * <p>
     * Rapporté au temps mur, il transforme « ce run a-t-il été affamé ? » d'inconnue en quantité
     * MESURÉE. Un run mono-fil qui obtient un cœur entier rend un rapport voisin de 1 ; nettement
     * en dessous, il a été privé de processeur et son coût atteint ne se compare à rien.
     */
    private static double processCpuSeconds() {
        var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean sunOs
                ? sunOs.getProcessCpuTime() / 1_000_000_000.0
                : -1.0;
    }

    /**
     * Part des machines occupées à l'origine par un travail DÉJÀ LANCÉ — la BUTÉE de
     * `DEC-KKI-013`, exposée en argument de campagne.
     *
     * <p>
     * Défaut ZÉRO, comme {@code kki.scarceShare} et pour la même raison : toutes les campagnes
     * archivées doivent rester rejouables à l'identique tant que la valeur n'est pas mesurée
     * (`VIS-KKI-001`). Nommée par l'appelant, elle rejoint le générateur ; absente, rien ne bouge.
     */
    static void applyClaimShare() {
        // Lue à l'APPEL et non capturée à l'initialisation de la classe : une constante statique
        // fige la valeur au premier chargement, ce qui rend le câblage intestable autrement qu'en
        // relançant une JVM — et un câblage non testé qui rend une courbe PLATE se lit « ce levier
        // ne sert à rien ». Faux, et crédible (REQ-KKI-033).
        String named = System.getProperty("kki.claimShare");
        if (named != null) {
            FullDataGenerator.claimShare = Double.parseDouble(named);
        }
    }

    private static final String SCARCE_SHARE_PROPERTY = System.getProperty("kki.scarceShare");

    /**
     * Vrai si l'appelant a NOMMÉ la part, fût-ce à zéro.
     *
     * <p>
     * La forme précédente, {@code getProperty("kki.scarceShare", "0.0")}, rendait
     * {@code -Dkki.scarceShare=0.0} strictement indistinguable de l'absence de propriété — et nos
     * deux campagnes passent précisément cette valeur ({@code bench-night.sh},
     * {@code bench-lift.sh}). Toute logique qui voudrait dériver un défaut du régime détecté
     * écraserait donc silencieusement la seule surcharge qui signifie « désactivé ».
     *
     * <p>
     * Même faute que celle évitée sur {@link #acceptorSize} : un défaut qui rend une surcharge
     * explicite inopérante n'est pas un défaut, c'est une perte de contrôle.
     */
    public static final boolean SCARCE_SHARE_NAMED_BY_CALLER = SCARCE_SHARE_PROPERTY != null;

    public static double scarceResourceShare =
            SCARCE_SHARE_NAMED_BY_CALLER ? Double.parseDouble(SCARCE_SHARE_PROPERTY) : 0.0;

    /**
     * Impose l'ordre de départ demandé. {@link Start#GEN} ne trie pas : c'est l'ordre dans
     * lequel le générateur a produit le carnet.
     */
    static void applyStart(JobShopSolution problem, Start start) {
        if (start == Start.EDD) {
            problem.getScheduleList().get(0).getOrderSequence()
                    .sort(Comparator.comparingLong(Order::getDueEpochSec));
        }
    }

    /** Coût souple de la séquence courante, calculateur réarmé — une balayée complète. */
    private static long softCostOf(FullScoreCalculator oracle, JobShopSolution problem) {
        oracle.resetWorkingSolution(problem);
        return -oracle.fullSweepScore().getSoftScore();
    }

    /**
     * Traduit un type de recherche locale en critère d'acceptation explicite, taille comprise.
     *
     * <p>
     * Chaque type a SA file et son paramètre : la taille de Late Acceptance n'est pas celle du
     * tabou, qui n'est pas le compteur de pas. Les mélanger produirait une comparaison entre des
     * grandeurs qui n'ont pas la même unité.
     */
    private static org.optaplanner.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig
            acceptorOf(String type, int size) {
        var config = new org.optaplanner.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig();
        var acceptorType = org.optaplanner.core.config.localsearch.decider.acceptor.AcceptorType.class;
        switch (type) {
            case "LATE_ACCEPTANCE" -> {
                config.setAcceptorTypeList(List.of(Enum.valueOf(acceptorType, "LATE_ACCEPTANCE")));
                config.setLateAcceptanceSize(size);
            }
            case "TABU_SEARCH" -> {
                config.setAcceptorTypeList(List.of(Enum.valueOf(acceptorType, "ENTITY_TABU")));
                config.setEntityTabuSize(size);
            }
            case "GREAT_DELUGE" -> {
                config.setAcceptorTypeList(List.of(Enum.valueOf(acceptorType, "GREAT_DELUGE")));
                config.setGreatDelugeWaterLevelIncrementRatio(size / 100_000.0);
            }
            case "HILL_CLIMBING" ->
                config.setAcceptorTypeList(List.of(Enum.valueOf(acceptorType, "HILL_CLIMBING")));
            default -> throw new IllegalArgumentException(
                    "taille non applicable au type " + type + " — retirer -Dkki.acceptorSize");
        }
        return config;
    }

    /**
     * Part du budget de tirage donnée au second mouvement. Dimension du banc, balayable.
     *
     * <p>
     * Le 0,5 précédent n'avait jamais été mesuré. Il l'est maintenant, et l'effet de cette part
     * change de SENS selon le critère d'acceptation :
     *
     * <pre>
     * coût atteint à 900 s        part 0,5     part 0,8     part 1,0
     *   graine 42, HILL           1,458e12     1,196e12     1,488e12
     *   graine 42, LAHC-5           n/m        1,415e12     1,155e12
     *   graine 7,  HILL           1,389e12     1,179e12     1,275e12
     *   graine 7,  LAHC-5           n/m        1,373e12     0,975e12
     * </pre>
     *
     * <p>
     * Sous Hill Climbing, 0,8 est un optimum intérieur. Sous LAHC-5 — le critère retenu — la part
     * 1,0 écrase 0,8 de 22,5 % puis 40,8 %. Le défaut suit le critère retenu, sur deux graines.
     *
     * <p>
     * <b>Réserve, à ne pas taire :</b> la colonne 0,5 sous LAHC-5 n'est pas mesurée ({@code n/m}).
     * 1,0 bat 0,8 nettement, mais la forme de la courbe sous ce critère n'est pas établie, et elle
     * n'a pas la même forme que sous HILL. Ce défaut peut encore bouger — {@code bench-lift2.sh}
     * ferme la colonne.
     *
     * <p>
     * À 1,0 l'échange de position n'est jamais tiré ({@code swaps=0}) : la meilleure configuration
     * mesurée n'utilise qu'un des deux mouvements. Ce n'est pas une raison de le retirer —
     * {@code VIS-KKI-001}, un levier qui perd dans un régime devient un paramètre exposé, jamais
     * du code supprimé. C'est précisément à ça que sert cette part.
     */
    public static double reassignmentShare = 1.0;

}
