package cm.camtel.nextfleet.desktop.data;

import cm.camtel.nextfleet.domain.chat.Chat;
import cm.camtel.nextfleet.domain.security.Role;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Objets du lot 2 : missions et GPS, messagerie, comptes et rôles, journal d'audit. */
public final class Ops {

    private Ops() { }

    public enum MissionStatus {
        DRAFT("Brouillon", "neutral"), AUTHORIZED("Autorisée", "info"), ACTIVE("En cours", "ok"),
        FINISHED("Terminée", "neutral"), CANCELLED("Annulée", "neutral");

        public final String label;
        public final String tone;

        MissionStatus(String label, String tone) {
            this.label = label;
            this.tone = tone;
        }
    }

    /**
     * Mission (NF-70 à NF-74). {@code deviceId} = émetteur autorisé pour la collecte (téléphone en phase 1) ;
     * la collecte n'existe qu'entre {@code startedAt} et {@code finishedAt}.
     */
    public record Mission(UUID id, String reference, String purpose, String origin, String destination, String registration,
                          String vehicleLabel, String driverName, String driverLogin, MissionStatus status,
                          Instant plannedStart, Instant plannedEnd, Instant startedAt, Instant finishedAt, String deviceId,
                          String noticeVersion, String conversationId, long version) { }

    public record Conversation(String id, Chat.Kind kind, String title, String subtitle, String lastBody, String lastSender,
                               Instant lastAt, long unread, List<String> participants) { }

    public record ChatLine(long seq, String senderId, String senderName, String body, Instant at, boolean mine,
                           Chat.DeliveryState state, String clientMessageId, List<Prod.Attachment> attachments) {
        public ChatLine(long seq, String senderId, String senderName, String body, Instant at, boolean mine,
                        Chat.DeliveryState state, String clientMessageId) {
            this(seq, senderId, senderName, body, at, mine, state, clientMessageId, List.of());
        }
    }

    public record Grant(Role role, String scope, LocalDate until, boolean delegation) { }

    public record UserAccount(String id, String login, String displayName, String orgUnit, List<Grant> grants,
                              String status, Instant lastSeen) { }

    /** Événement du journal chaîné (SHA-256 du précédent + contenu) ; jamais le contenu d'un message. */
    public record AuditEntry(long seq, Instant at, String actor, String action, String object, String detail, String hash,
                             String previousHash) { }

    /** États de la carte de flotte, calculés par le serveur (mêmes règles sur bureau et mobile). */
    public enum LiveState {
        EN_MOUVEMENT("En mouvement", "live-moving"), ARRET("À l'arrêt", "live-stopped"),
        SIGNAL_ANCIEN("Signal ancien", "live-stale"), A_VERIFIER("À vérifier", "live-check");

        public final String label;
        public final String styleClass;

        LiveState(String label, String styleClass) {
            this.label = label;
            this.styleClass = styleClass;
        }
    }

    /** Dernière position acceptée d'un véhicule en mission (carte de flotte). */
    public record VehicleLive(String vehicleId, String registration, String vehicleLabel, String category, String missionId,
                              String missionRef, String missionStatus, String driverName, String origin, String destination,
                              double lat, double lon, double accuracyM, Double speedKmh, Double bearingDeg, Instant recordedAt,
                              long ageSeconds, LiveState state, String stateLabel, Instant startedAt, Double distanceKm,
                              int openAnomalies) { }

    /** Position poussée par le canal temps réel. */
    public record LiveEvent(String vehicleId, String registration, String missionRef, double lat, double lon, Double speedKmh,
                            Instant recordedAt) { }
}
