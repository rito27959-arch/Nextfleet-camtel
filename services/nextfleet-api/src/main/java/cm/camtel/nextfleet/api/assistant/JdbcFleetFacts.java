package cm.camtel.nextfleet.api.assistant;

import cm.camtel.nextfleet.domain.assistant.FleetFacts;
import cm.camtel.nextfleet.domain.fuel.ConsumptionResult;
import cm.camtel.nextfleet.domain.fuel.FuelFillRecord;
import cm.camtel.nextfleet.domain.fuel.FullToFullConsumptionCalculator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Faits de l'assistant lus en base, en LECTURE SEULE et restreints au périmètre de l'utilisateur
 * (listes d'entités calculées par AccessService pour chaque permission). Chaque fait cite sa pièce source.
 */
final class JdbcFleetFacts implements FleetFacts {

    private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    private static final List<UUID> NONE = List.of(new UUID(0, 0));

    private final NamedParameterJdbcTemplate jdbc;
    private final List<UUID> vehicleScope;
    private final List<UUID> costScope;
    private final List<UUID> missionScope;
    private final LocalDate today;

    JdbcFleetFacts(NamedParameterJdbcTemplate jdbc, List<UUID> vehicleScope, List<UUID> costScope, List<UUID> missionScope, LocalDate today) {
        this.jdbc = jdbc;
        this.vehicleScope = vehicleScope.isEmpty() ? NONE : vehicleScope;
        this.costScope = costScope.isEmpty() ? NONE : costScope;
        this.missionScope = missionScope.isEmpty() ? NONE : missionScope;
        this.today = today;
    }

    @Override
    public List<VehicleFact> vehicles() {
        return jdbc.query("""
                SELECT registration, make || ' ' || model, status, id FROM vehicle
                 WHERE archived_at IS NULL AND org_unit_id IN (:s) ORDER BY registration""",
                new MapSqlParameterSource("s", vehicleScope),
                (rs, i) -> new VehicleFact(rs.getString(1), rs.getString(2), rs.getString(3), "vehicle:" + rs.getString(4)));
    }

    @Override
    public List<CostFact> costs(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource("s", costScope)
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to.plusDays(1)));
        return jdbc.query("""
                SELECT v.registration, (f.filled_at AT TIME ZONE 'Africa/Douala')::date, 'CARBURANT', f.amount, 'fuel_fill:' || f.id
                  FROM fuel_fill f JOIN vehicle v ON v.id = f.vehicle_id
                 WHERE v.org_unit_id IN (:s) AND f.filled_at >= CAST(:from AS date) AND f.filled_at < CAST(:to AS date)
                UNION ALL
                SELECT v.registration, (coalesce(o.immobilised_to, o.created_at) AT TIME ZONE 'Africa/Douala')::date, 'ENTRETIEN', o.cost_xaf,
                       'maintenance_order:' || o.id
                  FROM maintenance_order o JOIN vehicle v ON v.id = o.vehicle_id
                 WHERE v.org_unit_id IN (:s) AND o.status = 'CLOTUREE' AND o.cost_xaf IS NOT NULL
                   AND coalesce(o.immobilised_to, o.created_at) >= CAST(:from AS date) AND coalesce(o.immobilised_to, o.created_at) < CAST(:to AS date)
                 ORDER BY 2""", p,
                (rs, i) -> new CostFact(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getBigDecimal(4), rs.getString(5)));
    }

    @Override
    public List<DueFact> dueItems() {
        return jdbc.query("""
                SELECT due_kind, subject_label, due_on, days_left, level, subject_kind || ':' || subject_id
                  FROM nf_due_dates(:today, 30) WHERE org_unit_id IN (:s) ORDER BY days_left""",
                new MapSqlParameterSource("s", vehicleScope).addValue("today", Date.valueOf(today)),
                (rs, i) -> new DueFact(rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getLong(4), rs.getString(5),
                        rs.getString(6)));
    }

    @Override
    public List<MissionFact> missions() {
        return jdbc.query("""
                SELECT m.reference, v.registration, d.full_name, m.status, 'mission:' || m.id
                  FROM mission m JOIN vehicle v ON v.id = m.vehicle_id JOIN driver d ON d.id = m.driver_id
                 WHERE m.org_unit_id IN (:s) ORDER BY m.planned_start DESC LIMIT 200""",
                new MapSqlParameterSource("s", missionScope),
                (rs, i) -> new MissionFact(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    /** Plein-à-plein (NF-23) : périodes dont le plein de clôture tombe dans le mois ; rien n'est inventé. */
    @Override
    public List<ConsumptionFact> consumption(YearMonth month) {
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(DOUALA).toInstant();
        Instant start = month.atDay(1).atStartOfDay(DOUALA).toInstant();
        record Row(String plate, FuelFillRecord fill) { }
        List<Row> rows = jdbc.query("""
                SELECT v.registration, f.id, f.filled_at, f.odometer_km, f.liters, f.full_tank
                  FROM fuel_fill f JOIN vehicle v ON v.id = f.vehicle_id
                 WHERE v.org_unit_id IN (:s) AND f.filled_at < :end AND f.filled_at >= :since ORDER BY v.registration, f.filled_at""",
                new MapSqlParameterSource("s", costScope).addValue("end", java.sql.Timestamp.from(end))
                        .addValue("since", java.sql.Timestamp.from(start.minusSeconds(120L * 86400))),
                (rs, i) -> new Row(rs.getString(1), new FuelFillRecord(rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBoolean(6))));
        Map<String, List<FuelFillRecord>> byPlate = new LinkedHashMap<>();
        Map<String, Instant> at = new HashMap<>();
        rows.forEach(r -> {
            byPlate.computeIfAbsent(r.plate(), k -> new ArrayList<>()).add(r.fill());
            at.put(r.fill().id(), r.fill().filledAt());
        });
        FullToFullConsumptionCalculator calc = new FullToFullConsumptionCalculator();
        List<ConsumptionFact> out = new ArrayList<>();
        byPlate.forEach((plate, fills) -> {
            ConsumptionResult last = null;
            for (ConsumptionResult c : calc.allPeriods(fills)) {
                Instant closing = at.get(c.endFillId());
                if (c.isCalculated() && closing != null && !closing.isBefore(start)) last = c;
            }
            out.add(new ConsumptionFact(plate, month, last == null ? null : last.litersPer100Km(),
                    last == null ? "fuel_fill:aucune-periode" : "fuel_fill:" + last.endFillId()));
        });
        return out;
    }
}
