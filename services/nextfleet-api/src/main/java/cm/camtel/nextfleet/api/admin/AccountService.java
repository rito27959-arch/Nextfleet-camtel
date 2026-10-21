package cm.camtel.nextfleet.api.admin;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cycle de vie des comptes (admin:users) et changement de mot de passe en libre-service.
 * <ul>
 *   <li>Création : mot de passe provisoire affiché UNE fois à l'administrateur, changement imposé à la première connexion.</li>
 *   <li>Rôles : attribués dans le périmètre de l'administrateur, jamais à soi-même (NF-02), retrait motivé et tracé.</li>
 *   <li>Réinitialisation : sessions ouvertes révoquées, nouveau mot de passe provisoire.</li>
 * </ul>
 */
@Service
public class AccountService {

    public record GrantRequest(String role, UUID scopeOrgId, Instant validUntil, boolean delegation) { }

    public record GrantView(UUID id, String role, String roleLabel, UUID scopeOrgId, String scopeName, Instant validFrom,
                            Instant validUntil, boolean delegation, Instant revokedAt, String grantedBy) { }

    public record Created(AdminService.UserView user, String temporaryPassword) { }

    public record RoleView(String code, String label, List<String> permissions) { }

    static final Set<String> ROLES = Set.of("FLEET_MANAGER", "DRIVER", "MAINTENANCE", "CONTROLLER", "ADMIN", "AUDITOR");
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final AdminService admin;

