package cm.camtel.nextfleet.mobile.data;

import cm.camtel.nextfleet.domain.security.Permission;
import cm.camtel.nextfleet.domain.security.Role;

import java.util.List;
import java.util.Set;

/** Session ouverte : jeton opaque (serveur) ou session de démonstration. Les permissions suivent la matrice du noyau. */
public final class Session {

    public final String token;
    public final String login;
    public final String displayName;
    public final List<String> roles;
    public final boolean demo;
    /** Mot de passe provisoire : seul le changement de mot de passe est permis tant qu'il n'est pas remplacé. */
    public volatile boolean mustChangePassword;

    public Session(String token, String login, String displayName, List<String> roles, boolean demo) {
        this.token = token;
        this.login = login;
        this.displayName = displayName;
        this.roles = List.copyOf(roles);
        this.demo = demo;
    }

    public Set<Permission> permissions() {
        return Role.union(roles);
    }

    public boolean can(Permission p) {
        return permissions().contains(p);
    }

    public Role primaryRole() {
        return roles.isEmpty() ? null : Role.ofCode(roles.get(0)).orElse(null);
    }
}
