package cm.camtel.nextfleet.api.vehicles;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.StaleVersionException;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.vehicles.VehicleController.Page;
import cm.camtel.nextfleet.api.vehicles.VehicleController.VehicleCreate;
import cm.camtel.nextfleet.api.vehicles.VehicleController.VehiclePatch;
import cm.camtel.nextfleet.domain.fleet.RegistrationNumber;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class VehicleService {

    public record VehicleView(UUID id, String registration, String registrationNormalized, String vin,
                              String make, String model, Integer modelYear, String vehicleType, String energy,
                              BigDecimal tankCapacityLiters, boolean tankCapacityVerified, String status,
                              UUID orgUnitId, String modelAssetStatus, Instant archivedAt, long version) { }

    private static final String COLUMNS = """
            id, registration, registration_normalized, vin, make, model, model_year, vehicle_type, energy,
            tank_capacity_l, tank_capacity_verified, status, org_unit_id, model_asset_status, archived_at, version""";

    private static final RowMapper<VehicleView> MAPPER = (rs, i) -> new VehicleView(
            rs.getObject("id", UUID.class), rs.getString("registration"), rs.getString("registration_normalized"),
            rs.getString("vin"), rs.getString("make"), rs.getString("model"),
            rs.getObject("model_year", Integer.class), rs.getString("vehicle_type"), rs.getString("energy"),
            rs.getBigDecimal("tank_capacity_l"), rs.getBoolean("tank_capacity_verified"), rs.getString("status"),
            rs.getObject("org_unit_id", UUID.class), rs.getString("model_asset_status"),
            rs.getTimestamp("archived_at") == null ? null : rs.getTimestamp("archived_at").toInstant(),
            rs.getLong("version"));

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;

    public VehicleService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    /** Liste filtrée au périmètre de l'utilisateur : un véhicule hors périmètre n'apparaît jamais. */
    @Transactional(readOnly = true)
    public Page<VehicleView> search(String q, String status, boolean includeArchived, int page, int size) {
        List<UUID> scope = access.scopeFor("vehicle:read");
        if (scope.isEmpty()) return new Page<>(List.of(), page, size, 0);
        String term = q == null || q.isBlank() ? null : q.strip();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("scope", scope).addValue("status", status)
                .addValue("q", term == null ? null : "%" + term.toLowerCase() + "%")
                .addValue("qn", term == null ? null : "%" + term.replaceAll("[^A-Za-z0-9]", "").toUpperCase() + "%")
                .addValue("archived", includeArchived)
                .addValue("limit", size).addValue("offset", (long) page * size);
        String where = """
                 WHERE org_unit_id IN (:scope)
                   AND (CAST(:status AS text) IS NULL OR status = :status)
                   AND (:archived OR archived_at IS NULL)
                   AND (CAST(:q AS text) IS NULL OR registration_normalized LIKE :qn
                        OR lower(make) LIKE :q OR lower(model) LIKE :q)""";
        long total = jdbc.queryForObject("SELECT count(*) FROM vehicle" + where, p, Long.class);
        List<VehicleView> items = jdbc.query("SELECT " + COLUMNS + " FROM vehicle" + where
                + " ORDER BY registration_normalized LIMIT :limit OFFSET :offset", p, MAPPER);
        return new Page<>(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public VehicleView get(UUID id) {
        VehicleView v = find(id);
        access.require("vehicle:read", v.orgUnitId(), "vehicle", id.toString());
        return v;
    }

    @Transactional
    public VehicleView create(VehicleCreate c) {
        access.require("vehicle:write", c.orgUnitId(), "vehicle", "new");
        RegistrationNumber reg = RegistrationNumber.of(c.registration());   // validation et normalisation
        UUID id = jdbc.queryForObject("""
                INSERT INTO vehicle(registration, vin, make, model, model_year, vehicle_type, energy,
                                    tank_capacity_l, tank_capacity_verified, org_unit_id)
                VALUES (:reg, :vin, :make, :model, :year, :type, :energy, :tank, :tankv, :org) RETURNING id""",
                new MapSqlParameterSource().addValue("reg", reg.raw()).addValue("vin", blankToNull(c.vin()))
                        .addValue("make", c.make().strip()).addValue("model", c.model().strip())
                        .addValue("year", c.modelYear()).addValue("type", c.vehicleType()).addValue("energy", c.energy())
                        .addValue("tank", c.tankCapacityLiters()).addValue("tankv", c.tankCapacityVerified())
                        .addValue("org", c.orgUnitId()), UUID.class);
        VehicleView created = find(id);
        audit.record(access.currentUser().userId(), "CREATE", "vehicle", id.toString(), null, null, created);
        return created;
    }

    @Transactional
    public VehicleView patch(UUID id, long expectedVersion, VehiclePatch p) {
        VehicleView before = find(id);
        access.require("vehicle:write", before.orgUnitId(), "vehicle", id.toString());
        if (before.archivedAt() != null) throw new NotFoundException("Véhicule archivé : correction impossible.");
        int n = jdbc.update("""
                UPDATE vehicle SET make = COALESCE(:make, make), model = COALESCE(:model, model),
                       model_year = COALESCE(:year, model_year), status = COALESCE(:status, status),
                       tank_capacity_l = COALESCE(:tank, tank_capacity_l),
                       tank_capacity_verified = COALESCE(:tankv, tank_capacity_verified),
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :v""",
                new MapSqlParameterSource().addValue("make", p.make()).addValue("model", p.model())
                        .addValue("year", p.modelYear()).addValue("status", p.status())
                        .addValue("tank", p.tankCapacityLiters()).addValue("tankv", p.tankCapacityVerified())
                        .addValue("id", id).addValue("v", expectedVersion));
        if (n == 0) throw new StaleVersionException("version " + expectedVersion);
        VehicleView after = find(id);
        audit.record(access.currentUser().userId(), "UPDATE", "vehicle", id.toString(), p.reason(), before, after);
        return after;
    }

    /** RG-04 : archivage métier ; l'immatriculation reste réservée et l'historique consultable. */
    @Transactional
    public VehicleView archive(UUID id, long expectedVersion, String reason) {
        VehicleView before = find(id);
        access.require("vehicle:archive", before.orgUnitId(), "vehicle", id.toString());
        int n = jdbc.update("""
                UPDATE vehicle SET status = 'ARCHIVED', archived_at = now(), version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :v AND archived_at IS NULL""",
                new MapSqlParameterSource().addValue("id", id).addValue("v", expectedVersion));
        if (n == 0) throw new StaleVersionException("version " + expectedVersion);
        VehicleView after = find(id);
        audit.record(access.currentUser().userId(), "ARCHIVE", "vehicle", id.toString(), reason, before, after);
        return after;
    }

    private VehicleView find(UUID id) {
        List<VehicleView> l = jdbc.query("SELECT " + COLUMNS + " FROM vehicle WHERE id = :id",
                new MapSqlParameterSource("id", id), MAPPER);
        if (l.isEmpty()) throw new NotFoundException("Véhicule introuvable.");
        return l.get(0);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
