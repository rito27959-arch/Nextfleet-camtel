package cm.camtel.nextfleet.api.orgs;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.identity.AccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Entités organisationnelles (directions, agences, sites). La liste est filtrée : chacun ne voit que les entités où il
 * détient la permission demandée (par défaut, n'importe laquelle de ses permissions). Création réservée à admin:settings.
 */
@RestController
public class OrgController {

    public record OrgUnitView(UUID id, String code, String name, UUID parentId, String parentName) { }

    public record OrgUnitCreate(@NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{1,29}$") String code,
                                @NotBlank @Size(max = 120) String name,
                                UUID parentId) { }

    private static final List<String> ALL = List.of("vehicle:read", "vehicle:write", "driver:read", "driver:write",
            "fuel_order:create", "fuel_order:approve", "fuel_fill:create", "mission:manage", "mission:drive", "gps:read",
            "maintenance:write", "cost:read", "report:export", "audit:read", "admin:users", "admin:settings", "chat:use",
            "anomaly:read");

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;

    public OrgController(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/api/v1/org-units")
    @Transactional(readOnly = true)
    public List<OrgUnitView> list(@RequestParam(required = false) @Pattern(regexp = "^[a-z_]+:[a-z_]+$") String permission) {
        Set<UUID> scope = new LinkedHashSet<>();
        for (String p : permission == null ? ALL : List.of(permission)) scope.addAll(access.scopeFor(p));
        if (scope.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT o.id, o.code, o.name, o.parent_id, p.name FROM organisation_unit o
                  LEFT JOIN organisation_unit p ON p.id = o.parent_id
                 WHERE o.id IN (:s) ORDER BY coalesce(p.name, o.name), o.parent_id NULLS FIRST, o.name""",
                new MapSqlParameterSource("s", scope), (rs, i) -> new OrgUnitView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5)));
    }

    @PostMapping("/api/v1/admin/org-units")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public OrgUnitView create(@Valid @RequestBody OrgUnitCreate c) {
        UUID parent = c.parentId();
        if (parent == null) {
            List<UUID> roots = jdbc.queryForList("SELECT id FROM organisation_unit WHERE parent_id IS NULL ORDER BY created_at LIMIT 1",
                    new MapSqlParameterSource(), UUID.class);
            if (roots.isEmpty()) throw new IllegalStateException("Aucune entité racine : instance non amorcée.");
            parent = roots.get(0);
        }
        // une sous-entité ne peut être créée que sous une entité administrée
        access.require("admin:settings", parent, "organisation_unit", "new");
        UUID id = jdbc.queryForObject("INSERT INTO organisation_unit(code, name, parent_id) VALUES (:c, :n, :p) RETURNING id",
                new MapSqlParameterSource("c", c.code()).addValue("n", c.name().strip()).addValue("p", parent), UUID.class);
        audit.record(access.currentUser().userId(), "CREATE", "organisation_unit", id.toString(), null, null, c);
        return jdbc.queryForObject("""
                SELECT o.id, o.code, o.name, o.parent_id, p.name FROM organisation_unit o
                  LEFT JOIN organisation_unit p ON p.id = o.parent_id WHERE o.id = :id""",
                new MapSqlParameterSource("id", id), (rs, i) -> new OrgUnitView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5)));
    }
}
