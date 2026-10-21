package cm.camtel.nextfleet.api.live;

import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import cm.camtel.nextfleet.domain.security.AccessPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Périmètre temps réel d'un utilisateur (sans contexte de requête) et enrichissement des événements. */
@Component
public class LiveScope {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<UUID, String[]> vehicles = new ConcurrentHashMap<>();

    public LiveScope(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Entités où l'utilisateur détient gps:read ou mission:manage. */
    public Set<UUID> orgScope(AuthenticatedUser u) {
        Map<String, Set<String>> perms = new HashMap<>();
        jdbc.query("SELECT role_code, permission_code FROM role_permission", rs -> {
            perms.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
        });
        Map<String, String> tree = new HashMap<>();
        jdbc.query("SELECT id::text, parent_id::text FROM organisation_unit", rs -> {
            tree.put(rs.getString(1), rs.getString(2));
        });
        List<AccessPolicy.Grant> grants = jdbc.query("""
                SELECT role_code, scope_org_id::text, valid_from, valid_until, revoked_at FROM user_role_grant WHERE user_id = :u""",
                new MapSqlParameterSource("u", u.userId()), (rs, i) -> new AccessPolicy.Grant(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()));
        AccessPolicy p = new AccessPolicy(perms, tree);
        Set<UUID> out = new HashSet<>();
        for (String org : tree.keySet()) {
            if (p.isAllowed(grants, "gps:read", org, clock.instant()) || p.isAllowed(grants, "mission:manage", org, clock.instant())) {
                out.add(UUID.fromString(org));
            }
        }
        return out;
    }

    /** Événement client : immatriculation, libellé, vitesse en km/h ; jamais l'entité ni l'identifiant du conducteur. */
    public ObjectNode enrich(JsonNode e) {
        ObjectNode o = mapper.createObjectNode().put("type", "position");
        String vid = e.path("vehicleId").asText();
        String[] v = vehicles.computeIfAbsent(UUID.fromString(vid), id -> jdbc.query(
                "SELECT registration, make || ' ' || model, coalesce(category, '') FROM vehicle WHERE id = :id",
                new MapSqlParameterSource("id", id), rs -> rs.next()
                        ? new String[]{rs.getString(1), rs.getString(2), rs.getString(3)} : new String[]{"?", "", ""}));
        o.put("vehicleId", vid).put("registration", v[0]).put("vehicleLabel", v[1]).put("category", v[2])
                .put("missionId", e.path("missionId").asText()).put("missionRef", e.path("missionRef").asText())
                .put("lat", e.path("lat").asDouble()).put("lon", e.path("lon").asDouble())
                .put("accuracyM", e.path("accuracyM").asDouble()).put("recordedAt", e.path("recordedAt").asText());
        if (e.hasNonNull("speedMps")) o.put("speedKmh", Math.round(e.get("speedMps").asDouble() * 3.6 * 10) / 10.0);
        if (e.hasNonNull("bearingDeg")) o.put("bearingDeg", e.get("bearingDeg").asDouble());
        return o;
    }

    /** Participants actifs d'une conversation qui détiennent la permission chat:use. */
    public Set<UUID> chatParticipants(UUID conversationId) {
        return new java.util.HashSet<>(jdbc.queryForList("""
                SELECT user_id FROM conversation_participant
                 WHERE conversation_id = :c AND left_at IS NULL AND nf_has_permission(user_id, 'chat:use')""",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("c", conversationId), UUID.class));
    }
}
