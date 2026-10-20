package cm.camtel.nextfleet.domain.fuel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FuelOrderTest {

    private static FuelOrder order() {
        return new FuelOrder("BON-1", "agent-1", new BigDecimal("60"), new BigDecimal("50000"), LocalDate.parse("2026-12-31"));
    }

    @Test
    @DisplayName("NF-40 : le demandeur ne valide pas son propre bon")
    void noSelfApproval() {
        FuelOrder o = order();
        o.submit();
        BusinessRuleViolation e = assertThrows(BusinessRuleViolation.class, () -> o.approve("agent-1"));
        assertEquals("NF-40", e.ruleCode());
        o.approve("controleur-1");
        assertEquals(FuelOrder.Status.APPROVED, o.status());
    }

    @Test
    @DisplayName("CAR-01 : bon de 60 L, 40 L acceptés puis 30 L refusés ; solde intact")
    void balanceProtected() {
        FuelOrder o = order();
        o.submit();
        o.approve("controleur-1");
        o.consume(new BigDecimal("40"), new BigDecimal("32000"), LocalDate.parse("2026-10-01"));
        assertEquals(FuelOrder.Status.PARTIALLY_CONSUMED, o.status());
        BusinessRuleViolation e = assertThrows(BusinessRuleViolation.class,
                () -> o.consume(new BigDecimal("30"), new BigDecimal("24000"), LocalDate.parse("2026-10-02")));
        assertEquals("CAR-01", e.ruleCode());
        assertEquals(0, new BigDecimal("20").compareTo(o.remainingLiters()));
        o.consume(new BigDecimal("20"), new BigDecimal("16000"), LocalDate.parse("2026-10-02"));
        assertEquals(FuelOrder.Status.SETTLED, o.status());
    }

    @Test
    @DisplayName("CAR-01 : bon non approuvé ou expiré inutilisable")
    void unusable() {
        FuelOrder o = order();
        assertThrows(BusinessRuleViolation.class,
                () -> o.consume(BigDecimal.ONE, BigDecimal.TEN, LocalDate.parse("2026-10-01")));
        o.submit();
        o.approve("c");
        assertThrows(BusinessRuleViolation.class,
                () -> o.consume(BigDecimal.ONE, BigDecimal.TEN, LocalDate.parse("2027-01-01")));
    }

    @Test
    @DisplayName("RG-02 : un bon entamé ne s'annule pas")
    void noCancelAfterConsumption() {
        FuelOrder o = order();
        o.submit();
        o.approve("c");
        o.consume(BigDecimal.ONE, BigDecimal.TEN, LocalDate.parse("2026-10-01"));
        assertThrows(BusinessRuleViolation.class, o::cancel);
    }
}
