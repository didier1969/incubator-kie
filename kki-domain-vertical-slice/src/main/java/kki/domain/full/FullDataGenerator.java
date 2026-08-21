package kki.domain.full;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Instance du domaine COMPLET. Aucun mécanisme retiré.
 *
 * <p>
 * Paramètres calés sur PIL-KKI-004 et CPT-KKI-008 : 1000 machines en 5 technologies × 10
 * sous-types × 20 machines, coût horaire de 60 à 150 CHF par paliers de 10 le long de l'échelle,
 * chaînes de 1 à 6 passes, 200 articles, horizon 6 mois. La durée d'opération est calée sur
 * l'intervalle 5–21 h établi en REQ-KKI-010 : c'est le seul qui rende cohérents 5000 ordres,
 * ~4,5 opérations par ordre, 1000 machines et 6 mois.
 *
 * <p>
 * Deux choix qui font l'instance et qu'il faut voir :
 * <ul>
 * <li><b>Axe Z réel</b> — une passe sur deux revient sur une machine déjà visitée par le même
 * ordre, au lieu d'attendre que le hasard le fasse.</li>
 * <li><b>Demande asymétrique sur l'échelle</b> — les articles simples dominent, donc le bas de
 * l'échelle est sous tension et le haut sert de soupape. C'est le régime que le détecteur
 * identifie comme porteur de goulots.</li>
 * </ul>
 */
public final class FullDataGenerator {

    /** Un lundi 00:00, pour que le calendrier metteur ait un sens. */
    public static final long ORIGIN_EPOCH = 1_700_006_400L - (1_700_006_400L % 604_800L);

    private FullDataGenerator() {
    }

    // ************************************************************************
    // Dimensions du domaine — DEC-KKI-005 : des paramètres du banc, jamais des chiffres à
    // demander au client, et jamais des constantes cachées dans le générateur.
    //
    // ⚠️ Ce sont des statiques MUTABLES, donc un état global partagé par toute la JVM de test.
    // Tout appelant qui en change un DOIT passer par reset() en entrée et restaurer en sortie :
    // Surefire exécute les classes de test dans une seule JVM, et une contamination silencieuse
    // rendrait vertes des mesures faites sur un tout autre modèle.
    // ************************************************************************