    public AccountService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit, PasswordEncoder encoder,
                          AdminService admin) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.encoder = encoder;
        this.admin = admin;
    }

    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        return jdbc.query("""
                SELECT r.code, r.label, coalesce(array_agg(rp.permission_code ORDER BY rp.permission_code)
                                                 FILTER (WHERE rp.permission_code IS NOT NULL), '{}')
                  FROM role r LEFT JOIN role_permission rp ON rp.role_code = r.code GROUP BY r.code, r.label ORDER BY r.label""",
                new MapSqlParameterSource(), (rs, i) -> new RoleView(rs.getString(1), rs.getString(2),
                        List.of((String[]) rs.getArray(3).getArray())));
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public Created create(String login, String displayName, String email, String phone, UUID orgUnitId,
                          List<GrantRequest> grants, UUID driverId) {
        AuthenticatedUser me = access.currentUser();
        access.require("admin:users", orgUnitId, "app_user", "new");
        String l = login.strip();
        if (!l.matches("^[A-Za-z0-9][A-Za-z0-9._@-]{2,63}$")) {
            throw new IllegalArgumentException("Identifiant invalide : 3 à 64 caractères, lettres, chiffres, point, tiret, @.");
        }
        Integer dup = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE login_normalized = lower(btrim(:l))",
                new MapSqlParameterSource("l", l), Integer.class);
        if (dup != null && dup > 0) throw new IllegalArgumentException("Cet identifiant est déjà utilisé.");
        String temp = temporaryPassword();
        UUID id = jdbc.queryForObject("""
                INSERT INTO app_user(login, display_name, email, phone, org_unit_id, password_hash, must_change_password)
                VALUES (:l, :n, :e, :p, :o, :h, true) RETURNING id""",
                new MapSqlParameterSource("l", l).addValue("n", displayName.strip()).addValue("e", blank(email))
                        .addValue("p", blank(phone)).addValue("o", orgUnitId).addValue("h", encoder.encode(temp)), UUID.class);
        audit.record(me.userId(), "CREATE", "app_user", id.toString(), null, null,
                Map.of("login", l, "displayName", displayName.strip(), "orgUnitId", orgUnitId.toString()));
        for (GrantRequest g : grants == null ? List.<GrantRequest>of() : grants) grant(id, g, "Création du compte");
        if (driverId != null) linkDriver(id, driverId);
        return new Created(user(id), temp);
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public GrantView grant(UUID userId, GrantRequest g, String reason) {
        AuthenticatedUser me = access.currentUser();
        if (me.userId().equals(userId)) throw new IllegalArgumentException("Vous ne pouvez pas vous attribuer un rôle (NF-02).");
        if (g.role() == null || !ROLES.contains(g.role())) throw new IllegalArgumentException("Rôle inconnu.");
        UUID userOrg = orgOf(userId);
        access.require("admin:users", userOrg, "app_user", userId.toString());
        UUID scope = g.scopeOrgId() == null ? userOrg : g.scopeOrgId();
        access.require("admin:users", scope, "user_role_grant", "new");
        if (g.delegation() && g.validUntil() == null) throw new IllegalArgumentException("Une délégation a obligatoirement une date de fin.");
        if (g.validUntil() != null && !g.validUntil().isAfter(Instant.now())) throw new IllegalArgumentException("La date de fin doit être future.");
        Integer same = jdbc.queryForObject("""
                SELECT count(*) FROM user_role_grant WHERE user_id = :u AND role_code = :r AND scope_org_id = :s
                   AND revoked_at IS NULL AND (valid_until IS NULL OR valid_until > now())""",
                new MapSqlParameterSource("u", userId).addValue("r", g.role()).addValue("s", scope), Integer.class);
        if (same != null && same > 0) throw new IllegalArgumentException("Ce rôle est déjà attribué sur cette entité.");
        UUID id = jdbc.queryForObject("""
                INSERT INTO user_role_grant(user_id, role_code, scope_org_id, is_delegation, valid_until, granted_by)
                VALUES (:u, :r, :s, :d, :until, :by) RETURNING id""",
                new MapSqlParameterSource("u", userId).addValue("r", g.role()).addValue("s", scope).addValue("d", g.delegation())
                        .addValue("until", g.validUntil() == null ? null : Timestamp.from(g.validUntil())).addValue("by", me.userId()),
                UUID.class);
        audit.record(me.userId(), "GRANT_ROLE", "app_user", userId.toString(), reason, null,
                Map.of("role", g.role(), "scopeOrgId", scope.toString(), "grantId", id.toString(), "delegation", g.delegation()));
        return grants(userId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public void revoke(UUID userId, UUID grantId, String reason) {
        AuthenticatedUser me = access.currentUser();
        if (reason == null || reason.strip().length() < 5) throw new IllegalArgumentException("Motif obligatoire (5 caractères au moins).");
        access.require("admin:users", orgOf(userId), "app_user", userId.toString());
        if (me.userId().equals(userId)) throw new IllegalArgumentException("Vous ne pouvez pas retirer vos propres rôles.");
        int n = jdbc.update("UPDATE user_role_grant SET revoked_at = now() WHERE id = :g AND user_id = :u AND revoked_at IS NULL",
                new MapSqlParameterSource("g", grantId).addValue("u", userId));
        if (n == 0) throw new NotFoundException("Attribution introuvable ou déjà retirée.");
        audit.record(me.userId(), "REVOKE_ROLE", "app_user", userId.toString(), reason.strip(), Map.of("grantId", grantId.toString()), null);
    }

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public List<GrantView> grants(UUID userId) {
        access.require("admin:users", orgOf(userId), "app_user", userId.toString());
        return jdbc.query("""
                SELECT g.id, g.role_code, r.label, g.scope_org_id, o.name, g.valid_from, g.valid_until, g.is_delegation, g.revoked_at,
                       b.display_name
                  FROM user_role_grant g JOIN role r ON r.code = g.role_code JOIN organisation_unit o ON o.id = g.scope_org_id
                  LEFT JOIN app_user b ON b.id = g.granted_by
                 WHERE g.user_id = :u ORDER BY g.revoked_at NULLS FIRST, g.valid_from DESC""",
                new MapSqlParameterSource("u", userId), (rs, i) -> new GrantView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5), ts(rs.getTimestamp(6)), ts(rs.getTimestamp(7)),
                        rs.getBoolean(8), ts(rs.getTimestamp(9)), rs.getString(10)));
    }

    /** Nouveau mot de passe provisoire ; toutes les sessions du compte sont révoquées. */
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public String resetPassword(UUID userId, String reason) {
        AuthenticatedUser me = access.currentUser();
        if (reason == null || reason.strip().length() < 5) throw new IllegalArgumentException("Motif obligatoire (5 caractères au moins).");
        access.require("admin:users", orgOf(userId), "app_user", userId.toString());
        if (me.userId().equals(userId)) throw new IllegalArgumentException("Utilisez « Changer mon mot de passe » pour votre propre compte.");
        String temp = temporaryPassword();
        jdbc.update("""
                UPDATE app_user SET password_hash = :h, must_change_password = true, failed_logins = 0, locked_until = NULL,
                       version = version + 1, updated_at = now() WHERE id = :u""",
                new MapSqlParameterSource("h", encoder.encode(temp)).addValue("u", userId));
        jdbc.update("UPDATE auth_session SET revoked_at = now(), revoke_reason = 'PASSWORD_RESET' WHERE user_id = :u AND revoked_at IS NULL",
                new MapSqlParameterSource("u", userId));
        audit.record(me.userId(), "PASSWORD_RESET", "app_user", userId.toString(), reason.strip(), null, null);
        return temp;
    }

    /** Changement par l'utilisateur lui-même : ancien mot de passe exigé ; les autres sessions sont fermées. */
    @Transactional
    public void changeOwnPassword(String current, String next) {
        AuthenticatedUser me = access.currentUser();
        Map<String, Object> r = jdbc.queryForMap("SELECT password_hash, login FROM app_user WHERE id = :u", new MapSqlParameterSource("u", me.userId()));
        String hash = (String) r.get("password_hash");
        if (hash == null || !encoder.matches(current, hash)) {
            audit.record(me.userId(), "PASSWORD_CHANGE_REFUSED", "app_user", me.userId().toString(), null, null, null);
            throw new IllegalArgumentException("Mot de passe actuel incorrect.");
        }
        checkPolicy(next, (String) r.get("login"));
        if (encoder.matches(next, hash)) throw new IllegalArgumentException("Le nouveau mot de passe doit être différent de l'actuel.");
        jdbc.update("""
                UPDATE app_user SET password_hash = :h, must_change_password = false, password_changed_at = now(),
                       version = version + 1, updated_at = now() WHERE id = :u""",
                new MapSqlParameterSource("h", encoder.encode(next)).addValue("u", me.userId()));
        jdbc.update("""
                UPDATE auth_session SET revoked_at = now(), revoke_reason = 'PASSWORD_CHANGE'
                 WHERE user_id = :u AND id <> :s AND revoked_at IS NULL""",
                new MapSqlParameterSource("u", me.userId()).addValue("s", me.sessionId()));
        audit.record(me.userId(), "PASSWORD_CHANGE", "app_user", me.userId().toString(), null, null, null);
    }

    /** 12 caractères au moins, lettres ET chiffres ou symboles, différent de l'identifiant. */
    static void checkPolicy(String pwd, String login) {
        if (pwd == null || pwd.length() < 12) throw new IllegalArgumentException("Le mot de passe doit contenir au moins 12 caractères.");
        if (pwd.length() > 128) throw new IllegalArgumentException("Le mot de passe est trop long (128 caractères maximum).");
        boolean letter = pwd.chars().anyMatch(Character::isLetter);
        boolean other = pwd.chars().anyMatch(c -> !Character.isLetter(c));
        if (!letter || !other) throw new IllegalArgumentException("Le mot de passe doit mêler lettres et chiffres ou symboles.");
        if (login != null && pwd.toLowerCase().contains(login.toLowerCase())) {
            throw new IllegalArgumentException("Le mot de passe ne doit pas contenir l'identifiant.");
        }
    }

    private void linkDriver(UUID userId, UUID driverId) {
        List<Map<String, Object>> d = jdbc.queryForList("SELECT org_unit_id, user_id FROM driver WHERE id = :d", new MapSqlParameterSource("d", driverId));
        if (d.isEmpty()) throw new IllegalArgumentException("Fiche conducteur introuvable.");
        if (d.get(0).get("user_id") != null) throw new IllegalArgumentException("Cette fiche conducteur est déjà liée à un compte.");
        access.require("admin:users", (UUID) d.get(0).get("org_unit_id"), "driver", driverId.toString());
        jdbc.update("UPDATE driver SET user_id = :u, version = version + 1 WHERE id = :d", new MapSqlParameterSource("u", userId).addValue("d", driverId));
        audit.record(access.currentUser().userId(), "LINK_DRIVER", "app_user", userId.toString(), null, null, Map.of("driverId", driverId.toString()));
    }

    private AdminService.UserView user(UUID id) {
        return admin.users(null).stream().filter(u -> u.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Compte introuvable."));
    }

    private UUID orgOf(UUID userId) {
        List<UUID> org = jdbc.queryForList("SELECT org_unit_id FROM app_user WHERE id = :id", new MapSqlParameterSource("id", userId), UUID.class);
        if (org.isEmpty()) throw new NotFoundException("Compte introuvable.");
        return org.get(0);
    }

    static String temporaryPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();   // ex. Kp7d-Xq2m-… : 16 caractères tirés + tirets, au-delà de la politique de 12
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
