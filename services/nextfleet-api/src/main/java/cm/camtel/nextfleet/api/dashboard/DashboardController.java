package cm.camtel.nextfleet.api.dashboard;

import cm.camtel.nextfleet.api.dues.DueService;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.vehicles.VehicleService;
import cm.camtel.nextfleet.domain.fuel.ConsumptionResult;
import cm.camtel.nextfleet.domain.fuel.FuelFillRecord;
import cm.camtel.nextfleet.domain.fuel.FullToFullConsumptionCalculator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NF-20 : indicateurs du tableau de bord, calculés à partir des données réelles du périmètre de l'utilisateur.
 * Chaque valeur porte sa période ; une consommation n'est affichée que si le calcul plein-à-plein le permet (RG-07).
 */
@RestController
public class DashboardController {

    public record MonthlyConsumption(String month, BigDecimal litersPer100Km, String reliability) { }

    public record Alert(String kind, String title, String detail, String due, String severity) { }

    public record Dashboard(int activeVehicles, int totalVehicles, int upcomingMaintenance, int maintenanceDelta,
                            BigDecimal avgConsumption, BigDecimal consumptionTrendPercent, String consumptionNote,
                            BigDecimal monthCostXaf, BigDecimal costTrendPercent, String period,
                            List<MonthlyConsumption> consumptionSeries, List<Alert> alerts,
                            List<VehicleService.VehicleView> recentVehicles, Instant refreshedAt,
                            int openMaintenanceOrders, int activeMissions, int fuelOrdersToApprove, int openAnomalies) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final DueService dues;
    private final VehicleService vehicles;
    private final FullToFullConsumptionCalculator calculator = new FullToFullConsumptionCalculator();

    public DashboardController(NamedParameterJdbcTemplate jdbc, AccessService access, DueService dues, VehicleService vehicles) {
        this.jdbc = jdbc;
        this.access = access;
        this.dues = dues;
        this.vehicles = vehicles;
    }