    /** Horizon de planification. */
    public static long horizonSeconds = 6L * 30 * 24 * 3600;
    /**
     * Jours à résolution journalière dans les calendriers. Au-delà, motif hebdomadaire — c'est la
     * simplification que l'opérateur autorise : « au-delà de six mois, c'est beaucoup moins
     * important ». Les données de terrain iront jusqu'à deux ans quand elles existeront ; la
     * structure les accepte sans changement, seule cette valeur monte.
     */
    public static int fineDayCount = 182;
    /**
     * Nombre de SECTEURS de machines. L'horaire est une propriété du secteur, pas de la machine
     * — « c'est en général par secteur ». C'est aussi ce qui rend le partage possible : douze
     * motifs pour mille machines, référencés et jamais recopiés.
     */
    public static int machineSectorCount = 12;
    /**
     * Nombre de PROFILS d'horaire du personnel. Un profil est un couple (heure de prise de poste,
     * durée) : certains commencent plus tôt, d'autres plus tard ; certains font quatre heures,
     * la plupart huit — huit étant le maximum courant.
     */
    public static int setterProfileCount = 8;
    /** Jours fériés par an, fermés pour le personnel et pour les secteurs non continus. */
    public static int holidaysPerYear = 10;
    /** Nombre d'articles distincts. Redimensionne l'instance sans changer le régime. */
    public static int articleCount = 200;
    /** Longueur maximale d'une chaîne d'opérations (PIL-KKI-004 : 1 à 6 passes). */
    public static int maxPasses = 6;
    /** Nombre de technologies. Redimensionne sans changer le régime. */
    public static int technologies = 5;
    /** Nombre de niveaux par technologie — la longueur de l'échelle de substitution ascendante. */
    public static int levels = 10;
    /** Machines par (technologie, niveau). */
    public static int machinesPerLevel = 20;
    /**
     * Durée d'usinage : 8 h à 23 h.
     *
     * <p>
     * CALIBRÉE, pas posée : bissection sur la charge nominale mesurée jusqu'à ce que la moyenne
     * des postes atteigne les quatre-vingts pour cent de la zone cible. La valeur précédente
     * (5 h à 21 h) venait d'un calage de volumétrie et laissait l'atelier à 113 %.
     */
    public static long minDurationSeconds = 8L * 3600L;
    public static long durationSpreadSeconds = 15L * 3600L;
    /** Exposant de la loi sur le niveau technologique requis. 2 = les articles simples dominent. */
    public static double levelDemandSkew = 2.0;
    /**
     * Nombre de metteurs en train — POINT DE TRAVAIL, ajusté ici et jamais demandé.
     *
     * <p>
     * Le produit est le système APS3D ; le plan n'est que l'instrument qui sert à l'éprouver.
     * Une valeur d'instance manquante est donc une dimension à ajuster ici, pas une question à
     * poser à l'opérateur.
     *
     * <p>
     * CALIBRÉ par bissection sur la charge MESURÉE, dans l'intervalle que l'opérateur borne
     * lui-même : au moins un par technologie, et « neuf cents, c'est beaucoup trop ». Retenu :
     * 242 metteurs pour une charge de 77 %.
     *
     * <p>
     * La borne arithmétique « heures de travail à fournir / heures offertes par metteur » n'a PAS
     * servi de réponse. Elle ne regarde que la conservation du travail, quand ce qui sature est le
     * temps MUR pendant lequel la machine attend son metteur ; deux chiffres obtenus ainsi ont
     * déjà dû être retirés. Elle sert de point de départ, la mesure tranche.
     */
    public static int setterCount = 242;
    /** Nombre de technologies que chaque metteur sait régler. 1 = spécialiste, 5 = polyvalent. */
    public static int setterSkillBreadth = 2;
    /**
     * Jours ouvrés par semaine du calendrier metteur, et longueur de la plage quotidienne.
     *
     * <p>
     * Ces deux-là étaient en dur, et ce sont les plus lourdes de conséquences du générateur : une
     * mise en train de 16 h de temps METTEUR immobilise la machine pendant tout son temps MUR.
     * À 3 jours × 8 h, la semaine ouvre 24 h sur 168 : le temps mur vaut alors 7 fois le temps
     * travaillé. Les laisser cachées revenait à figer un multiplicateur de 7 sur toute la durée
     * physique de l'atelier sans jamais l'écrire.
     */
    public static int setterWorkingDays = 5;
    public static long setterWindowSeconds = 8L * 3600L;
    /** Heure d'ouverture de la plage metteur. */
    public static long setterWindowStartSeconds = 8L * 3600L;
    /** Part des metteurs qui connaissent une absence sur l'horizon (CPT-KKI-007, « maladie »). */
    public static double setterAbsenceShare = 0.15;
    /** Durée d'une absence de metteur. */
    public static long setterAbsenceSeconds = 5L * 24 * 3600L;
    /** Part des machines qui ne tournent PAS en continu (CPT-KKI-007 : « peut » tourner 24/7). */
    public static double nonContinuousMachineShare = 0.3;
    /** Nombre moyen de fenêtres de maintenance par machine sur l'horizon. */
    public static double maintenanceWindowsPerMachine = 1.5;
    /** Durée d'une fenêtre de maintenance. */
    public static long maintenanceWindowSeconds = 24L * 3600L;
    /** Nombre de TYPES d'outillage : plusieurs clés (article, passe) partagent un même montage. */
    public static int toolingTypeCount = 60;
    /** Exemplaires détenus par type. C'est ce qui rend le pool FINI (CPT-KKI-006). */
    public static int toolingCopiesPerType = 2;
    /** Part des opérations dont la mise en train emprunte un outillage. */
    public static double toolingRequirementShare = 0.4;
    /**
     * Horizon du gel SOUPLE. `CPT-KKI-004` exige explicitement qu'il soit paramétrable : c'est
     * le curseur entre stabilité du plan publié et liberté de réordonnancement.
     */
    public static long softFreezeHorizonSeconds = 21L * 24 * 3600L;
    /** Horizon en deçà duquel un ordre PEUT être à verrou dur (déjà lancé). */
    public static long hardFreezeHorizonSeconds = 7L * 24 * 3600L;
    /** Part des ordres de cet horizon qui sont effectivement verrouillés durs. */
    public static double hardFreezeShare = 0.5;
    /**
     * Marge minimale d'une date due au-dessus du temps de traversée de son ordre. 0 = due au plus
     * juste, 1 = deux fois le temps de traversée. C'est le curseur entre un carnet tendu et un
     * carnet confortable, et il décide si le retard mesuré est de l'ordonnancement ou de
     * l'infaisabilité.
     */
    public static double dueSlackFactor = 0.2;
    /**
     * Part des machines occupées à l'origine par un travail DÉJÀ LANCÉ, que le solveur SUBIT.
     *
     * <p>
     * <b>Défaut ZÉRO, et ce n'est pas un choix par défaut mais une condition de rejouabilité</b>
     * (`VIS-KKI-001`) : à liste vide le calcul est identique au bit près à celui d'avant la
     * butée, donc toutes les campagnes archivées restent comparables. Le jour où une valeur est
     * mesurée, elle devient un argument de campagne — jamais une constante Java.
     *
     * <p>
     * Exposée par {@code -Dkki.claimShare=…}. Ce qu'une revendication porte et qu'un trou de
     * calendrier ne peut pas porter : l'ARTICLE laissé monté (`REQ-KKI-064`).
     */
    public static double claimShare = 0.0;
    /** Reste d'usinage d'un travail déjà lancé, en temps de travail machine. */
    public static long claimMachiningSeconds = 12L * 3600L;

