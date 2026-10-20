package cm.camtel.nextfleet.chat;

import cm.camtel.nextfleet.domain.chat.Chat;
import cm.camtel.nextfleet.domain.chat.Chat.Message;
import cm.camtel.nextfleet.domain.chat.Chat.PostResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Passerelle de messagerie temps réel PILOTE (protocole JSON sur WebSocket). La cible du cahier est Spring
 * WebSocket dans l'API ; ce serveur exécutable prouve dès maintenant le protocole et les règles, avec le même
 * stockage PostgreSQL. Trames :
 * <pre>
 * → {"type":"auth","token":"…"}                         ← {"type":"ready","userId":"…"}
 * → {"type":"send","conversationId","clientMessageId","body"}  ← {"type":"ack",clientMessageId,serverSeq,duplicate}
 *                                                       ← {"type":"message",…} diffusé aux participants autorisés
 * → {"type":"sync","conversationId","afterSeq"}         ← {"type":"history","messages":[…]}
 * → {"type":"delivered"|"read","conversationId","seq"}  ← {"type":"receipt",conversationId,userId,delivered,read}
 *                                                       ← {"type":"error","code":"UNAUTHENTICATED|FORBIDDEN|INVALID|UNAVAILABLE"}
 * </pre>
 * Le premier message doit être l'authentification (le jeton ne passe jamais dans l'URL ni dans les journaux).
 * En production : WSS uniquement, derrière le frontal TLS de la DSIR.
 */
public final class ChatWebSocketServer extends WebSocketServer {

    private static final Logger LOG = Logger.getLogger(ChatWebSocketServer.class.getName());
    private final ObjectMapper json = new ObjectMapper();
    private final JdbcChatStore store;
    private final SessionAuthenticator auth;
    private final Map<WebSocket, String> users = new ConcurrentHashMap<>();
    private volatile boolean started;

    public ChatWebSocketServer(InetSocketAddress bind, JdbcChatStore store, SessionAuthenticator auth) {
        super(bind);
        this.store = store;
        this.auth = auth;
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    public boolean isStarted() {
        return started;
    }

    @Override
    public void onStart() {
        started = true;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // en attente de la trame d'authentification
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        users.remove(conn);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.warning("WebSocket : " + ex.getClass().getSimpleName());
    }

    @Override
    public void onMessage(WebSocket conn, String text) {
        JsonNode f;
        try {
            f = json.readTree(text);
        } catch (java.io.IOException e) {
            error(conn, "INVALID", "Trame illisible");
            return;
        }
        String type = f.path("type").asText("");
        String user = users.get(conn);
        if (user == null) {
            if (!"auth".equals(type)) {
                error(conn, "UNAUTHENTICATED", "Authentification requise");
                conn.close(4401, "authentification requise");
                return;
            }
            auth.authenticate(f.path("token").asText(null)).ifPresentOrElse(p -> {
                users.put(conn, p.userId());
                conn.send(json.createObjectNode().put("type", "ready").put("userId", p.userId()).toString());
            }, () -> {
                error(conn, "UNAUTHENTICATED", "Session invalide ou expirée");
                conn.close(4401, "session invalide");
            });
            return;
        }
        String conv = f.path("conversationId").asText("");
        try {
            switch (type) {
                case "send" -> {
                    PostResult r = store.post(conv, user, f.path("clientMessageId").asText(""), f.path("body").asText(""));
                    conn.send(json.createObjectNode().put("type", "ack").put("clientMessageId", r.message().clientMessageId())
                            .put("serverSeq", r.message().serverSeq()).put("duplicate", r.duplicate()).toString());
                    if (!r.duplicate()) broadcast(conv, message(r.message()));
                }
                case "sync" -> {
                    ArrayNode arr = json.createArrayNode();
                    store.after(conv, user, f.path("afterSeq").asLong(0), 200).forEach(m -> arr.add(message(m)));
                    ObjectNode h = json.createObjectNode().put("type", "history").put("conversationId", conv);
                    h.set("messages", arr);
                    conn.send(h.toString());
                }
                case "delivered", "read" -> {
                    long seq = f.path("seq").asLong(0);
                    if ("read".equals(type)) store.ackRead(conv, user, seq);
                    else store.ackDelivered(conv, user, seq);
                    Chat.Cursor c = store.cursors(conv, user).get(user);
                    broadcast(conv, json.createObjectNode().put("type", "receipt").put("conversationId", conv).put("userId", user)
                            .put("delivered", c.delivered()).put("read", c.read()));
                }
                default -> error(conn, "INVALID", "Type de trame inconnu");
            }
        } catch (Chat.AccessDenied e) {
            error(conn, "FORBIDDEN", e.getMessage());
        } catch (IllegalArgumentException e) {
            error(conn, "INVALID", e.getMessage());
        } catch (RuntimeException e) {
            error(conn, "UNAVAILABLE", "Messagerie momentanément indisponible");
        }
    }

    /** Diffusion limitée aux participants actifs autorisés, vérifiés au moment de l'envoi. */
    private void broadcast(String conversationId, ObjectNode frame) {
        var audience = new java.util.HashSet<>(store.audience(conversationId));
        String text = frame.toString();
        users.forEach((socket, uid) -> {
            if (audience.contains(uid) && socket.isOpen()) socket.send(text);
        });
    }

    private ObjectNode message(Message m) {
        return json.createObjectNode().put("type", "message").put("conversationId", m.conversationId())
                .put("senderId", m.senderId()).put("clientMessageId", m.clientMessageId()).put("serverSeq", m.serverSeq())
                .put("body", m.body()).put("createdAt", m.createdAt().toString());
    }

    private void error(WebSocket conn, String code, String message) {
        conn.send(json.createObjectNode().put("type", "error").put("code", code).put("message", message).toString());
    }
}
