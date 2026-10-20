package cm.camtel.nextfleet.api.live;

import cm.camtel.nextfleet.api.identity.AccessService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Émetteurs GPS (téléphones et boîtiers) avec leur état vu par Traccar, pour la supervision. */
@RestController
@RequestMapping("/api/v1/fleet/trackers")
public class TrackerController {

    public record Tracker(String uniqueId, String kind, String label, String registration, Instant registeredAt, boolean disabled,
                          String traccarStatus, String traccarLastUpdate, Instant lastAcceptedAt) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final TraccarClient traccar;

    public TrackerController(NamedParameterJdbcTemplate jdbc, AccessService access, TraccarClient traccar) {
        this.jdbc = jdbc;
        this.access = access;
        this.traccar = traccar;
    }

    @GetMapping
    public List<Tracker> list() {
        if (access.scopeFor("gps:read").isEmpty() && access.scopeFor("admin:settings").isEmpty()) {
            throw new AccessDeniedException("Supervision GPS hors profil");
        }
        Map<String, TraccarClient.DeviceStatus> remote = traccar.devices();
        return jdbc.query("""
                SELECT s.unique_id, s.kind, s.label, v.registration, s.registered_at, s.disabled_at IS NOT NULL,
                       (SELECT max(recorded_at) FROM location_point l WHERE l.source_device_id = s.unique_id)
                  FROM tracking_source s LEFT JOIN vehicle v ON v.id = s.vehicle_id ORDER BY s.kind, s.label""",
                new MapSqlParameterSource(), (rs, i) -> {
                    TraccarClient.DeviceStatus d = remote.get(rs.getString(1));
                    return new Tracker(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(),
                            rs.getBoolean(6), d == null ? (traccar.configured() ? "inconnu de Traccar" : "Traccar non configuré") : d.status(),
                            d == null ? null : d.lastUpdate(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant());
                });
    }
}
