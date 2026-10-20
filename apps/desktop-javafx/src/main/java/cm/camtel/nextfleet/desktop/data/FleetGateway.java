package cm.camtel.nextfleet.desktop.data;

import cm.camtel.nextfleet.desktop.data.Models.DashboardData;
import cm.camtel.nextfleet.desktop.data.Models.Page;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Models.VehicleDraft;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;

import java.util.UUID;

/**
 * Accès aux données du client bureau. Le client ne lit jamais la base : il passe par l'API (règle cardinale 3).
 * Deux implémentations : {@link HttpFleetGateway} (API réelle) et {@link DemoFleetGateway} (données fictives, badge DÉMO).
 */
public interface FleetGateway {

    /** Erreur présentable à l'utilisateur, avec nature (réseau, droits, session, validation, conflit). */
    final class GatewayException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public enum Kind { NETWORK, UNAUTHORIZED, FORBIDDEN, VALIDATION, CONFLICT, NOT_FOUND, SERVER }

        private final Kind kind;

        public GatewayException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    boolean isDemo();

    Session login(String login, char[] password);

    void logout();

    DashboardData dashboard();

    Page<Vehicle> vehicles(String query, VehicleStatus status, int page, int size);

    Vehicle vehicle(UUID id);

    Vehicle createVehicle(VehicleDraft draft);

    java.util.List<Models.Driver> drivers(String query);

    /** Échéances du périmètre, triées par date ; {@code thresholdDays} = seuil d'alerte approuvé. */
    java.util.List<Models.DueItem> dueDates(int thresholdDays);

    java.util.List<Models.NotificationItem> notifications();

    void markNotificationsRead(java.util.Collection<String> ids);

    /** Archivage métier d'un véhicule (RG-04) avec motif ; l'historique reste consultable. */
    Vehicle archiveVehicle(UUID id, long expectedVersion, String reason);

    /** Échéances d'un véhicule donné. */
    java.util.List<Models.DueItem> vehicleDueDates(UUID vehicleId);
}
