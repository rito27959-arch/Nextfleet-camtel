package cm.camtel.nextfleet.domain.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static cm.camtel.nextfleet.domain.security.Permission.*;

/**
 * Les six profils de la matrice des habilitations du cahier V4 (chap. 03).
 * Règles structurantes : aucune auto-approbation ; l'administrateur technique n'a aucun pouvoir métier implicite ;
 * l'auditeur ne modifie rien ; le contrôleur est distinct du demandeur.
 * La matrice est la copie exacte des tables {@code role_permission} (V1 + V5), vérifiée par test.
 */
public enum Role {
    FLEET_MANAGER("Gestionnaire flotte", "Crée et corrige dans son périmètre ; valide selon délégation nominative.",
            EnumSet.of(VEHICLE_READ, VEHICLE_WRITE, VEHICLE_ARCHIVE, DRIVER_READ, DRIVER_WRITE, FUEL_ORDER_CREATE,
                    FUEL_ORDER_APPROVE, MISSION_MANAGE, GPS_READ, CHAT_USE, ANOMALY_READ, COST_READ, REPORT_EXPORT,
                    ASSISTANT_USE)),
    DRIVER("Agent ou conducteur", "Saisit les opérations qui lui sont affectées ; profil personnel seulement.",
            EnumSet.of(VEHICLE_READ, FUEL_ORDER_CREATE, FUEL_FILL_CREATE, MISSION_DRIVE, CHAT_USE)),
    MAINTENANCE("Maintenance", "Interventions et contrôles affectés ; validation technique distincte.",
            EnumSet.of(VEHICLE_READ, CHAT_USE, MAINTENANCE_WRITE, MAINTENANCE_VALIDATE)),
    CONTROLLER("Contrôleur", "Lecture, investigation et export autorisé ; distinct du demandeur.",
            EnumSet.of(VEHICLE_READ, DRIVER_READ, ANOMALY_READ, ANOMALY_REVIEW, FUEL_ORDER_APPROVE, GPS_READ, CHAT_USE,
                    COST_READ, REPORT_EXPORT, ASSISTANT_USE)),
    ADMIN("Administrateur technique", "Comptes et configuration ; aucun pouvoir métier implicite.",
            EnumSet.of(ADMIN_USERS, ADMIN_SETTINGS)),
    AUDITOR("Auditeur", "Lecture et exports dans le mandat ; aucune modification, aucun changement de droits.",
            EnumSet.of(VEHICLE_READ, DRIVER_READ, ANOMALY_READ, AUDIT_READ, COST_READ, REPORT_EXPORT));

    public final String label;
    public final String summary;
    private final Set<Permission> permissions;

    Role(String label, String summary, Set<Permission> permissions) {
        this.label = label;
        this.summary = summary;
        this.permissions = permissions;
    }

    public Set<Permission> permissions() {
        return EnumSet.copyOf(permissions);
    }

    public boolean has(Permission p) {
        return permissions.contains(p);
    }

    public static Optional<Role> ofCode(String code) {
        return Arrays.stream(values()).filter(r -> r.name().equals(code)).findFirst();
    }

    /** Union des permissions des rôles actifs ; un code inconnu n'accorde rien (refus par défaut). */
    public static Set<Permission> union(Collection<String> roleCodes) {
        Set<Permission> s = EnumSet.noneOf(Permission.class);
        for (String c : roleCodes) ofCode(c).ifPresent(r -> s.addAll(r.permissions));
        return s;
    }

    /** Matrice au format attendu par {@link AccessPolicy}. */
    public static Map<String, Set<String>> matrix() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (Role r : values()) {
            m.put(r.name(), r.permissions.stream().map(p -> p.code).collect(Collectors.toUnmodifiableSet()));
        }
        return m;
    }
}
