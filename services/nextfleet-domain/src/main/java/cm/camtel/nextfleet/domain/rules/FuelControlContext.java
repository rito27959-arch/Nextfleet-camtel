package cm.camtel.nextfleet.domain.rules;

import cm.camtel.nextfleet.domain.fuel.FuelFillRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Données d'un véhicule soumises aux contrôles carburant. Construites par l'API à partir de la base
 * pour un périmètre et une période ; les règles ne lisent rien d'autre.
 */
public record FuelControlContext(String vehicleId,
                                 String registration,
                                 BigDecimal tankCapacityLiters,
                                 boolean tankCapacityVerified,
                                 List<FilledReceipt> fills,
                                 List<OdometerReading> odometerReadings,
                                 List<Immobilisation> immobilisations,
                                 List<InvoiceLineMatch> invoiceLines) {

    public FuelControlContext {
        Objects.requireNonNull(vehicleId);
        fills = List.copyOf(fills);
        odometerReadings = List.copyOf(odometerReadings);
        immobilisations = List.copyOf(immobilisations);
        invoiceLines = List.copyOf(invoiceLines);
    }

    /** Plein avec ses références de pièce. */
    public record FilledReceipt(FuelFillRecord fill, String supplierId, String receiptReference) {
        public String normalizedReceipt() {
            return receiptReference == null ? "" : receiptReference.strip().toUpperCase();
        }
    }

    /** Relevé de compteur ; {@code counterEpoch} change lors d'un remplacement de compteur documenté (NF-17). */
    public record OdometerReading(String id, Instant readAt, BigDecimal valueKm, int counterEpoch, String justification) { }

    /** Immobilisation enregistrée (maintenance, réparation) ; {@code workshopFuelAllowed} = exception atelier. */
    public record Immobilisation(String maintenanceOrderId, Instant from, Instant to, boolean workshopFuelAllowed) {
        boolean contains(Instant t) {
            return !t.isBefore(from) && (to == null || !t.isAfter(to));
        }
    }

    /** Ligne de facture rapprochée d'un plein (ou non rapprochée si {@code fillId} est nul). */
    public record InvoiceLineMatch(String invoiceNumber, int lineNo, String fillId,
                                   BigDecimal invoicedLiters, BigDecimal invoicedAmount,
                                   BigDecimal deliveredLiters, BigDecimal deliveredAmount) { }
}
