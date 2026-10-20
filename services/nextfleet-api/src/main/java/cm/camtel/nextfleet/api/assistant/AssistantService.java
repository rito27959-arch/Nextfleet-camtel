package cm.camtel.nextfleet.api.assistant;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import cm.camtel.nextfleet.domain.assistant.AssistantActivation;
import cm.camtel.nextfleet.domain.assistant.FleetAssistant;
import cm.camtel.nextfleet.domain.security.Permission;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Assistant en LECTURE SEULE : moteur de règles du domaine (FleetAssistant), faits lus en base dans le périmètre,
 * état d'activation lu dans assistant_activation (désactivé par défaut), chaque interrogation journalisée
 * dans assistant_run (table en ajout seul, gardée par trigger).
 */
@Service
public class AssistantService {

    public record ActivationView(boolean enabled, String securityReviewRef, String approvedBy, String decidedBy,
                                 java.time.Instant decidedAt, String reason) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public AssistantService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    Set<Permission> permissionsOf(UUID user) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        jdbc.query("""
                SELECT DISTINCT rp.permission_code FROM user_role_grant g JOIN role_permission rp ON rp.role_code = g.role_code
                 WHERE g.user_id = :u AND g.revoked_at IS NULL AND g.valid_from <= now() AND (g.valid_until IS NULL OR g.valid_until > now())""",
                new MapSqlParameterSource("u", user), rs -> {
                    Permission.ofCode(rs.getString(1)).ifPresent(out::add);
                });
        return out;
    }

    @Transactional(readOnly = true)
    public ActivationView activation() {
        access.currentUser();
        return toView(state());
    }

    private AssistantActivation.State state() {
        List<AssistantActivation.State> s = jdbc.query("""
                SELECT a.enabled, a.security_review_ref, a.approved_by_name, u.display_name, a.decided_at, a.reason
                  FROM assistant_activation a JOIN app_user u ON u.id = a.decided_by ORDER BY a.decided_at DESC, a.id DESC LIMIT 1""",
                new MapSqlParameterSource(), (rs, i) -> new AssistantActivation.State(rs.getBoolean(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getString(6)));
        return s.isEmpty() ? AssistantActivation.State.disabledByDefault() : s.get(0);
    }

    @Transactional
    public FleetAssistant.Answer ask(String question) {
        AuthenticatedUser u = access.currentUser();
        Set<Permission> perms = permissionsOf(u.userId());
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Africa/Douala")));
        var facts = new JdbcFleetFacts(jdbc, access.scopeFor("vehicle:read"), access.scopeFor("cost:read"),
                union(access.scopeFor("gps:read"), access.scopeFor("mission:manage")), today);
        FleetAssistant assistant = new FleetAssistant(run -> jdbc.update("""
                INSERT INTO assistant_run(user_id, question, intent, refused, refusal_reason, source_refs, engine)
                VALUES (:u, :q, :intent, :refused, :reason, CAST(:refs AS text[]), :engine)""",
                new MapSqlParameterSource("u", u.userId()).addValue("q", run.question().length() > 2000 ? run.question().substring(0, 2000)
                                : run.question().isEmpty() ? "(vide)" : run.question())
                        .addValue("intent", run.intent().name()).addValue("refused", run.refused())
                        .addValue("reason", run.refusalReason()).addValue("refs", pgArray(run.sourceRefs()))
                        .addValue("engine", FleetAssistant.ENGINE)));
        return assistant.ask(u.userId().toString(), perms, state(), facts, question, today);
    }

    /** Décision d'activation (admin:settings) : signataire distinct, référence de validation ; conservée en ajout seul. */
    @Transactional
    public ActivationView decide(boolean enable, String securityReviewRef, String approvedBy, String reason) {
        AuthenticatedUser u = access.currentUser();
        AssistantActivation.State s;
        try {
            s = AssistantActivation.decide(enable, securityReviewRef, approvedBy, reason, u.displayName(), permissionsOf(u.userId()), clock.instant());
        } catch (AssistantActivation.Refused e) {
            if (!permissionsOf(u.userId()).contains(Permission.ADMIN_SETTINGS)) {
                audit.record(u.userId(), "ACCESS_DENIED", "assistant_activation", "-", "admin:settings", null, null);
                throw new org.springframework.security.access.AccessDeniedException(e.getMessage());
            }
            throw new IllegalArgumentException(e.getMessage());
        }
        jdbc.update("""
                INSERT INTO assistant_activation(enabled, security_review_ref, approved_by_name, decided_by, reason)
                VALUES (:e, :ref, :by, :me, :reason)""",
                new MapSqlParameterSource("e", s.enabled()).addValue("ref", s.securityReviewRef()).addValue("by", s.approvedBy())
                        .addValue("me", u.userId()).addValue("reason", s.reason()));
        audit.record(u.userId(), enable ? "ASSISTANT_ENABLED" : "ASSISTANT_DISABLED", "assistant_activation", "-", s.reason(), null,
                s.securityReviewRef());
        return toView(s);
    }

    private static ActivationView toView(AssistantActivation.State s) {
        return new ActivationView(s.enabled(), s.securityReviewRef(), s.approvedBy(), s.decidedBy(), s.decidedAt(), s.reason());
    }

    private static List<UUID> union(List<UUID> a, List<UUID> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().toList();
    }

    /** Littéral de tableau PostgreSQL (références de sources : caractères sûrs uniquement). */
    static String pgArray(List<String> refs) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(refs.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return b.append('}').toString();
    }

}
