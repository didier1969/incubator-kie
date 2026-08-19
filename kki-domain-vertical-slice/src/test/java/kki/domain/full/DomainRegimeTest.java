package kki.domain.full;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Comparator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Le banc de qualification — `DEC-KKI-005` : « la cible doit devenir une ENVELOPPE : elle tient
 * sur une FAMILLE d'instances couvrant le domaine, pas sur un point. »
 *
 * <p>
 * Deux choses à protéger, et elles sont de natures différentes :
 * <ul>
 * <li><b>l'hygiène du paramétrage</b> — les dimensions sont des statiques mutables, donc un état
 * global. Une seule oubliée dans {@code reset()} contamine silencieusement toute instance
 * générée après elle, et rend fausses des mesures qui paraissent normales ;</li>
 * <li><b>l'existence des DEUX régimes</b> — `DEC-KKI-005` dit qu'un produit qui n'en couvre
 * qu'un ne sert que la moitié de sa base. Si le générateur ne savait produire que le régime
 * saturé, tout le banc reviendrait à mesurer un point unique en croyant couvrir un domaine.</li>
 * </ul>
 */
class DomainRegimeTest {

    @BeforeEach
    void resetDomainParameters() {
        FullDataGenerator.reset();
    }

    @Test
    void resetRestoresEveryDimensionWithoutException() throws Exception {
        // Par réflexion, pour que l'ajout d'une dimension future SANS ligne dans reset() fasse
        // échouer ce test. Une vérification écrite à la main serait justement celle qu'on
        // oublierait de mettre à jour en même temps que reset().
        for (Field field : mutableDimensions()) {
            Object reference = field.get(null);
            perturb(field);
            assertTrue(!reference.equals(field.get(null)),
                    "la perturbation de " + field.getName() + " n'a rien changé : le test ne mord pas");
            FullDataGenerator.reset();
            assertEquals(reference, field.get(null),
                    field.getName() + " n'est pas restauré par reset() — toute instance générée"
                            + " après un test qui y touche mesurerait un autre modèle");
        }
    }

    @Test
    void theGeneratorCanProduceBothRegimesNotJustTheSaturatedOne() {
        // Régime saturé : le retard écrase tout, aucun autre levier ne peut s'y voir.
        Measured saturated = Measured.of(2000, 42L);
        assertTrue(saturated.lateShare > 0.5,
                "le point de référence doit être saturé, part en retard " + saturated.lateShare);
        assertTrue(saturated.tardinessShare > 0.9,
                "en saturé le retard doit dominer, part " + saturated.tardinessShare);

        // Régime sous-chargé : l'avance domine, et les termes physiques deviennent visibles.
        // Les trois desserrages sont ceux qui composent la bascule ; aucun ne suffit seul, ce
        // qui est le résultat central du balayage.
        FullDataGenerator.setterCount = 1000;
        FullDataGenerator.levelDemandSkew = 0.0;
        FullDataGenerator.setterWorkingDays = 7;
        FullDataGenerator.setterWindowSeconds = 24L * 3600L;
        FullDataGenerator.setterWindowStartSeconds = 0L;
        Measured relaxed = Measured.of(400, 42L);
        assertTrue(relaxed.lateShare < 0.2,
                "le point desserré doit sortir de la saturation, part en retard " + relaxed.lateShare);
        assertTrue(relaxed.earlinessShare > 0.5,
                "en sous-charge l'AVANCE doit dominer, part " + relaxed.earlinessShare);
    }

    @Test
    void jitDatingIsWorthMuchMoreWhenEarlinessDominates() {
        // Le chiffre qui rouvre la question du câblage du score : la datation JIT ne vaut rien
        // là où tout est en retard, et beaucoup là où l'avance domine. Un banc qui ne mesurerait
        // que le régime saturé conclurait que la passe amont ne sert à rien.
        Measured saturated = Measured.of(2000, 42L);
        FullDataGenerator.setterCount = 1000;
        FullDataGenerator.levelDemandSkew = 0.0;
        FullDataGenerator.setterWorkingDays = 7;
        FullDataGenerator.setterWindowSeconds = 24L * 3600L;
        FullDataGenerator.setterWindowStartSeconds = 0L;
        Measured relaxed = Measured.of(400, 42L);

        assertTrue(relaxed.jitGain > 10.0 * Math.max(0.01, saturated.jitGain),
                "la datation JIT doit valoir un ordre de grandeur de plus en sous-charge :"
                        + " saturé " + saturated.jitGain + " %, desserré " + relaxed.jitGain + " %");
    }

    private static Field[] mutableDimensions() {
        return java.util.Arrays.stream(FullDataGenerator.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> !Modifier.isFinal(field.getModifiers()))
                .toArray(Field[]::new);
    }

    private static void perturb(Field field) throws Exception {
        Class<?> type = field.getType();
        if (type == int.class) {
            field.setInt(null, field.getInt(null) + 7);
        } else if (type == long.class) {
            field.setLong(null, field.getLong(null) + 701L);
        } else if (type == double.class) {
            field.setDouble(null, field.getDouble(null) + 0.37);
        } else {
            throw new AssertionError("dimension de type non prévu : " + field);
        }
    }

    private record Measured(double lateShare, double tardinessShare, double earlinessShare,
            double jitGain) {

        static Measured of(int orders, long seed) {
            JobShopSolution problem = FullDataGenerator.generate(orders, seed);
            problem.getScheduleList().get(0).getOrderSequence()
                    .sort(Comparator.comparingLong(Order::getDueEpochSec));
            FullScoreCalculator calculator = new FullScoreCalculator();
            calculator.resetWorkingSolution(problem);
            FullScoreCalculator.ColdSweep cold = calculator.coldSweep();
            long total = Math.max(1L, -cold.soft());
            long late = problem.getOrderList().stream()
                    .filter(o -> cold.completions()[(int) o.getId()] > o.getDueEpochSec())
                    .count();
            long jit = calculator.backwardSweep().jitCostCents();
            return new Measured((double) late / problem.getOrderList().size(),
                    (double) cold.tardiness() / total, (double) cold.earliness() / total,
                    100.0 * (total - jit) / total);
        }
    }
}
