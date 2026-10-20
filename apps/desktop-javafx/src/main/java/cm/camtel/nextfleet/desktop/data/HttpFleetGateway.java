package cm.camtel.nextfleet.desktop.data;

import cm.camtel.nextfleet.desktop.data.Models.DashboardData;
import cm.camtel.nextfleet.desktop.data.Models.Page;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Models.VehicleDraft;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Passerelle vers l'API NexFleet (contrats shared/api-contract/openapi.yaml et openapi-lot2.yaml).
 * HTTPS imposé hors poste de développement ; le jeton reste en mémoire et n'est jamais écrit sur disque.
 * STATUT : testée contre un faux serveur HTTP local (DesktopLogicTest) ; les mêmes routes sont couvertes côté API par
 * MissionChatAssistantIntegrationTest. Pas encore de recette de bout en bout bureau ↔ API réelle.
 */
public final class HttpFleetGateway implements FleetGateway {

    private final URI base;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private volatile String token;

    public HttpFleetGateway(URI base) {
        boolean local = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost());
        if (!"https".equals(base.getScheme()) && !local) {
            throw new IllegalArgumentException("HTTPS obligatoire pour un serveur distant (SEC-03) : " + base);
        }
        this.base = base;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public boolean isDemo() {
        return false;
    }

    @Override
    public Session login(String login, char[] password) {
        ObjectNode body = json.createObjectNode().put("login", login).put("password", new String(password)).put("clientKind", "DESKTOP");
        java.util.Arrays.fill(password, '\0');
        JsonNode r = send("POST", "/api/v1/auth/login", body, false);
        token = r.path("token").asText();
        JsonNode u = r.path("user");
        List<String> roles = new ArrayList<>();
        u.path("roles").forEach(g -> roles.add(g.path("role").asText()));
        return new Session(token, u.path("displayName").asText(), u.path("login").asText(), roles, false);
    }

    @Override
    public void logout() {
        if (token != null) {
            try {
                send("POST", "/api/v1/auth/logout", null, true);
            } finally {
                token = null;
            }
        }
    }

    @Override
    public DashboardData dashboard() {
        // L'agrégat de tableau de bord (NF-20) est prévu au lot 2 de l'API ; le client ne l'invente pas.
        throw new GatewayException(GatewayException.Kind.SERVER,
                "Indicateurs du tableau de bord non encore fournis par l'API (lot 2).");
    }

    @Override
    public Page<Vehicle> vehicles(String query, VehicleStatus status, int page, int size) {
        StringBuilder path = new StringBuilder("/api/v1/vehicles?page=").append(page).append("&size=").append(size);
        if (query != null && !query.isBlank()) path.append("&q=").append(URLEncoder.encode(query.strip(), StandardCharsets.UTF_8));
        if (status != null) path.append("&status=").append(status.name());
        JsonNode r = send("GET", path.toString(), null, true);
        List<Vehicle> items = new ArrayList<>();
        r.path("items").forEach(n -> items.add(toVehicle(n)));
        return new Page<>(items, r.path("page").asInt(), r.path("size").asInt(), r.path("total").asLong());
    }

    @Override
    public Vehicle vehicle(UUID id) {
        return toVehicle(send("GET", "/api/v1/vehicles/" + id, null, true));
    }

    @Override
    public Vehicle createVehicle(VehicleDraft d) {
        throw new GatewayException(GatewayException.Kind.SERVER,
                "Création côté API réelle : nécessite l'entité d'affectation (orgUnitId) issue de /me — lot 2.");
    }

    @Override
    public java.util.List<Models.Driver> drivers(String query) {
        throw new GatewayException(GatewayException.Kind.SERVER, "Module Conducteurs non encore exposé par l'API (lot 2).");
    }

    @Override
    public java.util.List<Models.DueItem> dueDates(int thresholdDays) {
        throw new GatewayException(GatewayException.Kind.SERVER,
                "Échéances : la vue base v_due_date existe ; l'API correspondante arrive au lot 2.");
    }

    @Override
    public java.util.List<Models.DueItem> vehicleDueDates(UUID vehicleId) {
        throw new GatewayException(GatewayException.Kind.SERVER, "Échéances du véhicule : lot 2.");
    }

    @Override
    public java.util.List<Models.NotificationItem> notifications() {
        throw new GatewayException(GatewayException.Kind.SERVER, "Centre de notifications : API prévue au lot 3 (NF-24).");
    }

