package cm.camtel.nextfleet.api.admin;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Administration des comptes (admin:users) et consultation du journal d'audit (audit:read), dans le périmètre.
 * Les mots de passe ne sont jamais renvoyés ; une suspension révoque les sessions ouvertes.
 */
@Service
public class AdminService {

    public record UserView(UUID id, String login, String displayName, String email, String orgUnit, String status,
                           List<String> roles, Instant lockedUntil, Instant createdAt, long version,
                           UUID driverId, String driverStaffNumber) { }

    public record AuditView(long id, Instant occurredAt, String actor, String action, String objectType, String objectId,
                            String reason, String requestId, String hash, String previousHash) { }

    /** Résultat de la vérification serveur de la chaîne (fonction nf_audit_verify : chaînage et contenu). */
    public record ChainCheck(long events, Long firstBrokenId, boolean intact) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;

    public AdminService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    private List<UUID> scope(String permission) {
        List<UUID> s = access.scopeFor(permission);
        if (s.isEmpty()) {
            AuthenticatedUser u = access.currentUser();
            audit.recordDenied(u.userId(), "admin", permission, permission);
            throw new AccessDeniedException("Hors profil");
        }
        return s;
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public List<UserView> users(String q) {
        List<UUID> s = scope("admin:users");
        return jdbc.query("""
                SELECT u.id, u.login, u.display_name, u.email, o.name, u.status, u.locked_until, u.created_at, u.version,
                       coalesce((SELECT array_agg(DISTINCT g.role_code ORDER BY g.role_code) FROM user_role_grant g
                                  WHERE g.user_id = u.id AND g.revoked_at IS NULL AND (g.valid_until IS NULL OR g.valid_until > now())), '{}') AS roles,
                       d.id AS driver_id, d.staff_number AS driver_staff
                  FROM app_user u JOIN organisation_unit o ON o.id = u.org_unit_id
                  LEFT JOIN driver d ON d.user_id = u.id
                 WHERE u.org_unit_id IN (:s)
                   AND (CAST(:q AS text) IS NULL OR u.login_normalized LIKE '%' || lower(CAST(:q AS text)) || '%'
                        OR lower(u.display_name) LIKE '%' || lower(CAST(:q AS text)) || '%')
                 ORDER BY u.display_name LIMIT 500""",
                new MapSqlParameterSource("s", s).addValue("q", q == null || q.isBlank() ? null : q.strip()),
                (rs, i) -> new UserView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), List.of((String[]) rs.getArray("roles").getArray()), ts(rs.getTimestamp(7)), ts(rs.getTimestamp(8)),
                        rs.getLong(9), rs.getObject("driver_id", UUID.class), rs.getString("driver_staff")));
    }

    /** ACTIVE ↔ SUSPENDED ; jamais sur son propre compte ; motif obligatoire ; sessions révoquées à la suspension. */
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public UserView setStatus(UUID userId, String status, String reason, long expectedVersion) {
        AuthenticatedUser me = access.currentUser();
        List<UUID> org = jdbc.queryForList("SELECT org_unit_id FROM app_user WHERE id = :id", new MapSqlParameterSource("id", userId), UUID.class);
        if (org.isEmpty()) throw new NotFoundException("Compte introuvable.");
        access.require("admin:users", org.get(0), "app_user", userId.toString());
        if (me.userId().equals(userId)) throw new IllegalArgumentException("Vous ne pouvez pas modifier le statut de votre propre compte.");
        if (!List.of("ACTIVE", "SUSPENDED").contains(status)) throw new IllegalArgumentException("Statut inconnu.");
        if (reason == null || reason.strip().length() < 5) throw new IllegalArgumentException("Motif obligatoire (5 caractères au moins).");
        String old = jdbc.queryForObject("SELECT status FROM app_user WHERE id = :id", new MapSqlParameterSource("id", userId), String.class);
        int n = jdbc.update("""
                UPDATE app_user SET status = :st, failed_logins = 0, locked_until = NULL, version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :v""",
                new MapSqlParameterSource("id", userId).addValue("st", status).addValue("v", expectedVersion));
        if (n == 0) throw new cm.camtel.nextfleet.api.common.ApiExceptionHandler.StaleVersionException("Version obsolète");
        // la révocation des sessions à la suspension est faite par la base (trigger nf_revoke_sessions_on_status)
        audit.record(me.userId(), "USER_STATUS", "app_user", userId.toString(), reason.strip(), old, status);
        return users(null).stream().filter(u -> u.id().equals(userId)).findFirst().orElseThrow();
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public List<AuditView> audit(String objectType, String objectId, Instant from, Instant to, int limit) {
        scope("audit:read");
        List<String> where = new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource("lim", Math.min(Math.max(limit, 1), 1000));
        if (objectType != null && !objectType.isBlank()) { where.add("e.object_type = :t"); p.addValue("t", objectType); }
        if (objectId != null && !objectId.isBlank()) { where.add("e.object_id = :oid"); p.addValue("oid", objectId); }
        if (from != null) { where.add("e.occurred_at >= :from"); p.addValue("from", Timestamp.from(from)); }
        if (to != null) { where.add("e.occurred_at < :to"); p.addValue("to", Timestamp.from(to)); }
        String sql = """
                SELECT e.id, e.occurred_at, coalesce(u.display_name, 'Système'), e.action, e.object_type, e.object_id, e.reason, e.request_id,
                       encode(e.row_hash, 'hex'), encode(e.prev_hash, 'hex')
                  FROM audit_event e LEFT JOIN app_user u ON u.id = e.actor_id
                """ + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY e.id DESC LIMIT :lim";
        return jdbc.query(sql, p, (rs, i) -> new AuditView(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)));
    }

    /** Recalcule toute la chaîne côté base (formule de nf_audit_chain) : chaînage ET contenu de chaque ligne. */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public ChainCheck verifyChain() {
        scope("audit:read");
        return jdbc.queryForObject("SELECT events, first_broken_id FROM nf_audit_verify()", new MapSqlParameterSource(), (rs, i) -> {
            Long broken = (Long) rs.getObject(2);
            return new ChainCheck(rs.getLong(1), broken, broken == null);
        });
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
