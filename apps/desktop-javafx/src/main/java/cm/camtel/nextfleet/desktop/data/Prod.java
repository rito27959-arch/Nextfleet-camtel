package cm.camtel.nextfleet.desktop.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Objets du passage en production : entités, comptes, pièces justificatives, atelier, carburant, coûts, rapports et
 * messagerie enrichie. Miroir des réponses de l'API (mêmes noms de champs).
 */
public final class Prod {

    private Prod() { }

    public record OrgUnit(UUID id, String code, String name, UUID parentId, String parentName) {
        @Override
        public String toString() {
            return parentName == null ? name : name + " — " + parentName;
        }
    }

    // ---------------------------------------------------------------- comptes

    public record RoleInfo(String code, String label, List<String> permissions) {
        @Override
        public String toString() {
            return label;
        }
    }

    public record RoleGrant(UUID id, String role, String roleLabel, UUID scopeOrgId, String scopeName, Instant validFrom,
                            Instant validUntil, boolean delegation, Instant revokedAt, String grantedBy) {
        public boolean active() {
            return revokedAt == null && (validUntil == null || validUntil.isAfter(Instant.now()));
        }
    }

    public record AccountDraft(String login, String displayName, String email, String phone, UUID orgUnitId, String role,
                               String driverStaffNumber) { }

    /** Le mot de passe provisoire n'est connu qu'ici, une seule fois. */
    public record CreatedAccount(String userId, String login, String temporaryPassword) { }

    // ---------------------------------------------------------------- fichiers

    public record StoredFile(UUID id, String purpose, String originalName, String mimeType, long sizeBytes, String sha256) { }

    public record Attachment(UUID id, String name, String mimeType, long sizeBytes) { }

    // ---------------------------------------------------------------- véhicules et conducteurs

    public record VehicleDocument(UUID id, String docType, String reference, LocalDate validFrom, LocalDate validUntil,
                                  UUID fileId, String fileName) { }

    public record DriverDraft(String staffNumber, String fullName, String phone, String email, UUID orgUnitId,
                              String idCardNumber, LocalDate idCardExpiresOn, String licenseNumber, List<String> licenseCategories,
                              LocalDate licenseExpiresOn) { }

    public record MissionDraft(UUID vehicleId, UUID driverId, String purpose, String origin, String destination,
                               Instant plannedStart, Instant plannedEnd) { }

    /** hasAccount : la fiche est liée à un compte (seul un conducteur avec compte peut démarrer une mission) ; null = inconnu. */
    public record DriverRef(UUID id, String fullName, String staffNumber, Boolean hasAccount) {
        public DriverRef(UUID id, String fullName, String staffNumber) {
            this(id, fullName, staffNumber, null);
        }

        @Override
        public String toString() {
            return fullName + " · " + staffNumber + (Boolean.FALSE.equals(hasAccount) ? " · sans compte" : "");
        }
    }

    /** Fiche conducteur rattachée à un compte. */
    public record DriverLink(UUID driverId, String staffNumber, String fullName, boolean created) { }

    // ---------------------------------------------------------------- atelier