    @Override
    public void markNotificationsRead(java.util.Collection<String> ids) {
        // pas d'état serveur au lot 1
    }

    /** POST /api/v1/vehicles/{id}/archive avec If-Match (contrat OpenAPI lot 1). */
    @Override
    public Vehicle archiveVehicle(UUID id, long expectedVersion, String reason) {
        String path = "/api/v1/vehicles/" + id + "/archive?reason=" + URLEncoder.encode(reason, StandardCharsets.UTF_8);
        return toVehicle(sendWithHeader("POST", path, "If-Match", "\"" + expectedVersion + "\""));
    }

    // ------------------------------------------------------------------ lot 2 : missions et GPS

    @Override
    public List<Ops.Mission> missions() {
        List<Ops.Mission> out = new ArrayList<>();
        send("GET", "/api/v1/missions", null, true).forEach(n -> out.add(toMission(n)));
        return out;
    }

    @Override
    public List<cm.camtel.nextfleet.domain.gps.GpsFix> missionTrack(UUID missionId) {
        List<cm.camtel.nextfleet.domain.gps.GpsFix> out = new ArrayList<>();
        send("GET", "/api/v1/missions/" + missionId + "/track", null, true).forEach(n -> out.add(new cm.camtel.nextfleet.domain.gps.GpsFix(
                n.path("clientFixId").asText(), Instant.parse(n.path("recordedAt").asText()), n.path("lat").asDouble(), n.path("lon").asDouble(),
                n.path("accuracyM").asDouble(), n.hasNonNull("speedMps") ? n.get("speedMps").asDouble() : null)));
        return out;
    }

    /** Le poste de bureau n'est pas un émetteur GPS : la mission démarre depuis le téléphone qui collecte la position. */
    @Override
    public Ops.Mission startMission(UUID missionId, String noticeVersion) {
        throw new GatewayException(GatewayException.Kind.VALIDATION, "Démarrez la mission depuis l'application Android NexFleet : "
                + "c'est le téléphone de mission qui collecte la position, après affichage de la notice d'information.");
    }

    @Override
    public Ops.Mission finishMission(UUID missionId) {
        return toMission(send("POST", "/api/v1/missions/" + missionId + "/finish", null, true));
    }

    static Ops.Mission toMission(JsonNode n) {
        Ops.MissionStatus st;
        try {
            st = Ops.MissionStatus.valueOf(n.path("status").asText());
        } catch (IllegalArgumentException e) {
            st = Ops.MissionStatus.CANCELLED;
        }
        return new Ops.Mission(UUID.fromString(n.path("id").asText()), n.path("reference").asText(), n.path("purpose").asText(""),
                n.path("origin").asText(""), n.path("destination").asText(""), n.path("registration").asText(""),
                n.path("vehicleLabel").asText(""), n.path("driverName").asText(""), null, st, instant(n, "plannedStart"),
                instant(n, "plannedEnd"), instant(n, "startedAt"), instant(n, "finishedAt"), n.path("deviceId").asText(null),
                n.path("noticeVersion").asText(null), n.path("conversationId").asText(null), n.path("version").asLong());
    }

    // ------------------------------------------------------------------ lot 2 : messagerie

    @Override
    public List<Ops.Conversation> conversations() {
        List<Ops.Conversation> out = new ArrayList<>();
        send("GET", "/api/v1/conversations", null, true).forEach(n -> {
            cm.camtel.nextfleet.domain.chat.Chat.Kind kind;
            try {
                kind = cm.camtel.nextfleet.domain.chat.Chat.Kind.valueOf(n.path("kind").asText("DIRECT"));
            } catch (IllegalArgumentException e) {
                kind = cm.camtel.nextfleet.domain.chat.Chat.Kind.DIRECT;
            }
            out.add(new Ops.Conversation(n.path("id").asText(), kind, n.path("title").asText(""), n.path("subtitle").asText(""),
                    n.path("lastBody").asText(null), null, instant(n, "lastAt"), n.path("unread").asLong(), List.of()));
        });
        return out;
    }

