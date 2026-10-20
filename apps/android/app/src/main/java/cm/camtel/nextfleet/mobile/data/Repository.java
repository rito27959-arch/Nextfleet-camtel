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
}
