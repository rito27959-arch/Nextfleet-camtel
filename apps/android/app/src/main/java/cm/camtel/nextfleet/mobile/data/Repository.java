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

    // ------------------------------------------------------------------ production (serveur) : non disponible en démonstration

    static RepositoryException demoOnly(String what) {
        return new RepositoryException(RepositoryException.Kind.SERVER, what + " : disponible avec le serveur NexFleet uniquement.");
    }

    /** Même lecture, servie par le stockage local seulement ; null s'il n'y a encore rien. */
    default <T> T cached(java.util.function.Supplier<T> read) {
        return null;
    }

    /** Si la dernière lecture a dû se contenter des données locales (réseau absent) : date de ces données. */
    default Long offlineSince() {
        return null;
    }

    default void changePassword(char[] current, char[] next) {
        throw demoOnly("Le changement de mot de passe");
    }

    default java.util.List<Models.Contact> contacts() {
        throw demoOnly("La liste des contacts");
    }

    default Models.Conversation openConversation(java.util.List<String> userIds, String title) {
        throw demoOnly("La création de conversation");
    }

    default Models.ChatLine send(String conversationId, String clientMessageId, String body, java.util.List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return send(conversationId, clientMessageId, body);
        throw demoOnly("L'envoi de pièces jointes");
    }

    /** Dépôt d'un justificatif (PDF, JPEG, PNG, WebP, 10 Mo max) ; renvoie son identifiant. */
    default String upload(String purpose, String vehicleId, String fileName, byte[] content) {
        throw demoOnly("Le dépôt de fichiers");
    }

    default byte[] download(String fileId) {
        throw demoOnly("Le téléchargement de fichiers");
    }

    /** Signal « nouveau message » en temps réel (identifiant de conversation). */
    default AutoCloseable messagesLive(java.util.function.Consumer<String> onConversation) {
        return () -> { };
    }

    default java.util.List<Models.VehicleRef> vehicles() {
        throw demoOnly("La liste des véhicules");
    }

    default java.util.List<Models.Supplier> suppliers() {
        throw demoOnly("La liste des stations");
    }

    default java.util.List<Models.FuelOrder> fuelOrders() {
        throw demoOnly("Les bons de carburant");
    }

    default Models.FuelOrder requestFuel(String vehicleId, String supplierId, double liters, double amountXaf,
                                         java.time.LocalDate validUntil, String reason) {
        throw demoOnly("La demande de bon");
    }

    default void recordFill(String requestKey, String fuelOrderId, String exceptionReason, String vehicleId, String supplierId,
                            java.time.Instant filledAt, Double odometerKm, double liters, double unitPriceXaf, boolean fullTank,
                            String receiptReference, String receiptFileId) {
        throw demoOnly("La saisie de plein");
    }

    default java.util.List<Models.Category> maintenanceCategories() {
        throw demoOnly("L'atelier");
    }

    default String requestWork(String vehicleId, String categoryCode, String title) {
        throw demoOnly("Le signalement de panne");
    }

    default java.util.List<Models.InspectionPoint> inspectionPoints() {
        throw demoOnly("Le contrôle du véhicule");
    }

    /** {@code items} : code du point → {état, commentaire, "1" si critique}. Renvoie le résultat (CONFORME, RESERVES, NON_CONFORME). */
    default String inspect(String vehicleId, String kind, Double odometerKm, java.util.Map<String, String[]> items, String notes) {
        throw demoOnly("Le contrôle du véhicule");
    }
}
