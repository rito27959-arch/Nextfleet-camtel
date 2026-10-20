package cm.camtel.nextfleet.domain.security;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Décision d'accès RBAC + périmètre organisationnel (SEC-02, REC-SEC-01).
 * Refus par défaut : une permission n'est accordée que si un rôle actif la porte ET que l'objet
 * appartient à l'entité du rôle ou à l'une de ses sous-entités.
 */
public final class AccessPolicy {

    /** Rôle attribué sur une entité ; délégations bornées dans le temps. */
    public record Grant(String roleCode, String scopeOrgId, Instant validFrom, Instant validUntil, Instant revokedAt) {
        boolean activeAt(Instant t) {
            return revokedAt == null && !t.isBefore(validFrom) && (validUntil == null || t.isBefore(validUntil));
        }
    }

    private final Map<String, Set<String>> permissionsByRole;
    private final Map<String, String> parentByOrg;

    /**
     * @param permissionsByRole matrice rôle → permissions (table {@code role_permission})
     * @param parentByOrg       arbre des entités : entité → parent ({@code null} pour la racine)
     */
    public AccessPolicy(Map<String, Set<String>> permissionsByRole, Map<String, String> parentByOrg) {
        this.permissionsByRole = Map.copyOf(permissionsByRole);
        this.parentByOrg = new java.util.HashMap<>(parentByOrg);
    }

    public boolean isAllowed(Collection<Grant> grants, String permission, String objectOrgId, Instant now) {
        Objects.requireNonNull(permission);
        if (objectOrgId == null) return false;
        for (Grant g : grants) {
            if (!g.activeAt(now)) continue;
            if (!permissionsByRole.getOrDefault(g.roleCode(), Set.of()).contains(permission)) continue;
            if (isWithin(objectOrgId, g.scopeOrgId())) return true;
        }
        return false;
    }

    /** {@code true} si {@code org} est {@code scope} ou l'un de ses descendants (cycles protégés). */
    public boolean isWithin(String org, String scope) {
        String cur = org;
        for (int depth = 0; cur != null && depth < 64; depth++) {
            if (cur.equals(scope)) return true;
            cur = parentByOrg.get(cur);
        }
        return false;
    }
}