    /**
     * Part des ordres DÉJÀ en retard au moment de la replanification. « Certains ordres peuvent
     * être dus dans le passé, c'est fréquent » — et l'horizon glissant de `PIL-KKI-005` en
     * fabrique en permanence : ce qui n'a pas été fait hier est en retard aujourd'hui.
     */
    public static double overdueShare = 0.12;
    /** Profondeur maximale du retard déjà constitué. */
    public static long overdueDepthSeconds = 21L * 24 * 3600L;

    /**
     * Restaure toutes les dimensions à leur valeur de référence.
     *
     * <p>
     * À appeler en ENTRÉE de tout montage qui touche un paramètre, pas seulement en sortie : une
     * restauration en sortie ne protège pas d'un appelant précédent qui aurait levé avant la
     * sienne. Les deux ensemble rendent chaque instance reproductible quel que soit l'ordre
     * d'exécution des tests.
     */
    public static void reset() {
        horizonSeconds = 6L * 30 * 24 * 3600;
        fineDayCount = 182;
        machineSectorCount = 12;
        setterProfileCount = 8;
        holidaysPerYear = 10;
        articleCount = 200;
        maxPasses = 6;
        technologies = 5;
        levels = 10;
        machinesPerLevel = 20;
        minDurationSeconds = 8L * 3600L;
        durationSpreadSeconds = 15L * 3600L;
        levelDemandSkew = 2.0;
        setterCount = 242;
        setterSkillBreadth = 2;
        setterWorkingDays = 5;
        setterWindowSeconds = 8L * 3600L;
        setterWindowStartSeconds = 8L * 3600L;
        setterAbsenceShare = 0.15;
        setterAbsenceSeconds = 5L * 24 * 3600L;
        nonContinuousMachineShare = 0.3;
        maintenanceWindowsPerMachine = 1.5;
        maintenanceWindowSeconds = 24L * 3600L;
        toolingTypeCount = 60;
        toolingCopiesPerType = 2;
        toolingRequirementShare = 0.4;
        softFreezeHorizonSeconds = 21L * 24 * 3600L;
        hardFreezeHorizonSeconds = 7L * 24 * 3600L;
        hardFreezeShare = 0.5;
        dueSlackFactor = 0.2;
        overdueShare = 0.12;
        overdueDepthSeconds = 21L * 24 * 3600L;
        claimShare = 0.0;
        claimMachiningSeconds = 12L * 3600L;
    }

