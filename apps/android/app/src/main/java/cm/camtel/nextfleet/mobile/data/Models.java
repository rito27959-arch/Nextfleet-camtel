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

        public final List<Attachment> attachments;

        public ChatLine(long seq, String senderName, String body, Instant at, boolean mine, Chat.DeliveryState state,
                        String clientMessageId) {
            this(seq, senderName, body, at, mine, state, clientMessageId, List.of());
        }

        public ChatLine(long seq, String senderName, String body, Instant at, boolean mine, Chat.DeliveryState state,
                        String clientMessageId, List<Attachment> attachments) {
            this.attachments = attachments;
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

    /** Dernière position d'un véhicule en mission ; état calculé par le serveur (couleur toujours doublée d'un libellé). */
    public static final class VehicleLive {
        public final String vehicleId, registration, vehicleLabel, missionRef, driverName, destination, state, stateLabel;
        public final double lat, lon;
        public final Double speedKmh, distanceKm;
        public final Instant recordedAt;

        public VehicleLive(String vehicleId, String registration, String vehicleLabel, String missionRef, String driverName,
                           String destination, String state, String stateLabel, double lat, double lon, Double speedKmh,
                           Double distanceKm, Instant recordedAt) {
            this.vehicleId = vehicleId;
            this.registration = registration;
            this.vehicleLabel = vehicleLabel;
            this.missionRef = missionRef;
            this.driverName = driverName;
            this.destination = destination;
            this.state = state;
            this.stateLabel = stateLabel;
            this.lat = lat;
            this.lon = lon;
            this.speedKmh = speedKmh;
            this.distanceKm = distanceKm;
            this.recordedAt = recordedAt;
        }

        public VehicleLive moved(double lat, double lon, Double speedKmh, Instant at) {
            String st = "A_VERIFIER".equals(state) ? state : speedKmh != null && speedKmh >= 5.4 ? "EN_MOUVEMENT" : "ARRET";
            String label = "A_VERIFIER".equals(st) ? stateLabel : "EN_MOUVEMENT".equals(st) ? "En mouvement" : "À l'arrêt";
            return new VehicleLive(vehicleId, registration, vehicleLabel, missionRef, driverName, destination, st, label, lat, lon,
                    speedKmh, distanceKm, at);
        }
    }

    /** Position poussée par le canal temps réel. */
    public static final class LiveEvent {
        public final String vehicleId;
        public final double lat, lon;
        public final Double speedKmh;
        public final Instant recordedAt;

        public LiveEvent(String vehicleId, double lat, double lon, Double speedKmh, Instant recordedAt) {
            this.vehicleId = vehicleId;
            this.lat = lat;
            this.lon = lon;
            this.speedKmh = speedKmh;
            this.recordedAt = recordedAt;
        }
    }

    // ------------------------------------------------------------------ production

    public static final class Attachment {
        public final String id, name, mimeType;
        public final long sizeBytes;

        public Attachment(String id, String name, String mimeType, long sizeBytes) {
            this.id = id;
            this.name = name;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
        }
    }

    public static final class Contact {
        public final String userId, displayName, role, orgUnit;

        public Contact(String userId, String displayName, String role, String orgUnit) {
            this.userId = userId;
            this.displayName = displayName;
            this.role = role;
            this.orgUnit = orgUnit;
        }
    }

    /** Véhicule du périmètre (choix dans les formulaires terrain). */
    public static final class VehicleRef {
        public final String id, registration, label, status;

        public VehicleRef(String id, String registration, String label, String status) {
            this.id = id;
            this.registration = registration;
            this.label = label;
            this.status = status;
        }

        @Override
        public String toString() {
            return registration + " · " + label;
        }
    }

    public static final class Supplier {
        public final String id, name, kind;

        public Supplier(String id, String name, String kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static final class FuelOrder {
        public final String id, orderNumber, vehicleId, registration, supplierId, supplierName, status;
        public final double authorizedLiters, deliveredLiters;
        public final java.time.LocalDate validUntil;

        public FuelOrder(String id, String orderNumber, String vehicleId, String registration, String supplierId, String supplierName,
                         String status, double authorizedLiters, double deliveredLiters, java.time.LocalDate validUntil) {
            this.id = id;
            this.orderNumber = orderNumber;
            this.vehicleId = vehicleId;
            this.registration = registration;
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.status = status;
            this.authorizedLiters = authorizedLiters;
            this.deliveredLiters = deliveredLiters;
            this.validUntil = validUntil;
        }

        public String statusLabel() {
            switch (status) {
                case "SUBMITTED": return "À valider";
                case "APPROVED": return "Validé";
                case "PARTIALLY_CONSUMED": return "Entamé";
                case "SETTLED": return "Soldé";
                case "CANCELLED": return "Annulé";
                case "EXPIRED": return "Expiré";
                default: return "Brouillon";
            }
        }

        public boolean usable() {
            return "APPROVED".equals(status) || "PARTIALLY_CONSUMED".equals(status);
        }

        @Override
        public String toString() {
            return orderNumber + " · " + registration + " · reste " + Math.round((authorizedLiters - deliveredLiters) * 10) / 10.0 + " L";
        }
    }

    public static final class Category {
        public final String code, label;

        public Category(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class InspectionPoint {
        public final String code, label, hint;

        public InspectionPoint(String code, String label, String hint) {
            this.code = code;
            this.label = label;
            this.hint = hint;
        }
    }

    /** Échéance d'un document du véhicule (assurance, vignette, contrôle technique, carte grise…). */
    public static final class DueRef {
        public final String kind, label;
        public final java.time.LocalDate dueOn;
        public final long daysLeft;
        public final String level;   // EXPIRE, A_ECHEANCE, A_JOUR

        public DueRef(String kind, String label, java.time.LocalDate dueOn, long daysLeft, String level) {
            this.kind = kind;
            this.label = label;
            this.dueOn = dueOn;
            this.daysLeft = daysLeft;
            this.level = level;
        }
    }
}