    @GetMapping("/api/v1/dashboard")
    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        List<UUID> scope = access.scopeFor("vehicle:read");
        LocalDate today = dues.today();
        YearMonth month = YearMonth.from(today);
        if (scope.isEmpty()) {
            return new Dashboard(0, 0, 0, 0, null, null, "Aucun véhicule dans votre périmètre.", null, null, month.toString(),
                    List.of(), List.of(), List.of(), Instant.now(), 0, 0, 0, 0);
        }
        MapSqlParameterSource p = new MapSqlParameterSource("s", scope);
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status IN ('AVAILABLE','IN_SERVICE','ON_MISSION')) AS active,
                       count(*) AS total
                  FROM vehicle WHERE org_unit_id IN (:s) AND archived_at IS NULL""", p);
        int active = ((Number) counts.get("active")).intValue();
        int total = ((Number) counts.get("total")).intValue();

        int openOrders = jdbc.queryForObject("""
                SELECT count(*) FROM maintenance_order mo JOIN vehicle v ON v.id = mo.vehicle_id
                 WHERE v.org_unit_id IN (:s) AND mo.status NOT IN ('CLOTUREE','ANNULEE')""", p, Integer.class);
        Timestamp startThis = ts(month.atDay(1));
        Timestamp startPrev = ts(month.minusMonths(1).atDay(1));
        Timestamp startNext = ts(month.plusMonths(1).atDay(1));
        MapSqlParameterSource pm = new MapSqlParameterSource("s", scope).addValue("a", startPrev).addValue("b", startThis).addValue("c", startNext);
        Map<String, Object> mo = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE mo.created_at >= :b AND mo.created_at < :c) AS this_month,
                       count(*) FILTER (WHERE mo.created_at >= :a AND mo.created_at < :b) AS prev_month
                  FROM maintenance_order mo JOIN vehicle v ON v.id = mo.vehicle_id WHERE v.org_unit_id IN (:s)""", pm);
        int delta = ((Number) mo.get("this_month")).intValue() - ((Number) mo.get("prev_month")).intValue();

        List<DueService.DueView> soon = dues.upcoming(DueService.SOON_DAYS);
        int upcoming = openOrders + (int) soon.stream().filter(d -> d.vehicle()).count();

        // coûts du mois et du mois précédent (carburant + interventions clôturées + vidanges hors ordre)
        BigDecimal costThis = null;
        BigDecimal costPrev = null;
        if (!access.scopeFor("cost:read").isEmpty() || !access.scopeFor("vehicle:write").isEmpty()) {
            costThis = cost(scope, startThis, startNext);
            costPrev = cost(scope, startPrev, startThis);
        }

        // consommation plein-à-plein par mois (6 derniers mois), affectée au mois du plein de clôture
        Map<UUID, List<FuelFillRecord>> fills = new HashMap<>();
        jdbc.query("""
                SELECT f.vehicle_id, f.id, f.filled_at, f.odometer_km, f.liters, f.full_tank FROM fuel_fill f JOIN vehicle v ON v.id = f.vehicle_id
                 WHERE v.org_unit_id IN (:s) AND f.filled_at >= :from ORDER BY f.vehicle_id, f.filled_at""",
                new MapSqlParameterSource("s", scope).addValue("from", ts(month.minusMonths(7).atDay(1))), rs -> {
                    fills.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new FuelFillRecord(rs.getString(2),
                            rs.getTimestamp(3).toInstant(), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBoolean(6)));
                });
        Map<YearMonth, BigDecimal[]> byMonth = new LinkedHashMap<>();
        for (int i = 5; i >= 0; i--) byMonth.put(month.minusMonths(i), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        Map<String, Instant> fillTime = new HashMap<>();
        fills.values().forEach(l -> l.forEach(f -> fillTime.put(f.id(), f.filledAt())));
        for (List<FuelFillRecord> l : fills.values()) {
            for (ConsumptionResult r : calculator.allPeriods(l)) {
                if (!r.isCalculated()) continue;
                YearMonth ym = YearMonth.from(fillTime.get(r.endFillId()).atZone(DueService.DOUALA));
                BigDecimal[] acc = byMonth.get(ym);
                if (acc == null) continue;
                acc[0] = acc[0].add(r.litersCounted());
                acc[1] = acc[1].add(r.distanceKm());
                acc[2] = acc[2].add(BigDecimal.ONE);
            }
        }
        List<MonthlyConsumption> series = new ArrayList<>();
        byMonth.forEach((ym, acc) -> {
            BigDecimal v = acc[1].signum() > 0 ? acc[0].multiply(BigDecimal.valueOf(100)).divide(acc[1], 1, RoundingMode.HALF_UP) : null;
            int n = acc[2].intValue();
            series.add(new MonthlyConsumption(ym.toString(), v, n == 0 ? "INSUFFISANTE" : n < 3 ? "PARTIELLE" : "FIABLE"));
        });
        MonthlyConsumption cur = series.get(series.size() - 1);
        MonthlyConsumption prev = series.get(series.size() - 2);
        BigDecimal avg = cur.litersPer100Km() != null ? cur.litersPer100Km() : prev.litersPer100Km();
        String note = cur.litersPer100Km() != null ? "Plein à plein, " + month + " (" + reliability(cur.reliability()) + ")"
                : prev.litersPer100Km() != null ? "Plein à plein, mois précédent : pas encore de période complète ce mois-ci"
                : "Pas assez de pleins complets pour calculer une consommation";
        BigDecimal trend = cur.litersPer100Km() != null && prev.litersPer100Km() != null ? percent(cur.litersPer100Km(), prev.litersPer100Km()) : null;

        List<Alert> alerts = new ArrayList<>();
        for (DueService.DueView d : soon.stream().limit(8).toList()) {
            alerts.add(new Alert(alertKind(d.kind()), label(d.kind()) + " · " + d.subject(),
                    d.daysLeft() < 0 ? "Expirée depuis " + (-d.daysLeft()) + " j" : "Dans " + d.daysLeft() + " j",
                    d.dueOn().toString(), d.daysLeft() < 0 ? "danger" : d.daysLeft() <= 7 ? "warning" : "info"));
        }
        jdbc.query("""
                SELECT v.registration, mo.title, mo.status, mo.created_at FROM maintenance_order mo JOIN vehicle v ON v.id = mo.vehicle_id
                 WHERE v.org_unit_id IN (:s) AND mo.status IN ('DEMANDE','EN_COURS') ORDER BY mo.created_at DESC LIMIT 4""", p, rs -> {
            alerts.add(new Alert("MAINTENANCE", rs.getString(1) + " · " + rs.getString(2),
                    "EN_COURS".equals(rs.getString(3)) ? "Intervention en cours" : "Demande à qualifier",
                    rs.getTimestamp(4).toInstant().atZone(DueService.DOUALA).toLocalDate().toString(),
                    "EN_COURS".equals(rs.getString(3)) ? "info" : "warning"));
        });

        int activeMissions = jdbc.queryForObject("SELECT count(*) FROM mission WHERE org_unit_id IN (:s) AND status = 'ACTIVE'", p, Integer.class);
        List<UUID> appr = access.scopeFor("fuel_order:approve");
        int toApprove = appr.isEmpty() ? 0 : jdbc.queryForObject("SELECT count(*) FROM fuel_order WHERE org_unit_id IN (:s) AND status = 'SUBMITTED'",
                new MapSqlParameterSource("s", appr), Integer.class);
        List<UUID> an = access.scopeFor("anomaly:read");
        int anomalies = an.isEmpty() ? 0 : jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE org_unit_id IN (:s) AND status IN ('A_VERIFIER','EN_ANALYSE')",
                new MapSqlParameterSource("s", an), Integer.class);

        List<VehicleService.VehicleView> recent = vehicles.search(null, null, false, 0, 6).items();
        return new Dashboard(active, total, upcoming, delta, avg, trend, note, costThis,
                costThis != null && costPrev != null && costPrev.signum() > 0 ? percent(costThis, costPrev) : null, month.toString(),
                series, alerts, recent, Instant.now(), openOrders, activeMissions, toApprove, anomalies);
    }

    private BigDecimal cost(List<UUID> scope, Timestamp from, Timestamp to) {
        MapSqlParameterSource p = new MapSqlParameterSource("s", scope).addValue("f", from).addValue("t", to);
        return jdbc.queryForObject("""
                SELECT coalesce((SELECT sum(f.amount) FROM fuel_fill f JOIN vehicle v ON v.id = f.vehicle_id
                                  WHERE v.org_unit_id IN (:s) AND f.filled_at >= :f AND f.filled_at < :t), 0)
                     + coalesce((SELECT sum(mo.cost_xaf) FROM maintenance_order mo JOIN vehicle v ON v.id = mo.vehicle_id
                                  WHERE v.org_unit_id IN (:s) AND mo.status = 'CLOTUREE'
                                    AND coalesce(mo.immobilised_to, mo.created_at) >= :f AND coalesce(mo.immobilised_to, mo.created_at) < :t), 0)
                     + coalesce((SELECT sum(oc.cost_xaf) FROM oil_change oc JOIN vehicle v ON v.id = oc.vehicle_id
                                  WHERE v.org_unit_id IN (:s) AND oc.maintenance_order_id IS NULL
                                    AND oc.done_on >= CAST(:f AS timestamptz)::date AND oc.done_on < CAST(:t AS timestamptz)::date), 0)""",
                p, BigDecimal.class);
    }

    private static Timestamp ts(LocalDate d) {
        return Timestamp.from(d.atStartOfDay(DueService.DOUALA).toInstant());
    }

    private static BigDecimal percent(BigDecimal now, BigDecimal before) {
        return now.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before, 1, RoundingMode.HALF_UP);
    }

    private static String reliability(String r) {
        return switch (r) {
            case "FIABLE" -> "fiable";
            case "PARTIELLE" -> "données partielles";
            default -> "données insuffisantes";
        };
    }

    private static String alertKind(String dueKind) {
        return switch (dueKind) {
            case "ASSURANCE", "VIGNETTE", "CONTROLE_TECHNIQUE", "CARTE_GRISE" -> "INSURANCE";
            case "PERMIS", "CNI" -> "LICENSE";
            default -> "MAINTENANCE";
        };
    }

    private static String label(String kind) {
        return switch (kind) {
            case "ASSURANCE" -> "Assurance";
            case "VIGNETTE" -> "Vignette";
            case "CONTROLE_TECHNIQUE" -> "Contrôle technique";
            case "CARTE_GRISE" -> "Carte grise";
            case "PERMIS" -> "Permis";
            case "CNI" -> "CNI";
            case "VIDANGE" -> "Vidange";
            default -> kind;
        };
    }
}