    public static JobShopSolution generate(int orderCount, long seed) {
        Random random = new Random(seed);
        SetupMatrix setupMatrix = new SetupMatrix(articleCount, maxPasses, seed);
        ShiftCatalog catalog = ShiftCatalog.build(random);
        int totalMachines = technologies * levels * machinesPerLevel;

        List<Machine> machines = new ArrayList<>(technologies * levels * machinesPerLevel);
        for (int technology = 0; technology < technologies; technology++) {
            for (int level = 0; level < levels; level++) {
                for (int k = 0; k < machinesPerLevel; k++) {
                    long id = machines.size();
                    // 60 CHF/h en bas d'échelle, +10 par palier, 150 en haut.
                    // Le secteur regroupe des machines contiguës : elles partagent l'horaire.
                    int sector = (int) (id * Math.max(1, machineSectorCount) / totalMachines);
                    machines.add(new Machine(id, technology, level, 6_000L + 1_000L * level,
                            machineCalendarOf(catalog, sector, random)));
                }
            }
        }

        // Les metteurs sont des ressources comme les autres : calendrier propre, et compétences.
        // Les technologies sont attribuées en rotation, ce qui garantit qu'aucune ne se retrouve
        // sans personne pour la régler — une couverture trouée rendrait l'instance infaisable
        // pour une raison qui n'a rien à voir avec l'ordonnancement.
        // PLANCHER DUR, appliqué ici et pas seulement dans reset() : sans au moins un metteur
        // par technologie, aucune mise en train n'est réalisable sur la technologie découverte,
        // et le générateur produit une instance silencieusement INFAISABLE — ou lève sur une
        // liste de compétents vide. Un balayage qui descendrait le paramètre trop bas ferait
        // alors MENTIR le générateur, pas seulement mal mesurer.
        int effectiveSetterCount = Math.max(technologies, setterCount);
        List<Setter> setters = new ArrayList<>(effectiveSetterCount);
        for (int i = 0; i < effectiveSetterCount; i++) {
            boolean[] mastered = new boolean[technologies];
            for (int b = 0; b < Math.min(setterSkillBreadth, technologies); b++) {
                mastered[(i + b) % technologies] = true;
            }
            setters.add(new Setter(i, setterCalendarOf(catalog, i % Math.max(1, setterProfileCount),
                    random), mastered));
        }
        List<List<Setter>> settersByTechnology = new ArrayList<>(technologies);
        for (int technology = 0; technology < technologies; technology++) {
            List<Setter> competent = new ArrayList<>();
            for (Setter setter : setters) {
                if (setter.canSetUp(machines.get(technology * levels * machinesPerLevel))) {
                    competent.add(setter);
                }
            }
            settersByTechnology.add(competent);
        }
        int[] nextSetterOfTechnology = new int[technologies];
        // Un compteur de rotation par bande (technologie, niveau) : réparti sur les vingt postes
        // du niveau au lieu d'en désigner un au hasard, ce qui laissait des postes vides.
        int[] nextWorkcenterOfBand = new int[technologies * levels];

        // Le pool d'outillage : `toolingCopiesPerType` exemplaires interchangeables par type.
        // MÉMOÏSATION — la plage de valeurs de la réaffectation ne dépend que du type, donc une
        // liste par type, partagée par toutes les opérations qui l'exigent.
        List<Tooling> toolings = new ArrayList<>(toolingTypeCount * toolingCopiesPerType);
        List<List<Tooling>> toolingsByType = new ArrayList<>(toolingTypeCount);
        for (int type = 0; type < toolingTypeCount; type++) {
            List<Tooling> copies = new ArrayList<>(toolingCopiesPerType);
            for (int c = 0; c < toolingCopiesPerType; c++) {
                Tooling tooling = new Tooling(toolings.size(), type);
                toolings.add(tooling);
                copies.add(tooling);
            }
            toolingsByType.add(List.copyOf(copies));
        }
        int[] nextToolingOfType = new int[toolingTypeCount];

        // MÉMOÏSATION — la plage de valeurs de M2 ne dépend que du couple (technologie, niveau) :
        // 50 listes partagées par les 17 515 opérations, construites une fois. Compatibilité
        // ASCENDANTE : les niveaux égaux ou supérieurs de la technologie, jamais en dessous.
        List<List<List<Machine>>> compatibleByTechAndLevel = new ArrayList<>(technologies);
        for (int technology = 0; technology < technologies; technology++) {
            List<List<Machine>> byLevel = new ArrayList<>(levels);
            for (int level = 0; level < levels; level++) {
                List<Machine> compatible = new ArrayList<>();
                for (Machine machine : machines) {
                    if (machine.canRun(technology, level)) {
                        compatible.add(machine);
                    }
                }
                byLevel.add(List.copyOf(compatible));
            }
            compatibleByTechAndLevel.add(byLevel);
        }

        // GAMMES — une par ARTICLE, pas une par ordre.
        //
        // `PIL-KKI-004` : « ordres = chaînes séquentielles de 1 à 6 opérations pour UN article ».
        // La gamme — nombre de passes, technologie, niveau requis, durée, machine de référence —
        // est une propriété de ce qu'on fabrique, pas de la commande qui le demande. Tirer une
        // gamme par ordre rendait `setup(A→A) = 0` de `CPT-KKI-006` INATTEIGNABLE : deux ordres
        // du même article n'avaient aucune raison de se croiser sur une machine, donc chaque
        // opération payait toujours une mise en train pleine. Mesuré avant correction : 81,5 %
        // du temps machine en mise en train contre 1,3 % en usinage, et grouper les ordres par
        // article AGGRAVAIT le plan au lieu de l'améliorer.
        List<Routing> routings = new ArrayList<>(articleCount);
        for (int article = 0; article < articleCount; article++) {
            routings.add(Routing.draw(random, technologies));
        }

        List<Order> orders = new ArrayList<>(orderCount);
        List<Operation> operations = new ArrayList<>();
        long operationId = 0;
        for (long o = 0; o < orderCount; o++) {
            int articleId = random.nextInt(articleCount);
            Routing routing = routings.get(articleId);
            int priorityWeight = 1 + random.nextInt(5);
            // Les dates dues PEUVENT être dans le passé, et ça n'a rien d'anormal : l'opérateur
            // le dit fréquent. Un carnet réel porte toujours des ordres en retard au moment où on
            // le replanifie — ce sont même ceux qui comptent le plus. Ce qui est interdit, c'est
            // de PLANIFIER dans le passé, et le modèle le garantit déjà : aucune date de début
            // n'est antérieure à l'origine.
            //
            // La borne « date due au moins égale au temps de traversée » qui figurait ici était
            // donc fausse, et elle effaçait précisément la population la plus intéressante.
            double urgency = random.nextDouble();
            long due;
            if (urgency < overdueShare) {
                // Déjà en retard à l'instant de la replanification.
                double lateness = (urgency / Math.max(1e-9, overdueShare));
                due = ORIGIN_EPOCH - (long) ((1.0 - lateness) * overdueDepthSeconds);
            } else {
                double position = (urgency - overdueShare) / Math.max(1e-9, 1.0 - overdueShare);
                long traversal = traversalSeconds(routing, setupMatrix, articleId);
                long earliestDue = (long) (traversal * (1.0 + dueSlackFactor));
                due = ORIGIN_EPOCH
                        + (long) (position * Math.max(earliestDue, horizonSeconds));
            }
            Order.FreezeLevel freeze = freezeLevelOf(urgency, random);
            // Plan de référence : la date due elle-même sert de dernier plan publié — c'est la
            // référence la plus défavorable au solveur, donc la plus honnête pour mesurer.
            Order order = new Order(o, articleId, priorityWeight, due, freeze, due);

            int passCount = routing.passCount();
            List<Operation> chain = new ArrayList<>(passCount);
            long[] assignedPerPass = new long[passCount];
            for (int pass = 0; pass < passCount; pass++) {
                long duration = routing.durationSeconds()[pass];
                int technology = routing.technology()[pass];
                int requiredLevel = routing.requiredLevel()[pass];
                // AFFECTATION à un workcenter compatible. C'est une décision, pas une donnée de
                // la gamme : on prend le niveau EXIGÉ — le moins cher qui convienne, ce que ferait
                // un planificateur — et on tourne sur les postes de ce niveau plutôt que d'en
                // charger un seul. Monter sur l'échelle reste au système à décider.
                long machineId;
                if (routing.revisitOf()[pass] >= 0) {
                    machineId = assignedPerPass[routing.revisitOf()[pass]];
                } else {
                    int band = technology * levels * machinesPerLevel
                            + requiredLevel * machinesPerLevel;
                    machineId = band + nextWorkcenterOfBand[band / machinesPerLevel]++
                            % machinesPerLevel;
                }
                assignedPerPass[pass] = machineId;
                int setupKey = setupMatrix.keyOf(articleId, pass);
                // Le type d'outillage dérive de la CLÉ de mise en train, pas de la machine : le
                // montage appartient à ce qu'on fabrique, pas à ce sur quoi on le fabrique. Le
                // tirage est fait sur la clé et non au hasard, pour que deux ordres du même
                // article se disputent bien le même montage — c'est là qu'est la contention.
                int requiredToolingType = toolingTypeOf(setupKey);
                List<Tooling> compatibleToolings = requiredToolingType == Operation.NO_TOOLING
                        ? List.of()
                        : toolingsByType.get(requiredToolingType);
                Tooling tooling = compatibleToolings.isEmpty()
                        ? null
                        : compatibleToolings.get(
                                nextToolingOfType[requiredToolingType]++ % compatibleToolings.size());
                // Un metteur COMPÉTENT pour cette machine, distribué en rotation pour ne pas
                // concentrer artificiellement la charge sur le premier de la liste.
                List<Setter> competent = settersByTechnology.get(technology);
                Setter setter = competent.get(
                        nextSetterOfTechnology[technology]++ % competent.size());
                chain.add(new Operation(operationId++, order, pass, duration,
                        technology, requiredLevel, setupKey, requiredToolingType,
                        compatibleByTechAndLevel.get(technology).get(requiredLevel),
                        compatibleToolings,
                        machines.get((int) machineId), setter, tooling));
            }
            order.setOperations(chain);
            operations.addAll(chain);
            orders.add(order);
        }

        Schedule schedule = new Schedule();
        schedule.setOrderSequence(new ArrayList<>(orders));

        // TRAVAUX DÉJÀ LANCÉS — le solveur les SUBIT. Ce ne sont pas des ordres tronqués : la
        // revendication est posée À CÔTÉ, les gammes restent entières et les identifiants stables,
        // ce qui laisse `ProblemChange` opérant (manque n° 2 de l'audit).
        //
        // Les identifiants sont NÉGATIFS, et c'est ce qui les rend retirables : quand l'atelier
        // avance, on retire la revendication PAR SON IDENTITÉ. Une maintenance n'a jamais besoin
        // d'être retirée ; un ordre lancé se termine toujours.
        List<ResourceClaim> claims = new ArrayList<>();
        if (claimShare > 0.0) {
            // ⚠️ UN travail à la fois par ressource. Toutes ces revendications commencent à
            // l'origine, donc deux qui partageraient un metteur ou un exemplaire d'outillage se
            // CHEVAUCHERAIENT — un atelier où un metteur règle deux machines en même temps.
            //
            // Ce n'est pas hypothétique : la simple rotation `++ % taille` produisait, à part
            // 0,15 sur 5000 ordres graine 7, **30 collisions de metteur et 4 d'outillage**. La
            // butée ne fait que des `max` : elle absorbe le chevauchement sans rien dire, et la
            // campagne aurait mesuré le coût d'une instance physiquement impossible.
            boolean[] setterClaimed = new boolean[setters.size()];
            boolean[] toolingClaimed = new boolean[toolings.size()];
            for (Machine machine : machines) {
                if (random.nextDouble() >= claimShare) {
                    continue;
                }
                int technology = machine.getTechnology();
                List<Setter> competent = settersByTechnology.get(technology);
                Setter setter = null;
                for (int probe = 0; probe < competent.size(); probe++) {
                    Setter candidate = competent.get(
                            nextSetterOfTechnology[technology]++ % competent.size());
                    if (!setterClaimed[(int) candidate.getId()]) {
                        setter = candidate;
                        break;
                    }
                }
                if (setter == null) {
                    // Plus aucun metteur libre sur cette technologie : la part demandée dépasse ce
                    // que l'atelier peut porter. On n'invente pas un metteur en double — la part
                    // OBTENUE se lira dans le nombre de revendications émises.
                    continue;
                }
                setterClaimed[(int) setter.getId()] = true;
                int setupKey = setupMatrix.keyOf(random.nextInt(articleCount),
                        random.nextInt(maxPasses));
                int toolingType = toolingTypeOf(setupKey);
                int toolingId = ResourceClaim.NONE;
                if (toolingType != Operation.NO_TOOLING) {
                    List<Tooling> copies = toolingsByType.get(toolingType);
                    for (int probe = 0; probe < copies.size(); probe++) {
                        int candidate = (int) copies
                                .get(nextToolingOfType[toolingType]++ % copies.size()).getId();
                        if (!toolingClaimed[candidate]) {
                            toolingId = candidate;
                            toolingClaimed[candidate] = true;
                            break;
                        }
                    }
                    // Aucun exemplaire libre : la revendication n'emprunte rien. Un montage déjà
                    // pris par un autre travail ne peut pas l'être deux fois, et une revendication
                    // sans outillage reste une revendication valide.
                }
                // Les trois FINS sont dérivées par le moteur, chacune sur le calendrier de sa
                // PROPRE ressource — l'appelant ne publie qu'un début et un reste-à-faire.
                claims.add(ResourceClaim.ingest(-1L - claims.size(),
                        (int) machine.getId(), machine.getCalendar(),
                        (int) setter.getId(), setter.getCalendar(),
                        toolingId, setupKey, ORIGIN_EPOCH,
                        setupMatrix.coldStartSeconds(setupKey), claimMachiningSeconds));
            }
        }

        // À part zéro, le calcul est identique au bit près à celui d'avant la butée : toutes les
        // campagnes antérieures restent rejouables à l'identique (VIS-KKI-001 — un réglage mesuré
        // devient un paramètre, jamais un défaut codé en dur).
        return new JobShopSolution(orders, operations, machines, setters, toolings,
                List.of(schedule), List.copyOf(claims), setupMatrix, ORIGIN_EPOCH);
    }

