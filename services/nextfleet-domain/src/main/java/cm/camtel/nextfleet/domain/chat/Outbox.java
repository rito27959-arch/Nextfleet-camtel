package cm.camtel.nextfleet.domain.chat;

import cm.camtel.nextfleet.domain.chat.Chat.DeliveryState;
import cm.camtel.nextfleet.domain.chat.Chat.PostResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * File d'envoi côté client (bureau, Android) : chaque message reçoit un {@code clientMessageId} à la saisie et le
 * conserve à chaque nouvelle tentative. Après une coupure, {@link #flush} renvoie les messages dans l'ordre ;
 * le serveur reconnaît les réémissions et ne crée aucun doublon (REC-CHAT-02).
 */
public final class Outbox {

    public record Pending(String clientMessageId, String conversationId, String body, DeliveryState state) { }

    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public synchronized Pending enqueue(String conversationId, String body) {
        Chat.validateBody(body);
        Pending p = new Pending(UUID.randomUUID().toString(), conversationId, body, DeliveryState.EN_ATTENTE);
        pending.put(p.clientMessageId(), p);
        return p;
    }

    /**
     * Tente d'envoyer tous les messages en attente, dans l'ordre de saisie. S'arrête au premier échec réseau
     * pour préserver l'ordre ; les messages restants passent à « Non envoyé ».
     *
     * @return les accusés reçus
     */
    public synchronized List<PostResult> flush(Function<Pending, PostResult> send) {
        List<PostResult> acks = new ArrayList<>();
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        boolean failed = false;
        while (it.hasNext()) {
            var e = it.next();
            if (failed) {
                e.setValue(withState(e.getValue(), DeliveryState.ECHEC));
                continue;
            }
            try {
                acks.add(send.apply(e.getValue()));
                it.remove();
            } catch (RuntimeException network) {
                if (network instanceof Chat.AccessDenied) throw network;
                e.setValue(withState(e.getValue(), DeliveryState.ECHEC));
                failed = true;
            }
        }
        return acks;
    }

    public synchronized List<Pending> pending() {
        return List.copyOf(pending.values());
    }

    private static Pending withState(Pending p, DeliveryState s) {
        return new Pending(p.clientMessageId(), p.conversationId(), p.body(), s);
    }
}
