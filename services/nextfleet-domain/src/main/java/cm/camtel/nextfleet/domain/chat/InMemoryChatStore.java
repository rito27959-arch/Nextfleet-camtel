package cm.camtel.nextfleet.domain.chat;

import cm.camtel.nextfleet.domain.chat.Chat.AccessDenied;
import cm.camtel.nextfleet.domain.chat.Chat.Cursor;
import cm.camtel.nextfleet.domain.chat.Chat.Message;
import cm.camtel.nextfleet.domain.chat.Chat.PostResult;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Implémentation de référence en mémoire (démo bureau, tests). Mêmes règles que PostgreSQL :
 * participant actif + permission à chaque échange, séquence par conversation, idempotence (émetteur, clientMessageId),
 * curseurs monotones, « lu » implique « distribué ».
 */
public final class InMemoryChatStore implements Chat.Store {

    private final Clock clock;
    private final Predicate<String> canChat;
    private final Map<String, Set<String>> participants = new HashMap<>();
    private final Map<String, List<Message>> messages = new HashMap<>();
    private final Map<String, Message> byClientId = new HashMap<>();
    private final Map<String, Map<String, Cursor>> cursors = new HashMap<>();

    /** @param canChat vrai si l'utilisateur porte la permission chat:use */
    public InMemoryChatStore(Clock clock, Predicate<String> canChat) {
        this.clock = clock;
        this.canChat = canChat;
    }

    public synchronized void createConversation(String id, Set<String> members) {
        participants.put(id, new java.util.LinkedHashSet<>(members));
        messages.put(id, new ArrayList<>());
        Map<String, Cursor> c = new LinkedHashMap<>();
        members.forEach(m -> c.put(m, new Cursor(0, 0)));
        cursors.put(id, c);
    }

    public synchronized void leave(String conversationId, String userId) {
        participants.getOrDefault(conversationId, Set.of()).remove(userId);
    }

    private void check(String conversationId, String userId) {
        if (!participants.getOrDefault(conversationId, Set.of()).contains(userId)) {
            throw new AccessDenied("Vous ne participez pas à cette conversation.");
        }
        if (!canChat.test(userId)) throw new AccessDenied("La messagerie n'est pas ouverte à votre profil.");
    }

    @Override
    public synchronized PostResult post(String conversationId, String senderId, String clientMessageId, String body) {
        check(conversationId, senderId);
        Chat.validateBody(body);
        Message existing = byClientId.get(senderId + "\u0000" + clientMessageId);
        if (existing != null) return new PostResult(existing, true);
        List<Message> list = messages.get(conversationId);
        Message m = new Message(conversationId, senderId, clientMessageId, list.size() + 1L, body, clock.instant());
        list.add(m);
        byClientId.put(senderId + "\u0000" + clientMessageId, m);
        ackRead(conversationId, senderId, m.serverSeq());   // l'émetteur a « lu » son propre message
        return new PostResult(m, false);
    }

    @Override
    public synchronized List<Message> after(String conversationId, String readerId, long afterSeq, int limit) {
        check(conversationId, readerId);
        return messages.get(conversationId).stream().filter(m -> m.serverSeq() > afterSeq).limit(limit).toList();
    }

    @Override
    public synchronized void ackDelivered(String conversationId, String userId, long seq) {
        check(conversationId, userId);
        move(conversationId, userId, seq, false);
    }

    @Override
    public synchronized void ackRead(String conversationId, String userId, long seq) {
        check(conversationId, userId);
        move(conversationId, userId, seq, true);
    }

    private void move(String conversationId, String userId, long seq, boolean read) {
        long last = messages.get(conversationId).size();
        if (seq > last) throw new IllegalArgumentException("Accusé au-delà du dernier message");
        Cursor c = cursors.get(conversationId).getOrDefault(userId, new Cursor(0, 0));
        long r = read ? Math.max(c.read(), seq) : c.read();
        long d = Math.max(Math.max(c.delivered(), r), read ? 0 : seq);
        cursors.get(conversationId).put(userId, new Cursor(d, r));
    }

    @Override
    public synchronized Map<String, Cursor> cursors(String conversationId, String readerId) {
        check(conversationId, readerId);
        return Map.copyOf(cursors.get(conversationId));
    }

    public synchronized long lastSeq(String conversationId) {
        return messages.getOrDefault(conversationId, List.of()).size();
    }
}