    /**
     * Temps mur minimal pour exécuter une gamme, ressources supposées libres.
     *
     * <p>
     * La mise en train compte pour son temps MUR et non pour son temps de travail : c'est tout
     * l'objet de `CPT-KKI-007`. Le facteur est le rapport entre la semaine entière et les heures
     * ouvertes du personnel — à quarante heures sur cent soixante-huit, seize heures de réglage
     * immobilisent le poste plus de soixante heures.
     */
    private static long traversalSeconds(Routing routing, SetupMatrix setupMatrix, int articleId) {
        double wallClockFactor = 604_800.0
                / Math.max(1.0, (double) setterWorkingDays * setterWindowSeconds);
        long total = 0L;
        for (int pass = 0; pass < routing.passCount(); pass++) {
            long setup = setupMatrix.coldStartSeconds(setupMatrix.keyOf(articleId, pass));
            total += (long) (setup * wallClockFactor) + routing.durationSeconds()[pass];
        }
        return total;
    }

    /**
     * Le type d'outillage exigé par une clé (article, passe), ou {@link Operation#NO_TOOLING}.
     *
     * <p>
     * Fonction de la clé SEULE, donc déterministe et stable : deux ordres du même article se
     * disputent bien le même montage, ce qui est là où se trouve la contention. Un tirage au
     * hasard par opération l'aurait diluée sur tout le pool et rendu la ressource inerte.
     */
    private static int toolingTypeOf(int setupKey) {
        // SplitMix64 — pas de Random ici : la même clé doit toujours donner le même verdict, y
        // compris entre deux instances. Un simple produit de Knuth modulo une puissance de dix
        // ne mélange PAS assez les clés consécutives : mesuré, il rendait 31 % d'emprunts pour
        // une part demandée de 40 %.
        long mixed = setupKey * 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        if ((mixed >>> 11) / (double) (1L << 53) >= toolingRequirementShare) {
            return Operation.NO_TOOLING;
        }
        return setupKey % toolingTypeCount;
    }

