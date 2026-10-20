package cm.camtel.nextfleet.domain.chat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Modèle et contrat de la messagerie (NF-60 à NF-64, REC-CHAT-01/02).
 * <ul>
 *   <li>Le serveur attribue une séquence par conversation ; l'ordre affiché est celui de la séquence.</li>
 *   <li>{@code clientMessageId} rend l'envoi idempotent : une réémission après coupure ne crée pas de doublon.</li>
 *   <li>« Envoyé » n'est affiché qu'après persistance (accusé serveur), jamais avant.</li>
 *   <li>Le droit d'écrire et de lire est vérifié à chaque échange (participant actif + permission chat:use).</li>
 * </ul>
 */
public final class Chat {

    private Chat() { }

    public enum Kind { DIRECT, MISSION, VEHICLE, SERVICE }

    /** États visibles par l'émetteur, dans l'ordre (HIG : libellé discret sous la dernière bulle). */
    public enum DeliveryState {
        EN_ATTENTE("Envoi…"), ENVOYE("Envoyé"), DISTRIBUE("Distribué"), LU("Lu"), ECHEC("Non envoyé");

        public final String label;

        DeliveryState(String label) {
            this.label = label;
        }
    }

    public record Message(String conversationId, String senderId, String clientMessageId, long serverSeq, String body,
                          Instant createdAt) {
        public Message {
            Objects.requireNonNull(conversationId);
            Objects.requireNonNull(senderId);
            Objects.requireNonNull(clientMessageId);
            validateBody(body);
        }
    }

    /** Résultat d'un envoi : {@code duplicate} = réémission reconnue, le message existant est renvoyé. */
    public record PostResult(Message message, boolean duplicate) { }

    /** Curseurs d'un participant : dernier message distribué et dernier lu. */
    public record Cursor(long delivered, long read) { }

    public static final class AccessDenied extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AccessDenied(String message) {
            super(message);
        }
    }

    /** Contrat commun du stockage (mémoire pour la démo et les tests, PostgreSQL pour la passerelle). */
    public interface Store {
        PostResult post(String conversationId, String senderId, String clientMessageId, String body);

        /** Reprise après coupure : messages de séquence strictement supérieure à {@code afterSeq}. */
        List<Message> after(String conversationId, String readerId, long afterSeq, int limit);

        void ackDelivered(String conversationId, String userId, long seq);

        void ackRead(String conversationId, String userId, long seq);

        Map<String, Cursor> cursors(String conversationId, String readerId);
    }

    public static void validateBody(String body) {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("Message vide");
        if (body.length() > 8000) throw new IllegalArgumentException("Message trop long (8 000 caractères au plus)");
    }

    /**
     * État d'un message pour son émetteur : « Lu » quand tous les autres participants l'ont lu,
     * « Distribué » quand tous l'ont reçu, sinon « Envoyé ».
     */
    public static DeliveryState stateFor(Message m, Map<String, Cursor> cursors) {
        boolean anyOther = false;
        boolean allRead = true;
        boolean allDelivered = true;
        for (var e : cursors.entrySet()) {
            if (e.getKey().equals(m.senderId())) continue;
            anyOther = true;
            allRead &= e.getValue().read() >= m.serverSeq();
            allDelivered &= e.getValue().delivered() >= m.serverSeq();
        }
        if (!anyOther) return DeliveryState.ENVOYE;
        if (allRead) return DeliveryState.LU;
        if (allDelivered) return DeliveryState.DISTRIBUE;
        return DeliveryState.ENVOYE;
    }
}
