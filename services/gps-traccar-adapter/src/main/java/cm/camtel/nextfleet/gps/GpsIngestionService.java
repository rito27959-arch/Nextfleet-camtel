package cm.camtel.nextfleet.gps;

import cm.camtel.nextfleet.domain.gps.GpsFix;
import cm.camtel.nextfleet.domain.gps.MissionTrackingGate;
import cm.camtel.nextfleet.domain.gps.MissionTrackingGate.Authorization;
import cm.camtel.nextfleet.domain.gps.MissionTrackingGate.Decision;
import cm.camtel.nextfleet.domain.gps.MissionTrackingGate.Mission;
import cm.camtel.nextfleet.domain.gps.MissionTrackingGate.MissionStatus;
import cm.camtel.nextfleet.domain.gps.MissionTrackingGate.Reason;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Ingestion des positions (Traccar ou téléphone) dans PostgreSQL : décision par {@link MissionTrackingGate},
 * écriture idempotente dans {@code location_point}, preuve du refus dans {@code gps_ingest_rejection} sans coordonnées.
 * Le trigger de la base reste la dernière barrière : si elle refuse, le point est compté comme rejeté.
 */
public final class GpsIngestionService {

    public enum Channel { TRACCAR_FORWARD, ANDROID_GATEWAY }

    public enum Status { ACCEPTED, DUPLICATE, REJECTED }

    public record Outcome(Status status, Reason reason, String missionReference) { }

    private final Supplier<Connection> connections;
    private final MissionTrackingGate gate = new MissionTrackingGate();

    public GpsIngestionService(Supplier<Connection> connections) {
        this.connections = connections;
    }

    /**
     * @param onlyMission si présent (jeton de mission Android), seule cette mission peut recevoir le point
     */
    public Outcome ingest(String deviceId, GpsFix fix, Double bearingDeg, String provider, Channel channel,
                          Optional<UUID> onlyMission) throws SQLException {
        try (Connection c = connections.get()) {
            List<Authorization> auths = new ArrayList<>();
            Map<UUID, Mission> missions = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT a.mission_id, a.granted_at, a.revoked_at, m.reference, m.vehicle_id, m.status, m.started_at, m.finished_at
                      FROM tracking_authorization a JOIN mission m ON m.id = a.mission_id
                     WHERE a.source_device_id = ?""")) {
                ps.setString(1, deviceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID mid = rs.getObject(1, UUID.class);
                        if (onlyMission.isPresent() && !onlyMission.get().equals(mid)) continue;
                        auths.add(new Authorization(mid, deviceId, ts(rs, 2), ts(rs, 3)));
                        missions.put(mid, new Mission(mid, rs.getString(4), rs.getObject(5, UUID.class),
                                MissionStatus.valueOf(rs.getString(6)), ts(rs, 7), ts(rs, 8)));
                    }
                }
            }
            Decision d = gate.evaluate(deviceId, fix.recordedAt(), auths, id -> Optional.ofNullable(missions.get(id)));
            if (!d.accepted()) {
                reject(c, deviceId, fix, channel, d.reason());
                return new Outcome(Status.REJECTED, d.reason(), null);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position,
                                               accuracy_m, speed_mps, bearing_deg, provider)
                    VALUES (?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?)
                    ON CONFLICT (source_device_id, client_fix_id) DO NOTHING""")) {
                ps.setObject(1, d.mission().id());
                ps.setObject(2, d.mission().vehicleId());
                ps.setString(3, deviceId);
                ps.setString(4, fix.clientFixId());
                ps.setTimestamp(5, Timestamp.from(fix.recordedAt()));
                ps.setDouble(6, fix.longitude());
                ps.setDouble(7, fix.latitude());
                ps.setFloat(8, (float) fix.accuracyM());
                if (fix.speedMps() == null) ps.setNull(9, java.sql.Types.REAL);
                else ps.setFloat(9, (float) Math.max(0, fix.speedMps().doubleValue()));
                if (bearingDeg == null) ps.setNull(10, java.sql.Types.REAL);
                else ps.setFloat(10, (float) (((bearingDeg % 360) + 360) % 360));
                ps.setString(11, provider);
                int n = ps.executeUpdate();
                return new Outcome(n == 1 ? Status.ACCEPTED : Status.DUPLICATE, null, d.mission().reference());
            } catch (SQLException dbRefusal) {
                if (!"23514".equals(dbRefusal.getSQLState()) && !"42501".equals(dbRefusal.getSQLState())) throw dbRefusal;
                reject(c, deviceId, fix, channel, Reason.HORS_PERIODE);
                return new Outcome(Status.REJECTED, Reason.HORS_PERIODE, null);
            }
        }
    }

    private static void reject(Connection c, String deviceId, GpsFix fix, Channel channel, Reason reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO gps_ingest_rejection(source_device_id, recorded_at, channel, reason_code) VALUES (?,?,?,?)")) {
            ps.setString(1, deviceId.length() > 64 ? deviceId.substring(0, 64) : deviceId);
            ps.setTimestamp(2, fix == null ? null : Timestamp.from(fix.recordedAt()));
            ps.setString(3, channel.name());
            ps.setString(4, reason.name());
            ps.executeUpdate();
        }
    }

    private static java.time.Instant ts(ResultSet rs, int i) throws SQLException {
        Timestamp t = rs.getTimestamp(i);
        return t == null ? null : t.toInstant();
    }
}
