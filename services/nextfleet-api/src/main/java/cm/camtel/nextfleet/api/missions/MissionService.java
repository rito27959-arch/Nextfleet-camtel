package cm.camtel.nextfleet.api.missions;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import cm.camtel.nextfleet.domain.gps.MissionToken;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Missions et collecte GPS (NF-70 à NF-74). Démarrage par le conducteur affecté seulement, après information
 * (notice versionnée) ; il crée l'autorisation de collecte de l'émetteur et délivre le jeton de mission éphémère
 * utilisé par la passerelle GPS. La clôture révoque l'autorisation (déclencheur de la base).
 */
@Service
public class MissionService {

    public record MissionView(UUID id, String reference, String purpose, String origin, String destination, String registration,
                              String vehicleLabel, String category, String driverName, String status, Instant plannedStart,
                              Instant plannedEnd, Instant startedAt, Instant finishedAt, String deviceId, String noticeVersion,
                              UUID conversationId, long version) { }

    public record StartResult(MissionView mission, String missionToken, String gatewayUrl) { }

    public record TrackPoint(String clientFixId, Instant recordedAt, double lat, double lon, double accuracyM, Double speedMps) { }

    public record MissionCreate(UUID vehicleId, UUID driverId, String purpose, String origin, String destination,
                                Instant plannedStart, Instant plannedEnd) { }

    private static final String SELECT = """
            SELECT m.id, m.reference, m.purpose, m.origin_label, m.destination_label, v.registration,
                   v.make || ' ' || v.model AS vehicle_label, v.category, d.full_name, m.status, m.planned_start, m.planned_end,
                   m.started_at, m.finished_at, m.version, m.org_unit_id, d.user_id AS driver_user,
                   (SELECT a.source_device_id FROM tracking_authorization a WHERE a.mission_id = m.id ORDER BY a.granted_at DESC LIMIT 1) AS device,
                   (SELECT a.notice_version FROM tracking_authorization a WHERE a.mission_id = m.id ORDER BY a.granted_at DESC LIMIT 1) AS notice,
                   (SELECT c.id FROM conversation c WHERE c.mission_id = m.id ORDER BY c.created_at LIMIT 1) AS conv
              FROM mission m JOIN vehicle v ON v.id = m.vehicle_id JOIN driver d ON d.id = m.driver_id
            """;

    private record Row(MissionView view, UUID orgUnitId, UUID driverUser) { }

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(new MissionView(rs.getObject("id", UUID.class), rs.getString("reference"),
            rs.getString("purpose"), rs.getString("origin_label"), rs.getString("destination_label"), rs.getString("registration"),
            rs.getString("vehicle_label"), rs.getString("category"), rs.getString("full_name"), rs.getString("status"),
            ts(rs.getTimestamp("planned_start")), ts(rs.getTimestamp("planned_end")), ts(rs.getTimestamp("started_at")),
            ts(rs.getTimestamp("finished_at")), rs.getString("device"), rs.getString("notice"), rs.getObject("conv", UUID.class),
            rs.getLong("version")), rs.getObject("org_unit_id", UUID.class), rs.getObject("driver_user", UUID.class));

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final GpsProperties gps;
    private final Clock clock;

