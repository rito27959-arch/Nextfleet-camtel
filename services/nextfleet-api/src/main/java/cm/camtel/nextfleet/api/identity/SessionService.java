package cm.camtel.nextfleet.api.identity;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.domain.security.AccessPolicy.Grant;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessions opaques côté serveur (NF-01, SEC-01) : le jeton n'est jamais stocké, seul son SHA-256 l'est.
 * Révocation immédiate à la déconnexion, à la suspension (trigger base) ou à l'expiration.
 * La fédération OIDC CAMTEL (NF-50) remplacera la vérification de mot de passe après accord DSIR.
 */
@Service
public class SessionService {

    public record LoginResult(String token, Instant expiresAt, AuthenticatedUser user) { }

    private static final SecureRandom RANDOM = new SecureRandom();
    // Empreinte factice : temps de réponse constant que l'identifiant existe ou non.
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z8hY8NVbQhZpL5.gS2JmDDiu";

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final NextfleetProperties props;
    private final AuditService audit;
    private final Clock clock;

    public SessionService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                          NextfleetProperties props, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Optional<LoginResult> login(String login, String password, String clientKind) {
        Instant now = clock.instant();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, password_hash, status, failed_logins, locked_until
                  FROM app_user WHERE login_normalized = lower(btrim(:login)) FOR UPDATE""",
                Map.of("login", login));
        if (rows.isEmpty()) {
            passwordEncoder.matches(password, DUMMY_HASH);
            return Optional.empty();
        }
        Map<String, Object> u = rows.get(0);
        UUID userId = (UUID) u.get("id");
        Timestamp lockedUntil = (Timestamp) u.get("locked_until");
        String hash = (String) u.get("password_hash");
        boolean locked = lockedUntil != null && lockedUntil.toInstant().isAfter(now);
        boolean ok = hash != null && passwordEncoder.matches(password, hash);
        if (!ok || locked || !"ACTIVE".equals(u.get("status"))) {
            if (!ok) {
                int failed = ((Number) u.get("failed_logins")).intValue() + 1;
                Timestamp lockUntil = failed >= props.session().maxFailedLogins()
                        ? Timestamp.from(now.plus(props.session().lockout())) : null;
                jdbc.update("UPDATE app_user SET failed_logins = :f, locked_until = :l WHERE id = :id",
                        new MapSqlParameterSource().addValue("f", failed).addValue("l", lockUntil).addValue("id", userId));
            }
            audit.record(userId, "LOGIN_REFUSED", "app_user", userId.toString(), null, null, null);
            return Optional.empty();   // message générique côté API : pas de divulgation de la cause
        }
        jdbc.update("UPDATE app_user SET failed_logins = 0, locked_until = NULL WHERE id = :id", Map.of("id", userId));

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = now.plus(props.session().ttl());
        UUID sessionId = jdbc.queryForObject("""
                INSERT INTO auth_session(user_id, token_sha256, client_kind, expires_at)
                VALUES (:u, :h, :k, :e) RETURNING id""",
                new MapSqlParameterSource().addValue("u", userId).addValue("h", sha256(token))
                        .addValue("k", clientKind).addValue("e", Timestamp.from(expires)), UUID.class);
        audit.record(userId, "LOGIN", "auth_session", String.valueOf(sessionId), null, null, null);
        return resolve(token).map(user -> new LoginResult(token, expires, user));
    }

    /** Valide un jeton : session non révoquée, non expirée, utilisateur actif. Met à jour last_seen_at. */
    @Transactional
    public Optional<AuthenticatedUser> resolve(String token) {
        if (token == null || token.length() < 20 || token.length() > 128) return Optional.empty();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE auth_session s SET last_seen_at = now()
                  FROM app_user u
                 WHERE s.token_sha256 = :h AND s.revoked_at IS NULL AND s.expires_at > now()
                   AND u.id = s.user_id AND u.status = 'ACTIVE'
                RETURNING s.id AS session_id, u.id AS user_id, u.login, u.display_name, u.org_unit_id, u.must_change_password""",
                Map.of("h", sha256(token)));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = rows.get(0);
        UUID userId = (UUID) r.get("user_id");
        List<Grant> grants = jdbc.query("""
                SELECT role_code, scope_org_id, valid_from, valid_until, revoked_at
                  FROM user_role_grant WHERE user_id = :u""", Map.of("u", userId),
                (rs, i) -> new Grant(rs.getString("role_code"), rs.getString("scope_org_id"),
                        rs.getTimestamp("valid_from").toInstant(),
                        rs.getTimestamp("valid_until") == null ? null : rs.getTimestamp("valid_until").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()));
        return Optional.of(new AuthenticatedUser(userId, (UUID) r.get("session_id"), (String) r.get("login"),
                (String) r.get("display_name"), (UUID) r.get("org_unit_id"), grants, Boolean.TRUE.equals(r.get("must_change_password"))));
    }

    @Transactional
    public void logout(AuthenticatedUser user) {
        jdbc.update("UPDATE auth_session SET revoked_at = now(), revoke_reason = 'LOGOUT' WHERE id = :id AND revoked_at IS NULL",
                Map.of("id", user.sessionId()));
        audit.record(user.userId(), "LOGOUT", "auth_session", user.sessionId().toString(), null, null, null);
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
