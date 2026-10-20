package cm.camtel.nextfleet.mobile.data;

import cm.camtel.nextfleet.domain.chat.Chat;

import java.time.Instant;
import java.util.List;

/** Objets échangés avec l'API (contrat shared/api-contract/openapi-lot2.yaml). */
public final class Models {

    private Models() { }

    public enum MissionStatus {
        DRAFT("Brouillon"), AUTHORIZED("Autorisée"), ACTIVE("En cours"), FINISHED("Terminée"), CANCELLED("Annulée");

        public final String label;

        MissionStatus(String label) {
            this.label = label;
        }
    }

    public static final class Mission {
        public final String id, reference, purpose, origin, destination, registration, vehicleLabel, category, driverName;
        public final MissionStatus status;
        public final Instant plannedStart, startedAt, finishedAt;
        public final String deviceId, conversationId;

        public Mission(String id, String reference, String purpose, String origin, String destination, String registration,
                       String vehicleLabel, String category, String driverName, MissionStatus status, Instant plannedStart,
                       Instant startedAt, Instant finishedAt, String deviceId, String conversationId) {
            this.id = id;
            this.reference = reference;
            this.purpose = purpose;
            this.origin = origin;
            this.destination = destination;
            this.registration = registration;
            this.vehicleLabel = vehicleLabel;
            this.category = category;
            this.driverName = driverName;
            this.status = status;
            this.plannedStart = plannedStart;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.deviceId = deviceId;
            this.conversationId = conversationId;
        }

        public Mission with(MissionStatus s, Instant started, Instant finished, String device) {
            return new Mission(id, reference, purpose, origin, destination, registration, vehicleLabel, category, driverName,
                    s, plannedStart, started, finished, device, conversationId);
        }
    }

    /** Résultat du démarrage : jeton de mission éphémère et adresse de la passerelle GPS. */
    public static final class StartResult {
        public final Mission mission;
        public final String missionToken;
        public final String gatewayUrl;

        public StartResult(Mission mission, String missionToken, String gatewayUrl) {
            this.mission = mission;
            this.missionToken = missionToken;
            this.gatewayUrl = gatewayUrl;
        }
    }

    public static final class Conversation {
        public final String id, title, subtitle, lastBody;
        public final Chat.Kind kind;
        public final Instant lastAt;
        public final long unread;

        public Conversation(String id, Chat.Kind kind, String title, String subtitle, String lastBody, Instant lastAt, long unread) {
            this.id = id;
            this.kind = kind;
            this.title = title;
            this.subtitle = subtitle;
            this.lastBody = lastBody;
            this.lastAt = lastAt;
            this.unread = unread;
        }
    }

    public static final class ChatLine {
        public final long seq;
        public final String senderName, body, clientMessageId;
        public final Instant at;
        public final boolean mine;
        public final Chat.DeliveryState state;

        public ChatLine(long seq, String senderName, String body, Instant at, boolean mine, Chat.DeliveryState state,
                        String clientMessageId) {
            this.seq = seq;
            this.senderName = senderName;
            this.body = body;
            this.at = at;
            this.mine = mine;
            this.state = state;
            this.clientMessageId = clientMessageId;
        }
    }

    public static List<String> demoLogins() {
        return List.of("conducteur", "gestionnaire", "maintenance", "controleur");
    }
}
