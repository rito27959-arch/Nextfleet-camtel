package cm.camtel.nextfleet.api.drivers;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.StaleVersionException;
import cm.camtel.nextfleet.api.files.FileService;
import cm.camtel.nextfleet.api.identity.AccessService;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** NF-12 : conducteurs (matricule, permis, CNI), affectation véhicule ↔ conducteur sans chevauchement (contrainte base). */
@Service
public class DriverService {

    public record DriverView(UUID id, String staffNumber, String fullName, String phone, String email, UUID orgUnitId,
                             String orgUnitName, String idCardNumber, LocalDate idCardExpiresOn, String licenseNumber,
                             List<String> licenseCategories, LocalDate licenseExpiresOn, UUID assignedVehicleId,
                             String assignedVehicle, String userLogin, Instant archivedAt, long version) { }

    public record DriverInput(String staffNumber, String fullName, String phone, String email, UUID orgUnitId,
                              String idCardNumber, LocalDate idCardExpiresOn) { }

    public record LicenseInput(String licenseNumber, List<String> categories, LocalDate issuedOn, LocalDate expiresOn, UUID fileId) { }

    private static final String SELECT = """
            SELECT d.id, d.staff_number, d.full_name, d.phone, d.email, d.org_unit_id, o.name AS org_name, d.id_card_number,
                   d.id_card_expires_on, l.license_number, l.categories, l.expires_on, av.vehicle_id, av.registration,
                   u.login, d.archived_at, d.version
              FROM driver d
              JOIN organisation_unit o ON o.id = d.org_unit_id
              LEFT JOIN app_user u ON u.id = d.user_id
              LEFT JOIN LATERAL (SELECT license_number, categories, expires_on FROM driver_license
                                  WHERE driver_id = d.id ORDER BY expires_on DESC LIMIT 1) l ON true
              LEFT JOIN LATERAL (SELECT a.vehicle_id, v.registration FROM vehicle_assignment a JOIN vehicle v ON v.id = a.vehicle_id
                                  WHERE a.driver_id = d.id AND a.period @> now() LIMIT 1) av ON true
            """;

