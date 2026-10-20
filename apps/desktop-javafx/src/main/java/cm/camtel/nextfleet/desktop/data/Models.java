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

    public record Page<T>(List<T> items, int page, int size, long total) { }

    public record VehicleDraft(String registration, String vin, String make, String model, Integer modelYear,
                               String energy, BigDecimal tankCapacityLiters, String category) { }
}
