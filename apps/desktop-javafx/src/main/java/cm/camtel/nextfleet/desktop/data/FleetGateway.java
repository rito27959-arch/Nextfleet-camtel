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

    // ------------------------------------------------------------------ lot 2 : missions et GPS
    // Mode serveur : HttpFleetGateway appelle l'API du lot 2 ; les valeurs par défaut ci-dessous ne servent qu'aux
    // passerelles qui n'implémentent pas encore une fonction (le client le dit au lieu de simuler).

    default java.util.List<Ops.Mission> missions() {
        throw notYet("Les missions");
    }

    /** Positions ACCEPTÉES de la mission (jamais hors période de mission). */
    default java.util.List<cm.camtel.nextfleet.domain.gps.GpsFix> missionTrack(UUID missionId) {
        throw notYet("Les trajets");
    }

    /** Démarrage par le conducteur affecté, après information explicite (notice GPS versionnée). */
    default Ops.Mission startMission(UUID missionId, String noticeVersion) {
        throw notYet("Le démarrage de mission");
    }

    default Ops.Mission finishMission(UUID missionId) {
        throw notYet("La clôture de mission");
    }

    // ------------------------------------------------------------------ suivi GPS en direct

    default java.util.List<Ops.VehicleLive> fleetLive() {
        throw notYet("Le suivi GPS en direct");
    }

    /**
     * Abonnement aux positions en direct. Par défaut : relecture de la carte de flotte toutes les 5 s (mode
     * démonstration et serveurs sans canal temps réel) ; la passerelle HTTP utilise le WebSocket du serveur.
     * {@code onConnection} reçoit l'état du canal (vrai = connecté). Fermer l'objet rendu arrête l'abonnement.
     */
    default AutoCloseable subscribeLive(java.util.function.Consumer<Ops.LiveEvent> onEvent,
                                        java.util.function.Consumer<Boolean> onConnection) {
        java.util.concurrent.ScheduledExecutorService ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nf-live-poll");
            t.setDaemon(true);
            return t;
        });
        java.util.Map<String, java.time.Instant> last = new java.util.concurrent.ConcurrentHashMap<>();
        ex.scheduleWithFixedDelay(() -> {
            try {
                for (Ops.VehicleLive v : fleetLive()) {
                    java.time.Instant prev = last.put(v.vehicleId(), v.recordedAt());
                    if (prev == null || v.recordedAt().isAfter(prev)) {
                        onEvent.accept(new Ops.LiveEvent(v.vehicleId(), v.registration(), v.missionRef(), v.lat(), v.lon(),
                                v.speedKmh(), v.recordedAt()));
                    }
                }
                onConnection.accept(true);
            } catch (RuntimeException e) {
                onConnection.accept(false);
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        return ex::shutdownNow;
    }

    // ------------------------------------------------------------------ lot 2 : messagerie

    default java.util.List<Ops.Conversation> conversations() {
        throw notYet("La messagerie");
    }

    default java.util.List<Ops.ChatLine> messages(String conversationId) {
        throw notYet("La messagerie");
    }

    /** Envoi idempotent : même {@code clientMessageId} = même message, jamais de doublon. */
    default Ops.ChatLine sendMessage(String conversationId, String clientMessageId, String body) {
        throw notYet("La messagerie");
    }

    default void markConversationRead(String conversationId) {
        throw notYet("La messagerie");
    }

    // ------------------------------------------------------------------ lot 2 : assistant (lecture seule)

    default cm.camtel.nextfleet.domain.assistant.AssistantActivation.State assistantState() {
        throw notYet("L'assistant");
    }

    default cm.camtel.nextfleet.domain.assistant.FleetAssistant.Answer askAssistant(String question) {
        throw notYet("L'assistant");
    }

    default cm.camtel.nextfleet.domain.assistant.AssistantActivation.State decideAssistant(boolean enable, String reviewRef,
                                                                                          String approvedBy, String reason) {
        throw notYet("L'activation de l'assistant");
    }

    default java.util.List<cm.camtel.nextfleet.domain.assistant.AssistantActivation.State> assistantDecisions() {
        throw notYet("L'historique des décisions");
    }

    // ------------------------------------------------------------------ lot 2 : administration et audit

    default java.util.List<Ops.UserAccount> users() {
        throw notYet("La gestion des comptes");
    }

    default java.util.List<Ops.AuditEntry> auditTrail() {
        throw notYet("Le journal d'audit");
    }

    /** Premier événement dont l'empreinte ne correspond pas (-1 si la chaîne est intègre). */
    default long auditFirstBroken(java.util.List<Ops.AuditEntry> shown) {
        return AuditChain.firstBroken(shown);
    }

    static GatewayException notYet(String what) {
        return new GatewayException(GatewayException.Kind.SERVER, what + " n'est pas disponible avec ce serveur. "
                + "Cette fonction reste consultable en mode démonstration.");
    }
}