    private static final RowMapper<DriverView> MAPPER = (rs, i) -> new DriverView(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8),
            rs.getObject(9, LocalDate.class), rs.getString(10),
            rs.getArray(11) == null ? List.of() : List.of((String[]) rs.getArray(11).getArray()),
            rs.getObject(12, LocalDate.class), rs.getObject(13, UUID.class), rs.getString(14), rs.getString(15),
            rs.getTimestamp(16) == null ? null : rs.getTimestamp(16).toInstant(), rs.getLong(17));

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final FileService files;

    public DriverService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit, FileService files) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public List<DriverView> list(String q, boolean includeArchived) {
        List<UUID> scope = access.scopeFor("driver:read");
        if (scope.isEmpty()) return List.of();
        String term = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase() + "%";
        return jdbc.query(SELECT + """
                 WHERE d.org_unit_id IN (:s) AND (:arch OR d.archived_at IS NULL)
                   AND (CAST(:q AS text) IS NULL OR lower(d.full_name) LIKE :q OR lower(d.staff_number) LIKE :q
                        OR lower(coalesce(d.phone, '')) LIKE :q)
                 ORDER BY d.full_name LIMIT 1000""",
                new MapSqlParameterSource("s", scope).addValue("arch", includeArchived).addValue("q", term), MAPPER);
    }

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public DriverView get(UUID id) {
        DriverView d = find(id);
        access.require("driver:read", d.orgUnitId(), "driver", id.toString());
        return d;
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public DriverView create(DriverInput in) {
        access.require("driver:write", in.orgUnitId(), "driver", "new");
        requireText(in.staffNumber(), "Matricule");
        requireText(in.fullName(), "Nom complet");
        UUID id = jdbc.queryForObject("""
                INSERT INTO driver(staff_number, full_name, phone, email, org_unit_id, id_card_number, id_card_expires_on)
                VALUES (:sn, :fn, :ph, :em, :o, :cni, :cnie) RETURNING id""", params(in), UUID.class);
        DriverView v = find(id);
        audit.record(access.currentUser().userId(), "CREATE", "driver", id.toString(), null, null, v);
        return v;
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public DriverView update(UUID id, long expectedVersion, DriverInput in, String reason) {
        DriverView before = find(id);
        access.require("driver:write", before.orgUnitId(), "driver", id.toString());
        if (before.archivedAt() != null) throw new IllegalArgumentException("Conducteur archivé : modification impossible.");
        if (in.orgUnitId() != null && !in.orgUnitId().equals(before.orgUnitId())) {
            access.require("driver:write", in.orgUnitId(), "driver", id.toString());
        }
        int n = jdbc.update("""
                UPDATE driver SET staff_number = coalesce(:sn, staff_number), full_name = coalesce(:fn, full_name),
                       phone = coalesce(:ph, phone), email = coalesce(:em, email), org_unit_id = coalesce(:o, org_unit_id),
                       id_card_number = coalesce(:cni, id_card_number), id_card_expires_on = coalesce(:cnie, id_card_expires_on),
                       version = version + 1
                 WHERE id = :id AND version = :v""", params(in).addValue("id", id).addValue("v", expectedVersion));
        if (n == 0) throw new StaleVersionException("version " + expectedVersion);
        DriverView after = find(id);
        audit.record(access.currentUser().userId(), "UPDATE", "driver", id.toString(), reason, before, after);
        return after;
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public DriverView addLicense(UUID id, LicenseInput l) {
        DriverView d = find(id);
        access.require("driver:write", d.orgUnitId(), "driver", id.toString());
        requireText(l.licenseNumber(), "Numéro de permis");
        if (l.expiresOn() == null) throw new IllegalArgumentException("Date d'expiration du permis obligatoire.");
        if (l.categories() == null || l.categories().isEmpty()) throw new IllegalArgumentException("Au moins une catégorie de permis.");
        for (String c : l.categories()) {
            if (!c.matches("^(A1|A|B|BE|C|CE|D|DE|E|F|G)$")) throw new IllegalArgumentException("Catégorie de permis inconnue : " + c);
        }
        files.requireAttachable(l.fileId(), "DRIVER_DOCUMENT", d.orgUnitId());
        jdbc.update("""
                INSERT INTO driver_license(driver_id, license_number, categories, issued_on, expires_on, file_id)
                VALUES (:d, :n, CAST(:c AS text[]), :i, :e, :f)
                ON CONFLICT (driver_id, license_number) DO UPDATE SET categories = EXCLUDED.categories,
                       issued_on = EXCLUDED.issued_on, expires_on = EXCLUDED.expires_on, file_id = coalesce(EXCLUDED.file_id, driver_license.file_id)""",
                new MapSqlParameterSource("d", id).addValue("n", l.licenseNumber().strip().toUpperCase())
                        .addValue("c", "{" + String.join(",", l.categories()) + "}").addValue("i", l.issuedOn())
                        .addValue("e", l.expiresOn()).addValue("f", l.fileId()));
        DriverView after = find(id);
        audit.record(access.currentUser().userId(), "LICENSE", "driver", id.toString(), null, d, after);
        return after;
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public DriverView archive(UUID id, long expectedVersion, String reason) {
        DriverView before = find(id);
        access.require("driver:write", before.orgUnitId(), "driver", id.toString());
        int n = jdbc.update("UPDATE driver SET archived_at = now(), version = version + 1 WHERE id = :id AND version = :v AND archived_at IS NULL",
                new MapSqlParameterSource("id", id).addValue("v", expectedVersion));
        if (n == 0) throw new StaleVersionException("version " + expectedVersion);
        // une affectation en cours se termine avec l'archivage
        jdbc.update("""
                UPDATE vehicle_assignment SET period = tstzrange(lower(period), now())
                 WHERE driver_id = :id AND period @> now() AND lower(period) < now()""", new MapSqlParameterSource("id", id));
        DriverView after = find(id);
        audit.record(access.currentUser().userId(), "ARCHIVE", "driver", id.toString(), reason, before, after);
        return after;
    }

    /**
     * Affecte un conducteur (ou aucun) à un véhicule à partir de maintenant : l'affectation en cours du véhicule et celle du
     * conducteur sont closes ; la base refuse tout chevauchement résiduel.
     */
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public void assign(UUID vehicleId, UUID driverId, String reason) {
        List<Map<String, Object>> v = jdbc.queryForList("SELECT org_unit_id, archived_at FROM vehicle WHERE id = :v", new MapSqlParameterSource("v", vehicleId));
        if (v.isEmpty()) throw new NotFoundException("Véhicule introuvable.");
        UUID org = (UUID) v.get(0).get("org_unit_id");
        access.require("vehicle:write", org, "vehicle", vehicleId.toString());
        if (v.get(0).get("archived_at") != null) throw new IllegalArgumentException("Véhicule archivé.");
        if (driverId != null) {
            DriverView d = find(driverId);
            access.require("driver:read", d.orgUnitId(), "driver", driverId.toString());
            if (d.archivedAt() != null) throw new IllegalArgumentException("Conducteur archivé.");
        }
        Timestamp now = Timestamp.from(Instant.now());
        MapSqlParameterSource p = new MapSqlParameterSource("v", vehicleId).addValue("d", driverId).addValue("now", now)
                .addValue("o", org).addValue("u", access.currentUser().userId());
        // affectations futures (ou commençant maintenant) remplacées, affectation en cours close à cet instant
        jdbc.update("""
                DELETE FROM vehicle_assignment
                 WHERE (vehicle_id = :v OR (CAST(:d AS uuid) IS NOT NULL AND driver_id = :d)) AND lower(period) >= CAST(:now AS timestamptz)""", p);
        jdbc.update("""
                UPDATE vehicle_assignment SET period = tstzrange(lower(period), :now)
                 WHERE (vehicle_id = :v OR (CAST(:d AS uuid) IS NOT NULL AND driver_id = :d)) AND period @> CAST(:now AS timestamptz)""", p);
        if (driverId != null) {
            jdbc.update("""
                    INSERT INTO vehicle_assignment(vehicle_id, driver_id, org_unit_id, period, created_by)
                    VALUES (:v, :d, :o, tstzrange(:now, NULL), :u)""", p);
        }
        audit.record(access.currentUser().userId(), "ASSIGN", "vehicle", vehicleId.toString(), reason, null,
                driverId == null ? Map.of("driverId", "aucun") : Map.of("driverId", driverId.toString()));
    }

    private DriverView find(UUID id) {
        List<DriverView> l = jdbc.query(SELECT + " WHERE d.id = :id", new MapSqlParameterSource("id", id), MAPPER);
        if (l.isEmpty()) throw new NotFoundException("Conducteur introuvable.");
        return l.get(0);
    }

    private static MapSqlParameterSource params(DriverInput in) {
        return new MapSqlParameterSource("sn", trim(in.staffNumber())).addValue("fn", trim(in.fullName()))
                .addValue("ph", trim(in.phone())).addValue("em", trim(in.email())).addValue("o", in.orgUnitId())
                .addValue("cni", trim(in.idCardNumber())).addValue("cnie", in.idCardExpiresOn());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static void requireText(String s, String label) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(label + " obligatoire.");
    }
}
