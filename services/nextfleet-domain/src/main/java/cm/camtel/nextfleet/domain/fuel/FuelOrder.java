package cm.camtel.nextfleet.domain.fuel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Bon carburant (NF-40) : machine à états et solde. La base applique les mêmes invariants
 * (trigger {@code nf_consume_fuel_order}) ; cette classe permet de refuser tôt et d'expliquer.
 */
public final class FuelOrder {

    public enum Status { DRAFT, SUBMITTED, APPROVED, PARTIALLY_CONSUMED, SETTLED, CANCELLED, EXPIRED }

    private final String number;
    private final String requestedBy;
    private final BigDecimal authorizedLiters;
    private final BigDecimal authorizedAmount;
    private final LocalDate validUntil;
    private BigDecimal deliveredLiters = BigDecimal.ZERO;
    private BigDecimal deliveredAmount = BigDecimal.ZERO;
    private String approvedBy;
    private Status status = Status.DRAFT;

    public FuelOrder(String number, String requestedBy, BigDecimal authorizedLiters,
                     BigDecimal authorizedAmount, LocalDate validUntil) {
        this.number = Objects.requireNonNull(number);
        this.requestedBy = Objects.requireNonNull(requestedBy);
        if (authorizedLiters.signum() <= 0 || authorizedAmount.signum() <= 0) {
            throw new IllegalArgumentException("Volume et montant autorisés doivent être positifs");
        }
        this.authorizedLiters = authorizedLiters;
        this.authorizedAmount = authorizedAmount;
        this.validUntil = Objects.requireNonNull(validUntil);
    }

    public void submit() {
        require(status == Status.DRAFT, "Seul un brouillon peut être soumis");
        status = Status.SUBMITTED;
    }

    /** Validation indépendante : le demandeur ne valide jamais son propre bon. */
    public void approve(String approverId) {
        require(status == Status.SUBMITTED, "Seul un bon soumis peut être approuvé (statut " + status + ")");
        if (requestedBy.equals(approverId)) {
            throw new BusinessRuleViolation("NF-40", "Le demandeur ne peut pas valider son propre bon");
        }
        approvedBy = approverId;
        status = Status.APPROVED;
    }

    /** Consomme une partie du solde ; refuse tout dépassement (CAR-01). */
    public void consume(BigDecimal liters, BigDecimal amount, LocalDate fillDate) {
        require(status == Status.APPROVED || status == Status.PARTIALLY_CONSUMED,
                "Bon " + number + " non utilisable (statut " + status + ")");
        if (fillDate.isAfter(validUntil)) {
            throw new BusinessRuleViolation("CAR-01", "Bon " + number + " expiré le " + validUntil);
        }
        if (deliveredLiters.add(liters).compareTo(authorizedLiters) > 0
                || deliveredAmount.add(amount).compareTo(authorizedAmount) > 0) {
            throw new BusinessRuleViolation("CAR-01", "Solde du bon " + number + " dépassé : restant "
                    + remainingLiters().toPlainString() + " L / " + remainingAmount().toPlainString() + " XAF");
        }
        deliveredLiters = deliveredLiters.add(liters);
        deliveredAmount = deliveredAmount.add(amount);
        status = deliveredLiters.compareTo(authorizedLiters) == 0 || deliveredAmount.compareTo(authorizedAmount) == 0
                ? Status.SETTLED : Status.PARTIALLY_CONSUMED;
    }

    public void cancel() {
        require(status == Status.DRAFT || status == Status.SUBMITTED || status == Status.APPROVED,
                "Un bon entamé ne s'annule pas ; il se solde ou expire");
        status = Status.CANCELLED;
    }

    public BigDecimal remainingLiters() { return authorizedLiters.subtract(deliveredLiters); }
    public BigDecimal remainingAmount() { return authorizedAmount.subtract(deliveredAmount); }
    public Status status() { return status; }
    public String approvedBy() { return approvedBy; }
    public BigDecimal deliveredLiters() { return deliveredLiters; }

    private static void require(boolean cond, String message) {
        if (!cond) throw new BusinessRuleViolation("RG-02", message);
    }
}
