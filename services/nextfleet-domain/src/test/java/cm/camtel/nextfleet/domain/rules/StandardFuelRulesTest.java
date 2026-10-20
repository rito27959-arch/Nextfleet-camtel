package cm.camtel.nextfleet.domain.rules;

import cm.camtel.nextfleet.domain.fuel.FuelFillRecord;
import cm.camtel.nextfleet.domain.rules.FuelControlContext.FilledReceipt;
import cm.camtel.nextfleet.domain.rules.FuelControlContext.Immobilisation;
import cm.camtel.nextfleet.domain.rules.FuelControlContext.InvoiceLineMatch;
import cm.camtel.nextfleet.domain.rules.FuelControlContext.OdometerReading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StandardFuelRulesTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static FilledReceipt receipt(String id, String at, String liters, String supplier, String ref) {
        return new FilledReceipt(new FuelFillRecord(id, Instant.parse(at), null, d(liters), true), supplier, ref);
    }

    private static FuelControlContext ctx(List<FilledReceipt> fills, List<OdometerReading> odo,
                                          List<Immobilisation> imm, List<InvoiceLineMatch> inv,
                                          String capacity, boolean verified) {
        return new FuelControlContext("V1", "CE 4401 LT", capacity == null ? null : d(capacity), verified, fills, odo, imm, inv);
    }

    @Test
    @DisplayName("CAR-02 : deux saisies du même reçu détectées (casse et espaces ignorés)")
    void duplicateReceipt() {
        var f = new StandardFuelRules.Car02DuplicateReceipt().evaluate(ctx(List.of(
                receipt("F1", "2026-10-01T08:00:00Z", "40", "ST-1", "R-77"),
                receipt("F2", "2026-10-02T08:00:00Z", "40", "ST-1", " r-77 "),
                receipt("F3", "2026-10-02T08:00:00Z", "40", "ST-2", "R-77")), List.of(), List.of(), List.of(), null, false));
        assertEquals(1, f.size());
        assertEquals(List.of("F1", "F2"), f.get(0).evidence().get("pleins"));
    }

    @Test
    @DisplayName("CAR-03 : recul de 12 500 à 12 400 km signalé ; changement de compteur documenté ignoré")
    void odometerRegression() {
        var f = new StandardFuelRules.Car03OdometerRegression().evaluate(ctx(List.of(), List.of(
                new OdometerReading("O1", Instant.parse("2026-10-01T08:00:00Z"), d("12500"), 0, null),
                new OdometerReading("O2", Instant.parse("2026-10-02T08:00:00Z"), d("12400"), 0, null),
                new OdometerReading("O3", Instant.parse("2026-10-03T08:00:00Z"), d("15"), 1, "Compteur remplacé, PV 12")),
                List.of(), List.of(), null, false));
        assertEquals(1, f.size());
        assertEquals("-100", f.get(0).evidence().get("ecart_km"));
        assertTrue(f.get(0).summary().contains("12500 à 12400"));
    }

    @Test
    @DisplayName("CAR-04 : 80 L pour une capacité documentée de 60 L ; qualité PARTIELLE si capacité non vérifiée")
    void tankCapacity() {
        var fills = List.of(receipt("F1", "2026-10-01T08:00:00Z", "80", "ST-1", "R1"),
                receipt("F2", "2026-10-02T08:00:00Z", "55", "ST-1", "R2"));
        var verified = new StandardFuelRules.Car04TankCapacity().evaluate(ctx(fills, List.of(), List.of(), List.of(), "60", true));
        assertEquals(1, verified.size());
        assertEquals(Finding.DataQuality.FIABLE, verified.get(0).dataQuality());
        var unverified = new StandardFuelRules.Car04TankCapacity().evaluate(ctx(fills, List.of(), List.of(), List.of(), "60", false));
        assertEquals(Finding.DataQuality.PARTIELLE, unverified.get(0).dataQuality());
        assertTrue(new StandardFuelRules.Car04TankCapacity().evaluate(ctx(fills, List.of(), List.of(), List.of(), null, false)).isEmpty(),
                "Sans capacité connue, aucune alerte n'est inventée");
    }

    @Test
    @DisplayName("CAR-06 : plein pendant réparation signalé, sauf exception atelier")
    void immobilisation() {
        var fills = List.of(receipt("F1", "2026-10-05T08:00:00Z", "30", "ST-1", "R1"));
        var imm = new Immobilisation("OT-9", Instant.parse("2026-10-04T00:00:00Z"), Instant.parse("2026-10-06T00:00:00Z"), false);
        assertEquals(1, new StandardFuelRules.Car06FillDuringImmobilisation()
                .evaluate(ctx(fills, List.of(), List.of(imm), List.of(), null, false)).size());
        var allowed = new Immobilisation("OT-9", imm.from(), imm.to(), true);
        assertTrue(new StandardFuelRules.Car06FillDuringImmobilisation()
                .evaluate(ctx(fills, List.of(), List.of(allowed), List.of(), null, false)).isEmpty());
    }

    @Test
    @DisplayName("REC-IA-01 / CAR-07 : facture de 55 L et reçu de 50 L → écart de 5 L documenté")
    void invoiceGap() {
        var lines = List.of(
                new InvoiceLineMatch("FAC-1", 1, "F1", d("55"), d("44000"), d("50"), d("40000")),
                new InvoiceLineMatch("FAC-1", 2, "F2", d("20"), d("16000"), d("20"), d("16000")),
                new InvoiceLineMatch("FAC-1", 3, null, d("10"), d("8000"), null, null));
        var f = new StandardFuelRules.Car07InvoiceGap().evaluate(ctx(List.of(), List.of(), List.of(), lines, null, false));
        assertEquals(2, f.size());
        Finding gap = f.get(0);
        assertEquals("5", gap.evidence().get("ecart_litres"));
        assertEquals("4000", gap.evidence().get("ecart_xaf"));
        assertEquals(Finding.Severity.HIGH, gap.severity());
        assertEquals("CAR-07", gap.ruleId());
        assertEquals(1, gap.ruleVersion());
        assertEquals(Finding.DataQuality.PARTIELLE, f.get(1).dataQuality());
    }

    @Test
    @DisplayName("Moteur : constatations dédoublonnées, aucune ne qualifie une fraude")
    void engineDedupAndWording() {
        var c = ctx(List.of(receipt("F1", "2026-10-01T08:00:00Z", "80", "ST-1", "R1")), List.of(), List.of(), List.of(), "60", true);
        RuleEngine engine = RuleEngine.standard();
        var first = engine.run(c);
        var replay = engine.run(c);
        assertEquals(first.stream().map(Finding::dedupKey).toList(), replay.stream().map(Finding::dedupKey).toList());
        for (Finding f : first) {
            assertFalse(f.summary().toLowerCase().contains("fraud"), "Aucune qualification de fraude : " + f.summary());
        }
    }
}
