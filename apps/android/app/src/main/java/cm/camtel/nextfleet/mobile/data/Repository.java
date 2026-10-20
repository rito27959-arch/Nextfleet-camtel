package cm.camtel.nextfleet.mobile.data;

import cm.camtel.nextfleet.mobile.data.Models.ChatLine;
import cm.camtel.nextfleet.mobile.data.Models.Conversation;
import cm.camtel.nextfleet.mobile.data.Models.Mission;
import cm.camtel.nextfleet.mobile.data.Models.StartResult;

import java.util.List;

/** Accès aux données : toutes les méthodes sont bloquantes (appel hors du fil d'interface). */
public interface Repository {

    final class RepositoryException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public enum Kind { NETWORK, UNAUTHORIZED, FORBIDDEN, VALIDATION, CONFLICT, NOT_FOUND, SERVER }

        public final Kind kind;

        public RepositoryException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    boolean isDemo();

    Session login(String login, char[] password);

    void logout();

    List<Mission> missions();

    /** Démarrage par le conducteur affecté, après information sur la localisation (notice versionnée). */
    StartResult start(String missionId, String noticeVersion, String deviceId);

    Mission finish(String missionId);

    List<Conversation> conversations();

    List<ChatLine> messages(String conversationId);

    /** Envoi idempotent : même clientMessageId = même message. */
    ChatLine send(String conversationId, String clientMessageId, String body);

    void markRead(String conversationId);

    /** Carte de flotte : véhicules en mission du périmètre (ou sa propre mission pour un conducteur). */
    List<Models.VehicleLive> fleetLive();

    /** Abonnement temps réel ; {@code close()} l'arrête. Par défaut : relecture de la carte toutes les 5 s. */
    default AutoCloseable live(java.util.function.Consumer<Models.LiveEvent> onEvent, java.util.function.Consumer<Boolean> onConnection) {
        java.util.concurrent.ScheduledExecutorService ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.Map<String, java.time.Instant> last = new java.util.concurrent.ConcurrentHashMap<>();
        ex.scheduleWithFixedDelay(() -> {
            try {
                for (Models.VehicleLive v : fleetLive()) {
                    java.time.Instant prev = last.put(v.vehicleId, v.recordedAt);
                    if (prev == null || (v.recordedAt != null && v.recordedAt.isAfter(prev))) {
                        onEvent.accept(new Models.LiveEvent(v.vehicleId, v.lat, v.lon, v.speedKmh, v.recordedAt));
                    }
                }
                onConnection.accept(true);
            } catch (RuntimeException e) {
                onConnection.accept(false);
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        return ex::shutdownNow;
    }
}
