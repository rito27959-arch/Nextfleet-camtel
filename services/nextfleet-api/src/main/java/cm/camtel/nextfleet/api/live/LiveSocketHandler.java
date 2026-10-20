package cm.camtel.nextfleet.api.live;

import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import cm.camtel.nextfleet.api.identity.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canal temps réel {@code wss://…/api/v1/live} (jeton de session en en-tête Authorization à la poignée de main).
 * Chaque position n'est envoyée qu'aux utilisateurs qui ont le droit de la voir : périmètre gps:read / mission:manage
 * de l'entité de la mission, ou conducteur de SA mission. Le périmètre est recalculé périodiquement ; une session
 * révoquée (déconnexion, suspension) ferme le canal.
 */
@Component
public class LiveSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LiveSocketHandler.class);
    static final String USER = "nf.user";
    static final String TOKEN = "nf.token";

    record Client(WebSocketSession ws, AuthenticatedUser user, String token, Set<UUID> scope) { }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final LiveScope scopes;
    private final SessionService sessions;
    private final ObjectMapper mapper;

    public LiveSocketHandler(LiveFeedListener feed, LiveScope scopes, SessionService sessions, ObjectMapper mapper) {
        this.scopes = scopes;
        this.sessions = sessions;
        this.mapper = mapper;
        feed.subscribe(this::broadcast);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws IOException {
        AuthenticatedUser u = (AuthenticatedUser) ws.getAttributes().get(USER);
        String token = (String) ws.getAttributes().get(TOKEN);
        if (u == null || token == null) {
            ws.close(CloseStatus.POLICY_VIOLATION.withReason("Session requise"));
            return;
        }
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(ws, 5_000, 256 * 1024);
        Set<UUID> scope = scopes.orgScope(u);
        clients.put(ws.getId(), new Client(safe, u, token, scope));
        ObjectNode hello = mapper.createObjectNode().put("type", "hello").put("at", Instant.now().toString())
                .put("scope", scope.isEmpty() ? "missions-personnelles" : "entites:" + scope.size());
        safe.sendMessage(new TextMessage(hello.toString()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws IOException {
        if ("ping".equals(message.getPayload())) ws.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
        // canal en lecture seule : aucun autre message client n'est accepté
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        clients.remove(ws.getId());
    }

    void broadcast(JsonNode e) {
        UUID org = uuid(e, "orgUnitId");
        UUID driver = uuid(e, "driverUserId");
        if (clients.isEmpty()) return;
        ObjectNode out = scopes.enrich(e);
        String text = out.toString();
        for (Client c : clients.values()) {
            boolean allowed = (org != null && c.scope().contains(org)) || (driver != null && driver.equals(c.user().userId()));
            if (!allowed) continue;
            try {
                c.ws().sendMessage(new TextMessage(text));
            } catch (IOException | IllegalStateException ex) {
                clients.remove(c.ws().getId());
            }
        }
    }

    /** Révocation et changement de droits pris en compte sans attendre la reconnexion. */
    @Scheduled(fixedDelay = 60_000)
    public void revalidate() {
        for (Client c : clients.values()) {
            if (sessions.resolve(c.token()).isEmpty()) {
                try {
                    c.ws().close(CloseStatus.POLICY_VIOLATION.withReason("Session expirée ou révoquée"));
                } catch (IOException ignored) {
                    // déjà fermée
                }
                clients.remove(c.ws().getId());
            } else {
                clients.put(c.ws().getId(), new Client(c.ws(), c.user(), c.token(), scopes.orgScope(c.user())));
            }
        }
        if (!clients.isEmpty()) LOG.debug("Canal temps réel : {} clients", clients.size());
    }

    public int clientCount() {
        return clients.size();
    }

    private static UUID uuid(JsonNode e, String f) {
        String v = e.path(f).asText(null);
        try {
            return v == null || v.isEmpty() ? null : UUID.fromString(v);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