    @Override
    public List<Ops.ChatLine> messages(String conversationId) {
        List<Ops.ChatLine> out = new ArrayList<>();
        send("GET", "/api/v1/conversations/" + UUID.fromString(conversationId) + "/messages", null, true).forEach(n -> {
            String state = n.path("state").asText(null);
            out.add(new Ops.ChatLine(n.path("serverSeq").asLong(), null, n.path("senderName").asText(""), n.path("body").asText(),
                    instant(n, "createdAt"), n.path("mine").asBoolean(),
                    state == null ? null : cm.camtel.nextfleet.domain.chat.Chat.DeliveryState.valueOf(state), n.path("clientMessageId").asText("")));
        });
        return out;
    }

    @Override
    public Ops.ChatLine sendMessage(String conversationId, String clientMessageId, String body) {
        ObjectNode b = json.createObjectNode().put("clientMessageId", clientMessageId).put("body", body);
        JsonNode ack = send("POST", "/api/v1/conversations/" + UUID.fromString(conversationId) + "/messages", b, true);
        return new Ops.ChatLine(ack.path("serverSeq").asLong(), null, "Vous", body, instant(ack, "createdAt"), true,
                cm.camtel.nextfleet.domain.chat.Chat.DeliveryState.ENVOYE, clientMessageId);
    }

    @Override
    public void markConversationRead(String conversationId) {
        send("POST", "/api/v1/conversations/" + UUID.fromString(conversationId) + "/read", null, true);
    }

    // ------------------------------------------------------------------ lot 2 : assistant (lecture seule)

    @Override
    public cm.camtel.nextfleet.domain.assistant.AssistantActivation.State assistantState() {
        return toActivation(send("GET", "/api/v1/assistant/activation", null, true));
    }

    @Override
    public List<cm.camtel.nextfleet.domain.assistant.AssistantActivation.State> assistantDecisions() {
        return List.of(assistantState());   // l'API expose la décision en vigueur ; l'historique complet est dans le journal d'audit
    }

    @Override
    public cm.camtel.nextfleet.domain.assistant.AssistantActivation.State decideAssistant(boolean enable, String reviewRef,
                                                                                         String approvedBy, String reason) {
        ObjectNode b = json.createObjectNode().put("enable", enable).put("securityReviewRef", reviewRef).put("approvedBy", approvedBy)
                .put("reason", reason);
        return toActivation(send("POST", "/api/v1/assistant/activation", b, true));
    }

    @Override
    public cm.camtel.nextfleet.domain.assistant.FleetAssistant.Answer askAssistant(String question) {
        JsonNode a = send("POST", "/api/v1/assistant/ask", json.createObjectNode().put("question", question), true);
        List<cm.camtel.nextfleet.domain.assistant.FleetAssistant.Figure> figures = new ArrayList<>();
        a.path("figures").forEach(f -> figures.add(new cm.camtel.nextfleet.domain.assistant.FleetAssistant.Figure(
                f.path("label").asText(), f.path("value").asText())));
        List<String> filters = new ArrayList<>();
        a.path("filters").forEach(f -> filters.add(f.asText()));
        List<cm.camtel.nextfleet.domain.assistant.FleetAssistant.Source> sources = new ArrayList<>();
        a.path("sources").forEach(f -> sources.add(new cm.camtel.nextfleet.domain.assistant.FleetAssistant.Source(
                f.path("ref").asText(), f.path("description").asText())));
        cm.camtel.nextfleet.domain.assistant.IntentParser.Intent intent;
        try {
            intent = cm.camtel.nextfleet.domain.assistant.IntentParser.Intent.valueOf(a.path("intent").asText("AIDE"));
        } catch (IllegalArgumentException e) {
            intent = cm.camtel.nextfleet.domain.assistant.IntentParser.Intent.AIDE;
        }
        return new cm.camtel.nextfleet.domain.assistant.FleetAssistant.Answer(intent, a.path("refused").asBoolean(),
                a.path("text").asText(""), figures, a.path("period").asText(null), filters, sources);
    }

    private static cm.camtel.nextfleet.domain.assistant.AssistantActivation.State toActivation(JsonNode n) {
        return new cm.camtel.nextfleet.domain.assistant.AssistantActivation.State(n.path("enabled").asBoolean(),
                n.path("securityReviewRef").asText(null), n.path("approvedBy").asText(null), n.path("decidedBy").asText(null),
                instant(n, "decidedAt"), n.path("reason").asText(null));
    }

    // ------------------------------------------------------------------ lot 2 : administration et audit

