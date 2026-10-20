package cm.camtel.nextfleet.domain.fuel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FullToFullConsumptionCalculatorTest {

    private final FullToFullConsumptionCalculator calc = new FullToFullConsumptionCalculator();

    private static FuelFillRecord fill(String id, String at, String km, String liters, boolean full) {
        return new FuelFillRecord(id, Instant.parse(at), km == null ? null : new BigDecimal(km), new BigDecimal(liters), full);
    }

    @Test
    @DisplayName("REC-05 : exemple du cahier — 100 × (20 + 30) / 600 = 8,33 L/100 km")
    void cahierExample() {
        ConsumptionResult r = calc.latest(List.of(
                fill("P0", "2026-10-01T07:00:00Z", "10000", "45", true),     // plein initial : exclu
                fill("P1", "2026-10-03T07:00:00Z", "10250", "20", false),    // intermédiaire : inclus
                fill("P2", "2026-10-05T07:00:00Z", "10600", "30", true)));   // plein final : inclus
        assertTrue(r.isCalculated());
        assertEquals(new BigDecimal("8.33"), r.litersPer100Km());
        assertEquals(0, new BigDecimal("50").compareTo(r.litersCounted()));
        assertEquals(0, new BigDecimal("600").compareTo(r.distanceKm()));
        assertEquals(List.of("P1", "P2"), r.countedFillIds());
        assertTrue(r.explanation().contains("P0 exclu"), r.explanation());
    }

    @Test
    @DisplayName("REC-05 : relevé initial absent → « données insuffisantes », aucune valeur inventée")
    void missingInitialOdometer() {
        ConsumptionResult r = calc.latest(List.of(
                fill("P0", "2026-10-01T07:00:00Z", null, "45", true),
                fill("P1", "2026-10-03T07:00:00Z", "10250", "20", false),
                fill("P2", "2026-10-05T07:00:00Z", "10600", "30", true)));
        assertFalse(r.isCalculated());
        assertNull(r.litersPer100Km());
        assertTrue(r.explanation().startsWith("Données insuffisantes"));
    }

    @Test
    @DisplayName("Un plein partiel ne clôt pas la période")
    void partialDoesNotClose() {
        ConsumptionResult r = calc.latest(List.of(
                fill("P0", "2026-10-01T07:00:00Z", "10000", "45", true),
                fill("P1", "2026-10-03T07:00:00Z", "10600", "30", false)));
        assertFalse(r.isCalculated());
    }

    @Test
    @DisplayName("Compteur décroissant → insuffisant et renvoi vers CAR-03")
    void decreasingOdometer() {
        ConsumptionResult r = calc.latest(List.of(
                fill("P0", "2026-10-01T07:00:00Z", "12500", "45", true),
                fill("P1", "2026-10-05T07:00:00Z", "12400", "30", true)));
        assertFalse(r.isCalculated());
        assertTrue(r.explanation().contains("CAR-03"));
    }

    @Test
    @DisplayName("Plusieurs périodes : chaque couple de pleins complets est calculé ; l'ordre de saisie est indifférent")
    void multiplePeriods() {
        List<ConsumptionResult> all = calc.allPeriods(List.of(
                fill("P2", "2026-10-05T07:00:00Z", "10600", "30", true),
                fill("P0", "2026-10-01T07:00:00Z", "10000", "45", true),
                fill("P3", "2026-10-09T07:00:00Z", "11100", "40", true),
                fill("P1", "2026-10-03T07:00:00Z", "10250", "20", false)));
        assertEquals(2, all.size());
        assertEquals(new BigDecimal("8.33"), all.get(0).litersPer100Km());
        assertEquals(new BigDecimal("8.00"), all.get(1).litersPer100Km());   // 100 × 40 / 500
        assertFalse(calc.latest(List.of()).isCalculated());
    }
}
