package cm.camtel.nextfleet.api.anomalies;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.identity.AccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Écarts détectés par les règles (NF-44) : consultation et instruction humaine. Aucun statut « fraude » : la conclusion
 * reste une décision humaine motivée, et chaque étape est conservée (historique en ajout seul).
 */
@RestController
public class AnomalyController {

    public record AnomalyView(UUID id, String ruleId, int ruleVersion, String severity, String dataQuality, String summary,
                              String evidence, String status, String registration, String assignedTo, Instant detectedAt,
                              List<ReviewView> reviews) { }

    public record ReviewView(String reviewer, String fromStatus, String toStatus, String comment, Instant at) { }

    public record ReviewRequest(@NotBlank String status, @NotBlank @Size(min = 5, max = 2000) String comment) { }

    static final Map<String, Set<String>> NEXT = Map.of(
            "A_VERIFIER", Set.of("EN_ANALYSE", "JUSTIFIE", "ERREUR_CORRIGEE"),
            "EN_ANALYSE", Set.of("JUSTIFIE", "ERREUR_CORRIGEE", "ANOMALIE_CONFIRMEE"),
            "JUSTIFIE", Set.of("EN_ANALYSE"),
            "ERREUR_CORRIGEE", Set.of("EN_ANALYSE"),
            "ANOMALIE_CONFIRMEE", Set.of("EN_ANALYSE"));

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;

    public AnomalyController(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/api/v1/anomalies")
    @Transactional(readOnly = true)
    public List<AnomalyView> list(@RequestParam(required = false) String status) {
        List<UUID> scope = access.scopeFor("anomaly:read");
        if (scope.isEmpty()) return List.of();
        List<AnomalyView> l = jdbc.query("""
                SELECT a.id, a.rule_id, a.rule_version, a.severity, a.data_quality, a.summary, a.evidence::text, a.status,
                       v.registration, u.display_name, a.detected_at
                  FROM anomaly a LEFT JOIN vehicle v ON v.id = a.vehicle_id LEFT JOIN app_user u ON u.id = a.assigned_to
                 WHERE a.org_unit_id IN (:s) AND (CAST(:st AS text) IS NULL OR a.status = :st)
                 ORDER BY CASE a.status WHEN 'A_VERIFIER' THEN 0 WHEN 'EN_ANALYSE' THEN 1 ELSE 2 END, a.detected_at DESC LIMIT 500""",
                new MapSqlParameterSource("s", scope).addValue("st", status), (rs, i) -> new AnomalyView(rs.getObject(1, UUID.class),
                        rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getTimestamp(11).toInstant(), new java.util.ArrayList<>()));
        if (l.isEmpty()) return l;
        Map<UUID, AnomalyView> byId = new java.util.HashMap<>();
        l.forEach(a -> byId.put(a.id(), a));
        jdbc.query("""
                SELECT r.anomaly_id, u.display_name, r.from_status, r.to_status, r.comment, r.reviewed_at FROM anomaly_review r
                  JOIN app_user u ON u.id = r.reviewer_id WHERE r.anomaly_id IN (:ids) ORDER BY r.reviewed_at""",
                new MapSqlParameterSource("ids", byId.keySet()), rs -> {
                    byId.get(rs.getObject(1, UUID.class)).reviews().add(new ReviewView(rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getTimestamp(6).toInstant()));
                });
        return l;
    }

    @PostMapping("/api/v1/anomalies/{id}/review")
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public Map<String, String> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest r) {
        List<Map<String, Object>> a = jdbc.queryForList("SELECT org_unit_id, status FROM anomaly WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id));
        if (a.isEmpty()) throw new NotFoundException("Écart introuvable.");
        access.require("anomaly:review", (UUID) a.get(0).get("org_unit_id"), "anomaly", id.toString());
        String from = (String) a.get(0).get("status");
        if (!NEXT.getOrDefault(from, Set.of()).contains(r.status())) {
            throw new IllegalArgumentException("Passage de " + from + " à " + r.status() + " non permis.");
        }
        UUID me = access.currentUser().userId();
        jdbc.update("UPDATE anomaly SET status = :st, assigned_to = coalesce(assigned_to, :me) WHERE id = :id",
                new MapSqlParameterSource("st", r.status()).addValue("me", me).addValue("id", id));
        jdbc.update("""
                INSERT INTO anomaly_review(anomaly_id, reviewer_id, from_status, to_status, comment) VALUES (:a, :me, :f, :t, :c)""",
                new MapSqlParameterSource("a", id).addValue("me", me).addValue("f", from).addValue("t", r.status()).addValue("c", r.comment().strip()));
        audit.record(me, "REVIEW", "anomaly", id.toString(), r.comment().strip(), Map.of("status", from), Map.of("status", r.status()));
        return Map.of("status", r.status());
    }
}