    /**
     * La gamme d'un article : la même pour tous les ordres qui le demandent.
     *
     * <p>
     * Précision opérateur : « la gamme d'un article désigne une <b>technologie minimale</b>,
     * laquelle contient des workcenters ; les opérations sont affectées à des workcenters
     * compatibles ». La gamme ne nomme donc AUCUN workcenter — elle dit ce qu'il faut savoir
     * faire, pas où le faire. Le choix du poste est une décision du système, révisable par
     * substitution ascendante ; la figer dans la gamme revenait à décider à sa place, et laissait
     * mécaniquement inutilisés tous les postes que la gamme n'avait pas tirés.
     *
     * <p>
     * {@code revisitOf} porte l'axe Z de `CPT-KKI-005` : une passe peut exiger de revenir sur le
     * MÊME poste qu'une passe antérieure. C'est bien une propriété de la gamme — l'usinage
     * repasse sur le poste — et non une conséquence de l'affectation.
     */
    private record Routing(int passCount, int[] technology, int[] requiredLevel,
            long[] durationSeconds, int[] revisitOf) {

        static Routing draw(Random random, int technologies) {
            int passCount = 1 + random.nextInt(maxPasses);
            int[] technology = new int[passCount];
            int[] requiredLevel = new int[passCount];
            long[] durationSeconds = new long[passCount];
            int[] revisitOf = new int[passCount];
            for (int pass = 0; pass < passCount; pass++) {
                durationSeconds[pass] = minDurationSeconds
                        + (long) (random.nextDouble() * durationSpreadSeconds);
                if (pass > 0 && random.nextBoolean()) {
                    // Axe Z : cette passe revient sur le poste d'une passe antérieure, dont elle
                    // hérite donc l'exigence exacte.
                    int earlier = random.nextInt(pass);
                    revisitOf[pass] = earlier;
                    technology[pass] = technology[earlier];
                    requiredLevel[pass] = requiredLevel[earlier];
                } else {
                    revisitOf[pass] = -1;
                    technology[pass] = random.nextInt(technologies);
                    requiredLevel[pass] = skewedLevel(random);
                }
            }
            return new Routing(passCount, technology, requiredLevel, durationSeconds, revisitOf);
        }
    }