    public record Category(String code, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public enum WorkStatus {
        DEMANDE("Demandée", "warning"), QUALIFIEE("Qualifiée", "info"), PLANIFIEE("Planifiée", "info"),
        EN_COURS("En cours", "danger"), CLOTUREE("Clôturée", "ok"), ANNULEE("Annulée", "neutral");

        public final String label;
        public final String tone;

        WorkStatus(String label, String tone) {
            this.label = label;
            this.tone = tone;
        }

        /** Mêmes transitions que le serveur (MaintenanceService.NEXT). */
        public List<WorkStatus> next() {
            return switch (this) {
                case DEMANDE -> List.of(QUALIFIEE, ANNULEE);
                case QUALIFIEE -> List.of(PLANIFIEE, EN_COURS, ANNULEE);
                case PLANIFIEE -> List.of(EN_COURS, ANNULEE);
                case EN_COURS -> List.of(CLOTUREE, ANNULEE);
                default -> List.of();
            };
        }

        public boolean open() {
            return this != CLOTUREE && this != ANNULEE;
        }
    }

    public record WorkOrder(UUID id, UUID vehicleId, String registration, String vehicleLabel, String categoryCode,
                            String categoryLabel, String title, WorkStatus status, String garageLabel, Instant immobilisedFrom,
                            Instant immobilisedTo, BigDecimal odometerKm, BigDecimal costXaf, String workDone, UUID proofFileId,
                            String technicalVisaBy, String requestedBy, Instant createdAt, long version) { }

    public record WorkTransition(WorkStatus status, String garageLabel, BigDecimal odometerKm, BigDecimal costXaf, String workDone,
                                 UUID proofFileId, String reason) { }

    public record OilChange(UUID id, UUID vehicleId, String registration, LocalDate doneOn, BigDecimal odometerKm, String oilLabel,
                            List<String> filters, Integer intervalKm, Integer intervalMonths, BigDecimal nextDueKm,
                            LocalDate nextDueOn, BigDecimal costXaf, String garageLabel, String author) { }

    public record OilChangeDraft(UUID vehicleId, LocalDate doneOn, BigDecimal odometerKm, String oilLabel, BigDecimal oilLiters,
                                 boolean oilFilter, boolean airFilter, boolean fuelFilter, Integer intervalKm, Integer intervalMonths,
                                 BigDecimal costXaf, String garageLabel) { }

    // ---------------------------------------------------------------- carburant

    public record Supplier(UUID id, String code, String name, String kind) {
        @Override
        public String toString() {
            return name;
        }
    }

    public enum FuelOrderStatus {
        DRAFT("Brouillon", "neutral"), SUBMITTED("À valider", "warning"), APPROVED("Validé", "info"),
        PARTIALLY_CONSUMED("Entamé", "info"), SETTLED("Soldé", "ok"), CANCELLED("Annulé", "neutral"), EXPIRED("Expiré", "neutral");

        public final String label;
        public final String tone;

        FuelOrderStatus(String label, String tone) {
            this.label = label;
            this.tone = tone;
        }
    }

    public record FuelOrder(UUID id, String orderNumber, UUID vehicleId, String registration, String supplierName,
                            String requestedBy, String approvedBy, BigDecimal authorizedLiters, BigDecimal authorizedAmountXaf,
                            BigDecimal deliveredLiters, BigDecimal deliveredAmountXaf, LocalDate validUntil, FuelOrderStatus status,
                            String reason, Instant createdAt, boolean mine, long version) {
        @Override
        public String toString() {
            return orderNumber + " · " + registration + " · reste " + authorizedLiters.subtract(deliveredLiters).stripTrailingZeros().toPlainString() + " L";
        }
    }

    public record FuelOrderDraft(UUID vehicleId, UUID supplierId, BigDecimal liters, BigDecimal amountXaf, LocalDate validUntil,
                                 String reason) { }

    public record FuelFill(UUID id, String orderNumber, UUID vehicleId, String registration, String driverName, String supplierName,
                           Instant filledAt, BigDecimal odometerKm, BigDecimal liters, BigDecimal unitPriceXaf, BigDecimal amountXaf,
                           boolean fullTank, String receiptReference, UUID receiptFileId, String exceptionReason, String enteredBy) { }

    public record FillDraft(UUID fuelOrderId, String exceptionReason, UUID vehicleId, UUID supplierId, Instant filledAt,
                            BigDecimal odometerKm, BigDecimal liters, BigDecimal unitPriceXaf, boolean fullTank,
                            String receiptReference, UUID receiptFileId) { }

    // ---------------------------------------------------------------- coûts et rapports

    public record CostMonth(int month, BigDecimal fuelXaf, BigDecimal maintenanceXaf, BigDecimal oilXaf, BigDecimal totalXaf,
                            BigDecimal liters) { }

    public record CostCategory(String code, String label, BigDecimal actualXaf, BigDecimal budgetXaf, BigDecimal consumedPercent) { }

    public record CostVehicle(UUID vehicleId, String registration, String label, BigDecimal fuelXaf, BigDecimal maintenanceXaf,
                              BigDecimal totalXaf, BigDecimal liters, int interventions) { }

    public record CostSummary(int year, BigDecimal totalXaf, BigDecimal fuelXaf, BigDecimal maintenanceXaf, BigDecimal oilXaf,
                              BigDecimal budgetXaf, BigDecimal liters, List<CostMonth> months, List<CostCategory> categories,
                              List<CostVehicle> vehicles) { }

    public record ImportResult(int read, int inserted, int duplicates, List<String> errors) { }

    public record ReportInfo(String code, String title, String description, boolean period) { }

    // ---------------------------------------------------------------- messagerie

    public record Contact(String userId, String displayName, String role, String orgUnit) {
        @Override
        public String toString() {
            return displayName + " · " + role;
        }
    }
}
