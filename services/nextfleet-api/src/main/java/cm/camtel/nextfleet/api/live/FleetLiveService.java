package cm.camtel.nextfleet.api.live;

import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import cm.camtel.nextfleet.domain.gps.TrackPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Carte de flotte : dernière position ACCEPTÉE de chaque véhicule en mission (en cours, ou terminée depuis moins de
 * 12 h) dans le périmètre. L'état est calculé côté serveur pour que bureau et mobile affichent la même chose :
 * une position plus vieille que le seuil « en direct » n'est jamais présentée comme actuelle, et une perte de signal
 * n'est pas une panne.
 */
@Service
public class FleetLiveService {

    /** EN_MOUVEMENT (vert), ARRET (bleu), SIGNAL_ANCIEN (gris), A_VERIFIER (orange : anomalie ouverte). */
    public enum State { EN_MOUVEMENT, ARRET, SIGNAL_ANCIEN, A_VERIFIER }

    public record VehicleLive(UUID vehicleId, String registration, String vehicleLabel, String category, UUID missionId,
                              String missionRef, String missionStatus, String driverName, String origin, String destination,
                              double lat, double lon, double accuracyM, Double speedKmh, Double bearingDeg, Instant recordedAt,
                              long ageSeconds, State state, String stateLabel, Instant startedAt, Double distanceKm,
                              int openAnomalies) { }

    private static final double MOVING_MPS = 1.5;   // ~5 km/h : en dessous, le véhicule est considéré à l'arrêt

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final LiveScope scopes;
    private final Clock clock;
    private final TrackPolicy policy = TrackPolicy.demoDefaults();

    public FleetLiveService(NamedParameterJdbcTemplate jdbc, AccessService access, LiveScope scopes, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.scopes = scopes;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<VehicleLive> live() {
        AuthenticatedUser u = access.currentUser();
        List<UUID> scope = new ArrayList<>(scopes.orgScope(u));
        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource("me", u.userId())
                .addValue("scope", scope.isEmpty() ? List.of(new UUID(0, 0)) : scope)
                .addValue("since", java.sql.Timestamp.from(now.minus(Duration.ofHours(12))));
        return jdbc.query("""
                SELECT v.id, v.registration, v.make || ' ' || v.model, v.category, m.id, m.reference, m.status, d.full_name,
                       m.origin_label, m.destination_label, ST_Y(lp.position::geometry), ST_X(lp.position::geometry), lp.accuracy_m,
                       lp.speed_mps, lp.bearing_deg, lp.recorded_at, m.started_at,
                       (SELECT ST_Length(ST_MakeLine(x.position::geometry ORDER BY x.recorded_at)::geography) / 1000.0
                          FROM location_point x WHERE x.mission_id = m.id) AS km,
                       (SELECT count(*) FROM anomaly a WHERE a.vehicle_id = v.id AND a.status IN ('A_VERIFIER','EN_ANALYSE')) AS anomalies
                  FROM mission m
                  JOIN vehicle v ON v.id = m.vehicle_id
                  JOIN driver d ON d.id = m.driver_id
                  JOIN LATERAL (SELECT * FROM location_point l WHERE l.mission_id = m.id ORDER BY l.recorded_at DESC LIMIT 1) lp ON true
                 WHERE (m.status = 'ACTIVE' OR (m.status = 'FINISHED' AND m.finished_at >= :since))
                   AND (m.org_unit_id IN (:scope) OR d.user_id = :me)
                 ORDER BY lp.recorded_at DESC""", p, (rs, i) -> {
            Instant at = rs.getTimestamp(16).toInstant();
            long age = Math.max(0, Duration.between(at, now).getSeconds());
            Double speed = nullable(rs, 14);
            int anomalies = rs.getInt(19);
            boolean active = "ACTIVE".equals(rs.getString(7));
            State st = anomalies > 0 ? State.A_VERIFIER
                    : !active || age > policy.liveThreshold().getSeconds() ? State.SIGNAL_ANCIEN
                    : speed != null && speed >= MOVING_MPS ? State.EN_MOUVEMENT : State.ARRET;
            return new VehicleLive(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                    rs.getDouble(11), rs.getDouble(12), rs.getDouble(13), speed == null ? null : Math.round(speed * 36) / 10.0,
                    nullable(rs, 15), at, age, st, label(st, age, active),
                    rs.getTimestamp(17) == null ? null : rs.getTimestamp(17).toInstant(),
                    rs.getObject(18) == null ? null : Math.round(rs.getDouble(18) * 10) / 10.0, anomalies);
        });
    }

    private static Double nullable(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    static String label(State s, long age, boolean active) {
        return switch (s) {
            case EN_MOUVEMENT -> "En mouvement";
            case ARRET -> "À l'arrêt";
            case A_VERIFIER -> "À vérifier";
            case SIGNAL_ANCIEN -> !active ? "Mission terminée" : "Dernier signal il y a " + human(age);
        };
    }

    static String human(long s) {
        if (s < 3600) return Math.max(1, s / 60) + " min";
        if (s < 86400) return (s / 3600) + " h " + String.format("%02d", (s % 3600) / 60);
        return (s / 86400) + " j";
    }
}