    /**
     * Les motifs d'horaire, construits UNE FOIS et partagés.
     *
     * <p>
     * C'est ici que se joue « éviter les explosions de ressources » : douze motifs de secteur et
     * huit profils de personnel couvrent mille neuf cents ressources. Chaque motif porte cent
     * quatre-vingt-deux enregistrements journaliers ; les dupliquer par ressource coûterait des
     * dizaines de mégaoctets pour une information identique.
     */
    private record ShiftCatalog(ShiftPattern[] machineSectors, ShiftPattern[] setterProfiles) {

        static ShiftCatalog build(Random random) {
            int[] holidays = holidayDays(random);

            ShiftPattern[] sectors = new ShiftPattern[Math.max(1, machineSectorCount)];
            for (int sector = 0; sector < sectors.length; sector++) {
                if (random.nextDouble() >= nonContinuousMachineShare) {
                    // Décolletage et assimilés : la machine tourne, jours fériés compris.
                    sectors[sector] = ShiftPattern.continuous(fineDayCount);
                } else if (random.nextBoolean()) {
                    // Deux équipes, du lundi au vendredi.
                    sectors[sector] = ShiftPattern.regular(fineDayCount, 0b001_1111,
                            6L * 3600L, 16L * 3600L, holidays);
                } else {
                    // Une équipe.
                    sectors[sector] = ShiftPattern.regular(fineDayCount, 0b001_1111,
                            7L * 3600L, 8L * 3600L, holidays);
                }
            }

            // Profils de personnel. Les trois axes que l'opérateur a nommés, dérivés des
            // paramètres d'horaire pour que le banc puisse encore les balayer :
            //   - prise de poste ÉCHELONNÉE autour de setterWindowStartSeconds ;
            //   - durée PLEINE (setterWindowSeconds, le maximum courant) ou DEMIE ;
            //   - semaine complète ou raccourcie d'un jour.
            int fullWeek = (1 << Math.max(1, Math.min(7, setterWorkingDays))) - 1;
            int shortWeek = fullWeek >> 1 != 0 ? fullWeek >> 1 : fullWeek;
            ShiftPattern[] profiles = new ShiftPattern[Math.max(1, setterProfileCount)];
            for (int profile = 0; profile < profiles.length; profile++) {
                long offset = ((profile % 4) - 1) * 3600L; // une heure plus tôt à deux plus tard
                long start = Math.max(0L, setterWindowStartSeconds + offset);
                long length = profile % 4 == 3 ? setterWindowSeconds / 2 : setterWindowSeconds;
                int days = profile % 3 == 2 ? shortWeek : fullWeek;
                profiles[profile] = ShiftPattern.regular(fineDayCount, days, start, length,
                        holidays);
            }
            return new ShiftCatalog(sectors, profiles);
        }

