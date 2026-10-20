package cm.camtel.nextfleet.domain.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * Permissions applicatives. Les codes sont ceux de la table {@code permission} (migrations V1 et V5) ;
 * {@code RoleMatrixTest} vérifie que la liste Java et la base ne divergent pas.
 */
public enum Permission {
    VEHICLE_READ("vehicle:read", "Consulter le parc"),
    VEHICLE_WRITE("vehicle:write", "Créer et corriger un véhicule"),
    VEHICLE_ARCHIVE("vehicle:archive", "Archiver un véhicule"),
    DRIVER_READ("driver:read", "Consulter les conducteurs"),
    DRIVER_WRITE("driver:write", "Gérer les conducteurs"),
    FUEL_ORDER_CREATE("fuel_order:create", "Demander un bon carburant"),
    FUEL_ORDER_APPROVE("fuel_order:approve", "Valider un bon carburant"),
    FUEL_FILL_CREATE("fuel_fill:create", "Saisir un plein"),
    ANOMALY_READ("anomaly:read", "Consulter les anomalies"),
    ANOMALY_REVIEW("anomaly:review", "Instruire une anomalie"),
    MISSION_MANAGE("mission:manage", "Créer et autoriser une mission"),
    MISSION_DRIVE("mission:drive", "Exécuter une mission affectée"),
    GPS_READ("gps:read", "Consulter les trajets"),
    CHAT_USE("chat:use", "Utiliser la messagerie"),
    AUDIT_READ("audit:read", "Consulter et exporter le journal"),
    ADMIN_USERS("admin:users", "Administrer les comptes"),
    ADMIN_SETTINGS("admin:settings", "Administrer les paramètres"),
    // V5
    MAINTENANCE_WRITE("maintenance:write", "Saisir interventions et contrôles"),
    MAINTENANCE_VALIDATE("maintenance:validate", "Valider techniquement une intervention"),
    COST_READ("cost:read", "Consulter coûts et dépenses"),
    REPORT_EXPORT("report:export", "Exporter des rapports"),
    ASSISTANT_USE("assistant:use", "Interroger l'assistant (lecture seule)");

    public final String code;
    public final String label;

    Permission(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static Optional<Permission> ofCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }
}