    public MissionService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit, GpsProperties gps, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.gps = gps;
        this.clock = clock;
    }

    /** Missions du périmètre (gestion, trajets) ou, pour un conducteur, ses seules missions. */
    @Transactional(readOnly = true)
    public List<MissionView> list() {
        AuthenticatedUser u = access.currentUser();
        List<UUID> scope = new ArrayList<>(access.scopeFor("mission:manage"));
        access.scopeFor("gps:read").forEach(o -> {
            if (!scope.contains(o)) scope.add(o);
        });
        boolean drives = !access.scopeFor("mission:drive").isEmpty();
        if (scope.isEmpty() && !drives) throw new AccessDeniedException("Missions hors profil");
        MapSqlParameterSource p = new MapSqlParameterSource("me", u.userId()).addValue("scope", scope.isEmpty() ? List.of(new UUID(0, 0)) : scope)
                .addValue("drives", drives);
        return jdbc.query(SELECT + """
                 WHERE m.org_unit_id IN (:scope) OR (:drives AND d.user_id = :me)
                 ORDER BY CASE m.status WHEN 'ACTIVE' THEN 0 WHEN 'AUTHORIZED' THEN 1 WHEN 'DRAFT' THEN 2 ELSE 3 END, m.planned_start DESC
                 LIMIT 500""", p, ROW).stream().map(Row::view).toList();
    }

    private Row row(UUID id) {
        List<Row> r = jdbc.query(SELECT + " WHERE m.id = :id", new MapSqlParameterSource("id", id), ROW);
        if (r.isEmpty()) throw new NotFoundException("Mission introuvable.");
        return r.get(0);
    }

    @Transactional
    public MissionView create(MissionCreate c) {
        UUID org = jdbc.queryForObject("SELECT org_unit_id FROM vehicle WHERE id = :v AND archived_at IS NULL",
                new MapSqlParameterSource("v", c.vehicleId()), UUID.class);
        access.require("mission:manage", org, "mission", "new");
        if (c.plannedEnd() == null || c.plannedStart() == null || !c.plannedEnd().isAfter(c.plannedStart())) {
            throw new IllegalArgumentException("La fin prévue doit suivre le début prévu.");
        }
        AuthenticatedUser u = access.currentUser();
        UUID id = UUID.randomUUID();
        long n = jdbc.queryForObject("SELECT count(*) + 1 FROM mission WHERE reference LIKE :p",
                new MapSqlParameterSource("p", "MIS-" + clock.instant().toString().substring(0, 4) + "-%"), Long.class);
        String ref = "MIS-" + clock.instant().toString().substring(0, 4) + "-" + String.format("%03d", n);
        jdbc.update("""
                INSERT INTO mission(id, reference, org_unit_id, vehicle_id, driver_id, purpose, origin_label, destination_label,
                                    planned_start, planned_end, created_by)
                VALUES (:id, :ref, :org, :v, :d, :purpose, :o, :dest, :ps, :pe, :me)""",
                new MapSqlParameterSource("id", id).addValue("ref", ref).addValue("org", org).addValue("v", c.vehicleId())
                        .addValue("d", c.driverId()).addValue("purpose", c.purpose()).addValue("o", c.origin())
                        .addValue("dest", c.destination()).addValue("ps", Timestamp.from(c.plannedStart()))
                        .addValue("pe", Timestamp.from(c.plannedEnd())).addValue("me", u.userId()));
        // conversation privée de mission : créateur + conducteur (s'il a un compte)
        UUID conv = UUID.randomUUID();
        jdbc.update("INSERT INTO conversation(id, kind, org_unit_id, mission_id, title) VALUES (:c, 'MISSION', :org, :m, :t)",
                new MapSqlParameterSource("c", conv).addValue("org", org).addValue("m", id).addValue("t", ref));
        jdbc.update("""
                INSERT INTO conversation_participant(conversation_id, user_id)
                SELECT :c, x FROM (SELECT CAST(:me AS uuid) AS x UNION SELECT user_id FROM driver WHERE id = :d AND user_id IS NOT NULL) s""",
                new MapSqlParameterSource("c", conv).addValue("me", u.userId()).addValue("d", c.driverId()));
        audit.record(u.userId(), "CREATE", "mission", id.toString(), null, null, ref);
        return row(id).view();
    }

    /** Autorisation par un gestionnaire distinct du créateur (aucune auto-approbation). */
    @Transactional
    public MissionView authorize(UUID id) {
        Row r = row(id);
        access.require("mission:manage", r.orgUnitId(), "mission", id.toString());
        AuthenticatedUser u = access.currentUser();
        UUID creator = jdbc.queryForObject("SELECT created_by FROM mission WHERE id = :id", new MapSqlParameterSource("id", id), UUID.class);
        if (u.userId().equals(creator)) throw new IllegalArgumentException("Une mission ne peut pas être autorisée par son créateur.");
        int n = jdbc.update("""
                UPDATE mission SET status = 'AUTHORIZED', authorized_by = :me, authorized_at = now(), version = version + 1
                 WHERE id = :id AND status = 'DRAFT'""", new MapSqlParameterSource("id", id).addValue("me", u.userId()));
        if (n == 0) throw new IllegalStateException("Seule une mission en brouillon peut être autorisée.");
        audit.record(u.userId(), "AUTHORIZE", "mission", id.toString(), null, r.view().status(), "AUTHORIZED");
        return row(id).view();
    }

    @Transactional
    public StartResult start(UUID id, String noticeVersion, String deviceId) {
        AuthenticatedUser u = access.currentUser();
        Row r = row(id);
        if (!u.userId().equals(r.driverUser()) || access.scopeFor("mission:drive").isEmpty()) {
            throw new AccessDeniedException("Seul le conducteur affecté démarre sa mission");
        }
        if (!gps.noticeVersions().contains(noticeVersion)) throw new IllegalArgumentException("Notice d'information inconnue.");
        if (deviceId == null || !deviceId.matches("^[A-Za-z0-9._-]{3,64}$")) throw new IllegalArgumentException("Identifiant d'émetteur invalide.");
        if (gps.missionTokenKey() == null || gps.missionTokenKey().isBlank()) {
            throw new IllegalStateException("Passerelle GPS non configurée (clé de jeton absente).");
        }
        int n = jdbc.update("""
                UPDATE mission SET status = 'ACTIVE', started_at = now(), version = version + 1
                 WHERE id = :id AND status = 'AUTHORIZED'""", new MapSqlParameterSource("id", id));
        if (n == 0) throw new IllegalStateException("Cette mission n'est pas autorisée ou a déjà démarré.");
        Boolean disabled = jdbc.query("SELECT disabled_at IS NOT NULL FROM tracking_source WHERE unique_id = :d",
                new MapSqlParameterSource("d", deviceId), rs -> rs.next() ? rs.getBoolean(1) : null);
        if (Boolean.TRUE.equals(disabled)) throw new AccessDeniedException("Émetteur désactivé");
        if (disabled == null) {
            jdbc.update("""
                    INSERT INTO tracking_source(unique_id, kind, label, registered_by) VALUES (:d, 'ANDROID', 'Téléphone de mission', :me)""",
                    new MapSqlParameterSource("d", deviceId).addValue("me", u.userId()));
        }
        jdbc.update("""
                INSERT INTO tracking_authorization(mission_id, source_device_id, informed_at, notice_version, granted_at)
                VALUES (:m, :d, now(), :notice, now())""",
                new MapSqlParameterSource("m", id).addValue("d", deviceId).addValue("notice", noticeVersion));
        audit.record(u.userId(), "MISSION_STARTED", "mission", id.toString(), noticeVersion, null, deviceId);
        String token = new MissionToken(Base64.getDecoder().decode(gps.missionTokenKey()))
                .issue(id, deviceId, clock.instant(), gps.missionTokenTtl());
        return new StartResult(row(id).view(), token, gps.gatewayPublicUrl());
    }

    @Transactional
    public MissionView finish(UUID id) {
        AuthenticatedUser u = access.currentUser();
        Row r = row(id);
        boolean driver = u.userId().equals(r.driverUser());
        if (!driver) access.require("mission:manage", r.orgUnitId(), "mission", id.toString());
        int n = jdbc.update("""
                UPDATE mission SET status = 'FINISHED', finished_at = now(), version = version + 1 WHERE id = :id AND status = 'ACTIVE'""",
                new MapSqlParameterSource("id", id));
        if (n == 0) throw new IllegalStateException("Cette mission n'est pas en cours.");
        audit.record(u.userId(), "MISSION_FINISHED", "mission", id.toString(), null, "ACTIVE", "FINISHED");
        return row(id).view();
    }

    /** Positions ACCEPTÉES (jamais hors période de mission, garanti par la base). Le refus reste journalisé. */
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public List<TrackPoint> track(UUID id) {
        AuthenticatedUser u = access.currentUser();
        Row r = row(id);
        if (!u.userId().equals(r.driverUser())) {
            List<UUID> scope = new ArrayList<>(access.scopeFor("gps:read"));
            scope.addAll(access.scopeFor("mission:manage"));
            if (!scope.contains(r.orgUnitId())) {
                audit.record(u.userId(), "ACCESS_DENIED", "mission", id.toString(), "gps:read", null, null);
                throw new AccessDeniedException("Trajet hors périmètre");
            }
        }
        return jdbc.query("""
                SELECT client_fix_id, recorded_at, ST_Y(position::geometry) AS lat, ST_X(position::geometry) AS lon, accuracy_m, speed_mps
                  FROM location_point WHERE mission_id = :id ORDER BY recorded_at LIMIT 20000""",
                new MapSqlParameterSource("id", id), (rs, i) -> new TrackPoint(rs.getString(1), rs.getTimestamp(2).toInstant(),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), (Double) rs.getObject(6) == null ? null : rs.getDouble(6)));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