        /**
         * Jours fermés pour tout l'atelier — communs, donc dans le MOTIF et non par ressource.
         *
         * <p>
         * Dédupliqués : un tirage avec remise rendrait MOINS de jours fermés distincts que
         * demandé, et le nombre annoncé ne serait pas celui obtenu.
         */
        private static int[] holidayDays(Random random) {
            int wanted = Math.max(0, holidaysPerYear * fineDayCount / 365);
            java.util.Set<Integer> distinct = new java.util.TreeSet<>();
            for (int guard = 0; distinct.size() < wanted && guard < wanted * 20; guard++) {
                distinct.add(random.nextInt(Math.max(1, fineDayCount)));
            }
            return distinct.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /**
     * Calendrier d'une machine : le motif PARTAGÉ de son secteur, plus ses propres fenêtres de
     * MAINTENANCE — des indisponibilités datées, pas un mécanisme à part (CPT-KKI-004, 4e cas).
     * {@code withBlackouts} traverse le motif par référence : c'est ce qui préserve le partage.
     */
    private static WorkCalendar machineCalendarOf(ShiftCatalog catalog, int sector, Random random) {
        return new WorkCalendar(catalog.machineSectors()[sector], maintenanceWindows(random));
    }

    /** Arrêts de maintenance : quelques journées entières sur l'horizon, subies. */
    private static long[] maintenanceWindows(Random random) {
        int count = (int) Math.floor(maintenanceWindowsPerMachine)
                + (random.nextDouble() < maintenanceWindowsPerMachine % 1.0 ? 1 : 0);
        long[] windows = new long[count * 2];
        long spacing = horizonSeconds / Math.max(1, count);
        for (int i = 0; i < count; i++) {
            long start = i * spacing + (long) (random.nextDouble() * spacing * 0.6);
            windows[2 * i] = start;
            windows[2 * i + 1] = start + maintenanceWindowSeconds;
        }
        return windows;
    }

    /**
     * Calendrier d'un metteur : lundi-mercredi 8 h, plus d'éventuelles absences. La maladie de
     * CPT-KKI-007 ne demande aucun mécanisme dédié — c'est un trou dans SON calendrier.
     */
    private static WorkCalendar setterCalendarOf(ShiftCatalog catalog, int profile, Random random) {
        ShiftPattern pattern = catalog.setterProfiles()[profile];
        if (random.nextDouble() < setterAbsenceShare) {
            long start = (long) (random.nextDouble() * horizonSeconds * 0.9);
            return new WorkCalendar(pattern,
                    new long[] { start, start + setterAbsenceSeconds });
        }
        return new WorkCalendar(pattern, new long[0]);
    }

    /**
     * Trois paliers : verrou dur sur ce qui est déjà lancé (première semaine), gel souple sur
     * l'horizon de trois semaines, libre au-delà.
     */
    /**
     * Le palier de gel se lit sur l'URGENCE RELATIVE de l'ordre dans le carnet, pas sur un délai
     * absolu.
     *
     * <p>
     * Il l'était : « date due à moins de sept jours ». Depuis que la date due ne peut plus
     * précéder le temps de traversée de l'ordre — plusieurs semaines dès qu'une gamme compte
     * quelques passes — plus AUCUN ordre n'atteignait ce seuil, et les trois paliers de
     * `CPT-KKI-004` se réduisaient silencieusement à un seul. Deux tests l'ont attrapé.
     *
     * <p>
     * Ce que le gel décrit est de toute façon un état du cycle de vie — « déjà lancé »,
     * « publié » — donc la position de l'ordre dans le carnet, pas une distance à l'origine.
     *
     * @param urgency position de l'ordre dans la fenêtre de dates dues, 0 = le plus urgent
     */
    private static Order.FreezeLevel freezeLevelOf(double urgency, Random random) {
        double hardFraction = (double) hardFreezeHorizonSeconds / Math.max(1L, horizonSeconds);
        double softFraction = (double) softFreezeHorizonSeconds / Math.max(1L, horizonSeconds);
        if (urgency < hardFraction && random.nextDouble() < hardFreezeShare) {
            return Order.FreezeLevel.HARD;
        }
        if (urgency < softFraction) {
            return Order.FreezeLevel.SOFT;
        }
        return Order.FreezeLevel.FREE;
    }

    /** Demande en 1/rang² sur l'échelle : les articles simples dominent largement. */
    private static int skewedLevel(Random random) {
        double u = random.nextDouble();
        double total = 0.0;
        for (int level = 0; level < levels; level++) {
            total += 1.0 / Math.pow(level + 1.0, levelDemandSkew);
        }
        double cumulative = 0.0;
        for (int level = 0; level < levels; level++) {
            cumulative += 1.0 / Math.pow(level + 1.0, levelDemandSkew) / total;
            if (u <= cumulative) {
                return level;
            }
        }
        return levels - 1;
    }
}