    @Override
    public List<Ops.UserAccount> users() {
        List<Ops.UserAccount> out = new ArrayList<>();
        send("GET", "/api/v1/admin/users", null, true).forEach(n -> {
            List<Ops.Grant> grants = new ArrayList<>();
            n.path("roles").forEach(r -> cm.camtel.nextfleet.domain.security.Role.ofCode(r.asText())
                    .ifPresent(role -> grants.add(new Ops.Grant(role, n.path("orgUnit").asText(""), null, false))));
            out.add(new Ops.UserAccount(n.path("id").asText(), n.path("login").asText(), n.path("displayName").asText(),
                    n.path("orgUnit").asText(""), grants, n.path("status").asText(), null));
        });
        return out;
    }

    private volatile long lastChainBreak = -1;

    @Override
    public List<Ops.AuditEntry> auditTrail() {
        JsonNode check = send("GET", "/api/v1/admin/audit/verify", null, true);
        lastChainBreak = check.hasNonNull("firstBrokenId") ? check.get("firstBrokenId").asLong() : -1;
        List<Ops.AuditEntry> out = new ArrayList<>();
        send("GET", "/api/v1/admin/audit?limit=500", null, true).forEach(n -> out.add(new Ops.AuditEntry(n.path("id").asLong(),
                instant(n, "occurredAt"), n.path("actor").asText(), n.path("action").asText(),
                n.path("objectType").asText() + " " + n.path("objectId").asText(), n.path("reason").asText(""),
                n.path("hash").asText(""), n.path("previousHash").asText(AuditChain.GENESIS))));
        return out;
    }

    /** La chaîne est recalculée par le serveur sur TOUT le journal (pas seulement les lignes affichées). */
    @Override
    public long auditFirstBroken(List<Ops.AuditEntry> shown) {
        return lastChainBreak;
    }

    private static Instant instant(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return v == null || v.isEmpty() || "null".equals(v) ? null : Instant.parse(v);
    }

    private JsonNode sendWithHeader(String method, String path, String header, String value) {
        extraHeader = new String[]{header, value};
        try {
            return send(method, path, null, true);
        } finally {
            extraHeader = null;
        }
    }

    private volatile String[] extraHeader;

    static Vehicle toVehicle(JsonNode n) {
        VehicleStatus status;
        try {
            status = VehicleStatus.valueOf(n.path("status").asText("AVAILABLE"));
        } catch (IllegalArgumentException e) {
            status = VehicleStatus.OUT_OF_SERVICE;
        }
        return new Vehicle(UUID.fromString(n.path("id").asText()), n.path("registration").asText(),
                n.path("make").asText(), n.path("model").asText(),
                n.hasNonNull("modelYear") ? n.get("modelYear").asInt() : null, n.path("energy").asText(null),
                status, null, null, null, null,
                n.hasNonNull("tankCapacityLiters") ? new BigDecimal(n.get("tankCapacityLiters").asText()) : null,
                null, n.path("vehicleType").asText(null), n.path("version").asLong());
    }

    private JsonNode send(String method, String path, JsonNode body, boolean auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("X-Request-ID", UUID.randomUUID().toString());
        if (auth) {
            if (token == null) throw new GatewayException(GatewayException.Kind.UNAUTHORIZED, "Session expirée. Reconnectez-vous.");
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (body != null) b.header("Content-Type", "application/json");
        String[] extra = extraHeader;
        if (extra != null) b.header(extra[0], extra[1]);
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Kind.NETWORK,
                    "Serveur injoignable. Vérifiez la connexion réseau ; aucune donnée n'a été modifiée.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(GatewayException.Kind.NETWORK, "Requête interrompue.");
        }
        int s = res.statusCode();
        JsonNode payload;
        try {
            payload = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Kind.SERVER, "Réponse du serveur illisible (" + s + ").");
        }
        if (s >= 200 && s < 300) return payload;
        String detail = payload.path("detail").asText("Erreur " + s);
        GatewayException.Kind kind = switch (s) {
            case 400, 422 -> GatewayException.Kind.VALIDATION;
            case 401 -> GatewayException.Kind.UNAUTHORIZED;
            case 403 -> GatewayException.Kind.FORBIDDEN;
            case 404 -> GatewayException.Kind.NOT_FOUND;
            case 409 -> GatewayException.Kind.CONFLICT;
            default -> GatewayException.Kind.SERVER;
        };
        if (kind == GatewayException.Kind.UNAUTHORIZED) token = null;
        throw new GatewayException(kind, detail);
    }
}
