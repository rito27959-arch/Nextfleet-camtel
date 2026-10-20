package cm.camtel.nextfleet.domain.chat;

import cm.camtel.nextfleet.domain.chat.Chat.DeliveryState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ChatTest {

    private final InMemoryChatStore store = new InMemoryChatStore(
            Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC), u -> !u.equals("auditeur"));

    {
        store.createConversation("MIS-014", Set.of("gestionnaire", "conducteur", "auditeur"));
    }

    @Test
    void serverSequenceAndIdempotentResend() {
        var a = store.post("MIS-014", "gestionnaire", "c-1", "Merci d'envoyer la photo du compteur.");
        var b = store.post("MIS-014", "conducteur", "c-1", "Photo envoyée.");
        var again = store.post("MIS-014", "conducteur", "c-1", "Photo envoyée.");
        assertEquals(1, a.message().serverSeq());
        assertEquals(2, b.message().serverSeq());
        assertTrue(again.duplicate());
        assertEquals(2, again.message().serverSeq());
        assertEquals(2, store.lastSeq("MIS-014"), "aucun doublon");
    }

    @Test
    void accessCheckedOnEveryExchange() {
        assertThrows(Chat.AccessDenied.class, () -> store.post("MIS-014", "intrus", "x", "bonjour"));
        assertThrows(Chat.AccessDenied.class, () -> store.post("MIS-014", "auditeur", "x", "bonjour"), "chat:use absent");
        store.post("MIS-014", "gestionnaire", "c-9", "Point d'étape ?");
        store.leave("MIS-014", "conducteur");
        assertThrows(Chat.AccessDenied.class, () -> store.after("MIS-014", "conducteur", 0, 50), "lecture refusée après départ");
    }

    @Test
    void recoveryAfterSeqAndDeliveryStates() {
        store.post("MIS-014", "gestionnaire", "c-1", "Départ confirmé ?");
        store.post("MIS-014", "gestionnaire", "c-2", "Merci de signaler l'arrivée.");
        var mine = store.after("MIS-014", "gestionnaire", 0, 10);
        assertEquals(2, mine.size());
        // le conducteur se reconnecte avec son dernier numéro connu = 1
        var missed = store.after("MIS-014", "conducteur", 1, 10);
        assertEquals(1, missed.size());
        assertEquals("Merci de signaler l'arrivée.", missed.get(0).body());

        var last = mine.get(1);
        var others = new java.util.HashMap<>(store.cursors("MIS-014", "gestionnaire"));
        others.remove("auditeur");
        assertEquals(DeliveryState.ENVOYE, Chat.stateFor(last, others));
        store.ackDelivered("MIS-014", "conducteur", 2);
        others.put("conducteur", store.cursors("MIS-014", "gestionnaire").get("conducteur"));
        assertEquals(DeliveryState.DISTRIBUE, Chat.stateFor(last, others));
        store.ackRead("MIS-014", "conducteur", 2);
        store.ackDelivered("MIS-014", "conducteur", 1);   // un accusé ne recule jamais
        others.put("conducteur", store.cursors("MIS-014", "gestionnaire").get("conducteur"));
        assertEquals(DeliveryState.LU, Chat.stateFor(last, others));
        assertThrows(IllegalArgumentException.class, () -> store.ackRead("MIS-014", "conducteur", 99));
    }

    @Test
    void outboxResendsAfterOutageWithoutDuplicates() {
        Outbox out = new Outbox();
        out.enqueue("MIS-014", "Je suis à Mbalmayo.");
        out.enqueue("MIS-014", "Arrivée prévue 11 h.");
        AtomicBoolean online = new AtomicBoolean(false);
        var acks = out.flush(p -> {
            if (!online.get()) throw new IllegalStateException("réseau indisponible");
            return store.post(p.conversationId(), "conducteur", p.clientMessageId(), p.body());
        });
        assertTrue(acks.isEmpty());
        assertTrue(out.pending().stream().allMatch(p -> p.state() == DeliveryState.ECHEC));

        // une tentative a atteint le serveur mais l'accusé s'est perdu : la réémission ne double pas
        var first = out.pending().get(0);
        store.post("MIS-014", "conducteur", first.clientMessageId(), first.body());
        online.set(true);
        acks = out.flush(p -> store.post(p.conversationId(), "conducteur", p.clientMessageId(), p.body()));
        assertEquals(2, acks.size());
        assertTrue(acks.get(0).duplicate());
        assertEquals(2, store.lastSeq("MIS-014"));
        assertTrue(out.pending().isEmpty());
    }
}
