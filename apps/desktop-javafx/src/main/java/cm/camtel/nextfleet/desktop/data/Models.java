package cm.camtel.nextfleet.desktop.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Objets échangés entre l'interface et la passerelle de données (miroir du contrat OpenAPI lot 1). */
public final class Models {

    private Models() { }

    public record Session(String token, String displayName, String login, List<String> roles, boolean demo) {
        public boolean has(String role) {
            return roles.contains(role);
        }

        /** Permissions effectives : union des rôles (matrice du noyau, identique à la base). L'API revérifie tout. */
        public java.util.Set<cm.camtel.nextfleet.domain.security.Permission> permissions() {
            return cm.camtel.nextfleet.domain.security.Role.union(roles);
        }

        public boolean can(cm.camtel.nextfleet.domain.security.Permission p) {
            return permissions().contains(p);
        }

        public cm.camtel.nextfleet.domain.security.Role primaryRole() {
            return roles.isEmpty() ? null : cm.camtel.nextfleet.domain.security.Role.ofCode(roles.get(0)).orElse(null);
        }
    }

    public enum VehicleStatus {
        AVAILABLE("Disponible", "ok"), IN_SERVICE("En service", "ok"), ON_MISSION("En déplacement", "info"),
        MAINTENANCE("Entretien requis", "danger"), OUT_OF_SERVICE("Hors service", "neutral"), ARCHIVED("Archivé", "neutral");

        public final String label;
        public final String tone;

        VehicleStatus(String label, String tone) {
            this.label = label;
            this.tone = tone;
        }
    }

    /**
     * @param fuelLevelPercent niveau déclaré ; {@code null} = non renseigné (RG-07), jamais 0 par défaut
     * @param lastKnownPlace   position DÉCLARÉE (NF-19) ; pas un suivi en direct
     */
    public record Vehicle(UUID id, String registration, String make, String model, Integer modelYear, String energy,
                          VehicleStatus status, String driverName, Integer fuelLevelPercent, String lastKnownPlace,
                          BigDecimal odometerKm, BigDecimal tankCapacityLiters, LocalDate nextMaintenance,
                          String category, long version) {
        public String label() {
            return make + " " + model;
        }
    }

    public enum AlertKind { MAINTENANCE, FUEL_LOW, INSURANCE, LICENSE, ANOMALY }

    public record Alert(AlertKind kind, String title, String detail, String due, String severity) { }

    public record MonthlyConsumption(YearMonth month, BigDecimal litersPer100Km, String reliability) { }

    /** Indicateurs du tableau de bord ; chaque valeur porte sa période (NF-20). */
    public record DashboardData(int activeVehicles, int totalVehicles, int upcomingMaintenance, int maintenanceDelta,
                                BigDecimal avgConsumption, BigDecimal consumptionTrendPercent, String consumptionNote,
                                BigDecimal monthCostXaf, BigDecimal costTrendPercent, YearMonth period,
                                List<MonthlyConsumption> consumptionSeries, List<Alert> alerts,
                                List<Vehicle> recentVehicles, Instant refreshedAt) { }

    /** Conducteur (NF-12 ; champs repris de l'ancien NexFleet : matricule, permis, CNI). */
    /** userLogin : compte NexFleet lié à la fiche (null = aucun, ou inconnu en démonstration). */
    public record Driver(UUID id, String fullName, String staffNumber, String phone, String licenseNumber,
                         List<String> licenseCategories, LocalDate licenseExpiresOn, LocalDate idCardExpiresOn,
                         String assignedVehicle, String site, String userLogin) {
        public Driver(UUID id, String fullName, String staffNumber, String phone, String licenseNumber,
                      List<String> licenseCategories, LocalDate licenseExpiresOn, LocalDate idCardExpiresOn,
                      String assignedVehicle, String site) {
            this(id, fullName, staffNumber, phone, licenseNumber, licenseCategories, licenseExpiresOn, idCardExpiresOn, assignedVehicle, site, null);
        }

        public String initials() {
            String[] p = fullName.trim().split("\\s+");
            String a = p.length > 0 && !p[0].isEmpty() ? p[0].substring(0, 1) : "";
            String b = p.length > 1 ? p[p.length - 1].substring(0, 1) : "";
            return (a + b).toUpperCase(java.util.Locale.FRANCE);
        }
    }

    public enum DueKind {
        ASSURANCE("Assurance"), VIGNETTE("Vignette"), CONTROLE_TECHNIQUE("Contrôle technique"),
        CARTE_GRISE("Carte grise"), PERMIS("Permis de conduire"), CNI("Carte d'identité"), VIDANGE("Vidange");

        public final String label;

        DueKind(String label) {
            this.label = label;
        }
    }

    public enum DueLevel { EXPIRE, A_ECHEANCE, A_JOUR }

    /** Échéance unifiée (vue v_due_date) : même date et même niveau partout (NF-11). */
    public record DueItem(DueKind kind, String subject, String subjectDetail, boolean vehicle, LocalDate dueOn,
                          long daysLeft, DueLevel level) { }

    public enum NotificationKind { ECHEANCE, ENTRETIEN, CARBURANT, SYSTEME }

    /**
     * Notification (HIG « Notifications ») : titre court en casse de titre sans ponctuation finale, corps en phrase complète,
     * aucune donnée sensible ; {@code targetQuery} ouvre le contenu lié.
     */
    public record NotificationItem(String id, NotificationKind kind, String title, String body, Instant at,
                                   boolean read, String targetSection, String targetQuery) {
        public NotificationItem withRead(boolean r) {
            return new NotificationItem(id, kind, title, body, at, r, targetSection, targetQuery);
        }
    }

    public record Page<T>(List<T> items, int page, int size, long total) { }

    public record VehicleDraft(String registration, String vin, String make, String model, Integer modelYear,
                               String energy, BigDecimal tankCapacityLiters, String category) { }
}
