package cm.camtel.nextfleet.api.identity;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.domain.security.AccessPolicy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Point unique de décision d'accès : rôle + périmètre organisationnel, refus par défaut, refus journalisé
 * sans fuite d'information (REC-SEC-01). Les référentiels sont relus à chaque décision pour le pilote ;
 * un cache invalidé par événement sera ajouté si la charge le justifie.
 */
@Service
public class AccessService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public AccessService(NamedParameterJdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public AuthenticatedUser currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof AuthenticatedUser u)) {
            throw new AccessDeniedException("Non authentifié");
        }
        return u;
    }

    /** Vérifie la permission sur un objet rattaché à {@code objectOrgId} ; lève 403 et journalise sinon. */
    public void require(String permission, UUID objectOrgId, String objectType, String objectId) {
        AuthenticatedUser u = currentUser();
        if (!policy().isAllowed(u.grants(), permission, objectOrgId == null ? null : objectOrgId.toString(), clock.instant())) {
            audit.record(u.userId(), "ACCESS_DENIED", objectType, objectId, permission, null, null);
            throw new AccessDeniedException("Accès refusé");
        }
    }

    /** Entités (identifiants) sur lesquelles l'utilisateur détient la permission : filtre des listes. */
    public List<UUID> scopeFor(String permission) {
        AuthenticatedUser u = currentUser();
        AccessPolicy p = policy();
        List<UUID> all = jdbc.queryForList("SELECT id FROM organisation_unit", Map.of(), UUID.class);
        return all.stream().filter(org -> p.isAllowed(u.grants(), permission, org.toString(), clock.instant())).toList();
    }

    private AccessPolicy policy() {
        Map<String, Set<String>> perms = new HashMap<>();
        jdbc.query("SELECT role_code, permission_code FROM role_permission", rs -> {
            perms.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
        });
        Map<String, String> tree = new HashMap<>();
        jdbc.query("SELECT id::text, parent_id::text FROM organisation_unit", rs -> {
            tree.put(rs.getString(1), rs.getString(2));
        });
        return new AccessPolicy(perms, tree);
    }
}
