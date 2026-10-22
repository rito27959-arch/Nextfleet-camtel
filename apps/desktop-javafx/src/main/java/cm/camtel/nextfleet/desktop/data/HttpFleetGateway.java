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

    private volatile URI base;
    private final HttpClient http;
    /** Réponses GET déjà reçues, revalidées par ETag (304 : rien ne retransite). Vidé à chaque changement de session. */
    private final EtagCache etags = new EtagCache(256);
    private final ObjectMapper json = new ObjectMapper();
    private volatile String token;
    private volatile boolean mustChangePassword;

    public HttpFleetGateway(URI base) {
        this.base = validate(base);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** HTTPS imposé hors poste local (SEC-03) ; l'adresse est ramenée à sa racine. */
    public static URI validate(URI base) {
        if (base == null || base.getHost() == null) throw new IllegalArgumentException("Adresse de serveur invalide.");
        boolean local = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost());
        if (!"https".equals(base.getScheme()) && !local) {
            throw new IllegalArgumentException("HTTPS obligatoire pour un serveur distant (SEC-03) : " + base);
        }
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + "/");
    }

    public URI base() {
        return base;
    }

    /** Change de serveur (avant connexion uniquement). */
    public void setBase(URI newBase) {
        if (token != null) throw new IllegalStateException("Déconnectez-vous avant de changer de serveur.");
        this.base = validate(newBase);
    }

    /** Vérifie que le serveur répond (route publique /actuator/health). */
    public boolean ping() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("/actuator/health")).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("UP");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        etags.clear();
        ViewCache.reset(true);
        JsonNode u = r.path("user");
        mustChangePassword = u.path("mustChangePassword").asBoolean(false);
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
                mustChangePassword = false;
                etags.clear();
                ViewCache.reset(false);
            }
        }
    }

    @Override
    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    @Override
    public void changePassword(char[] current, char[] next) {
        ObjectNode b = json.createObjectNode().put("currentPassword", new String(current)).put("newPassword", new String(next));
        java.util.Arrays.fill(current, '\0');
        java.util.Arrays.fill(next, '\0');
        send("POST", "/api/v1/me/password", b, true);
        mustChangePassword = false;
    }

    @Override
    public DashboardData dashboard() {
        JsonNode d = send("GET", "/api/v1/dashboard", null, true);
        List<Models.MonthlyConsumption> series = new ArrayList<>();
        d.path("consumptionSeries").forEach(m -> series.add(new Models.MonthlyConsumption(java.time.YearMonth.parse(m.path("month").asText()),
                dec(m, "litersPer100Km"), m.path("reliability").asText())));
        List<Models.Alert> alerts = new ArrayList<>();
        d.path("alerts").forEach(a -> {
            Models.AlertKind k;
            try {
                k = Models.AlertKind.valueOf(a.path("kind").asText());
            } catch (IllegalArgumentException e) {
                k = Models.AlertKind.MAINTENANCE;
            }
            alerts.add(new Models.Alert(k, a.path("title").asText(), a.path("detail").asText(), a.path("due").asText(""), a.path("severity").asText("info")));
        });
        List<Vehicle> recent = new ArrayList<>();
        d.path("recentVehicles").forEach(v -> recent.add(toVehicle(v)));
        return new DashboardData(d.path("activeVehicles").asInt(), d.path("totalVehicles").asInt(), d.path("upcomingMaintenance").asInt(),
                d.path("maintenanceDelta").asInt(), dec(d, "avgConsumption"), dec(d, "consumptionTrendPercent"),
                d.path("consumptionNote").asText(""), dec(d, "monthCostXaf"), dec(d, "costTrendPercent"),
                java.time.YearMonth.parse(d.path("period").asText()), series, alerts, recent, instant(d, "refreshedAt"));
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

    /** Création dans l'entité choisie (ou la seule entité où l'on peut créer un véhicule). */
    @Override
    public Vehicle createVehicle(VehicleDraft d) {
        return createVehicle(d, null, null);
    }

    public Vehicle createVehicle(VehicleDraft d, UUID orgUnitId, BigDecimal odometerKm) {
        UUID org = orgUnitId;
        if (org == null) {
            List<Prod.OrgUnit> units = orgUnits("vehicle:write");
            if (units.isEmpty()) throw new GatewayException(GatewayException.Kind.FORBIDDEN, "Vous ne pouvez créer de véhicule dans aucune entité.");
            org = units.get(0).id();
        }
        ObjectNode b = json.createObjectNode().put("registration", d.registration()).put("make", d.make()).put("model", d.model())
                .put("orgUnitId", org.toString()).put("tankCapacityVerified", d.tankCapacityLiters() != null);
        if (d.vin() != null && !d.vin().isBlank()) b.put("vin", d.vin());
        if (d.modelYear() != null) b.put("modelYear", d.modelYear());
        if (d.energy() != null) b.put("energy", d.energy());
        if (d.tankCapacityLiters() != null) b.put("tankCapacityLiters", d.tankCapacityLiters());
        if (d.category() != null) b.put("category", d.category());
        if (odometerKm != null) b.put("odometerKm", odometerKm);
        return toVehicle(send("POST", "/api/v1/vehicles", b, true));
    }

    @Override
    public List<Models.Driver> drivers(String query) {
        String q = query == null || query.isBlank() ? "" : "?q=" + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
        List<Models.Driver> out = new ArrayList<>();
        send("GET", "/api/v1/drivers" + q, null, true).forEach(n -> out.add(toDriver(n)));
        return out;
    }

    static Models.Driver toDriver(JsonNode n) {
        List<String> cats = new ArrayList<>();
        n.path("licenseCategories").forEach(c -> cats.add(c.asText()));
        return new Models.Driver(UUID.fromString(n.path("id").asText()), n.path("fullName").asText(), n.path("staffNumber").asText(),
                n.path("phone").asText(null), n.path("licenseNumber").asText(null), cats, date(n, "licenseExpiresOn"),
                date(n, "idCardExpiresOn"), n.path("assignedVehicle").asText(null), n.path("orgUnitName").asText(null),
                n.path("userLogin").asText(null));
    }

    @Override
    public List<Models.DueItem> dueDates(int thresholdDays) {
        List<Models.DueItem> out = new ArrayList<>();
        send("GET", "/api/v1/due-dates?days=" + Math.max(thresholdDays, 0), null, true).forEach(n -> out.add(toDue(n)));
        return out;
    }

    @Override
    public List<Models.DueItem> vehicleDueDates(UUID vehicleId) {
        List<Models.DueItem> out = new ArrayList<>();
        send("GET", "/api/v1/vehicles/" + vehicleId + "/due-dates", null, true).forEach(n -> out.add(toDue(n)));
        return out;
    }

    static Models.DueItem toDue(JsonNode n) {
        Models.DueKind k;
        try {
            k = Models.DueKind.valueOf(n.path("kind").asText());
        } catch (IllegalArgumentException e) {
            k = Models.DueKind.CARTE_GRISE;
        }
        Models.DueLevel l;
        try {
            l = Models.DueLevel.valueOf(n.path("level").asText());
        } catch (IllegalArgumentException e) {
            l = Models.DueLevel.A_JOUR;
        }
        return new Models.DueItem(k, n.path("subject").asText(), n.path("subjectDetail").asText(""), n.path("vehicle").asBoolean(),
                date(n, "dueOn"), n.path("daysLeft").asLong(), l);
    }

    @Override
    public List<Models.NotificationItem> notifications() {
        List<Models.NotificationItem> out = new ArrayList<>();
        send("GET", "/api/v1/notifications", null, true).forEach(n -> {
            Models.NotificationKind k;
            try {
                k = Models.NotificationKind.valueOf(n.path("kind").asText());
            } catch (IllegalArgumentException e) {
                k = Models.NotificationKind.SYSTEME;
            }
            out.add(new Models.NotificationItem(n.path("id").asText(), k, n.path("title").asText(), n.path("body").asText(),
                    instant(n, "at"), n.path("read").asBoolean(), n.path("targetSection").asText(null), n.path("targetQuery").asText(null)));
        });
        return out;
    }

    @Override
    public void markNotificationsRead(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        ObjectNode b = json.createObjectNode();
        var arr = b.putArray("ids");
        ids.stream().limit(200).forEach(arr::add);
        send("POST", "/api/v1/notifications/read", b, true);
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

    // ------------------------------------------------------------------ suivi GPS en direct

    @Override
    public List<Ops.VehicleLive> fleetLive() {
        List<Ops.VehicleLive> out = new ArrayList<>();
        send("GET", "/api/v1/fleet/live", null, true).forEach(n -> out.add(toVehicleLive(n)));
        return out;
    }

    static Ops.VehicleLive toVehicleLive(JsonNode n) {
        Ops.LiveState st;
        try {
            st = Ops.LiveState.valueOf(n.path("state").asText());
        } catch (IllegalArgumentException e) {
            st = Ops.LiveState.SIGNAL_ANCIEN;
        }
        return new Ops.VehicleLive(n.path("vehicleId").asText(), n.path("registration").asText(), n.path("vehicleLabel").asText(""),
                n.path("category").asText(""), n.path("missionId").asText(), n.path("missionRef").asText(""), n.path("missionStatus").asText(""),
                n.path("driverName").asText(""), n.path("origin").asText(""), n.path("destination").asText(""), n.path("lat").asDouble(),
                n.path("lon").asDouble(), n.path("accuracyM").asDouble(), n.hasNonNull("speedKmh") ? n.get("speedKmh").asDouble() : null,
                n.hasNonNull("bearingDeg") ? n.get("bearingDeg").asDouble() : null, instant(n, "recordedAt"), n.path("ageSeconds").asLong(),
                st, n.path("stateLabel").asText(st.label), instant(n, "startedAt"),
                n.hasNonNull("distanceKm") ? n.get("distanceKm").asDouble() : null, n.path("openAnomalies").asInt());
    }

    /** Canal WebSocket du serveur (jeton en en-tête), reconnexion automatique avec attente progressive. */
    @Override
    public AutoCloseable subscribeLive(java.util.function.Consumer<Ops.LiveEvent> onEvent, java.util.function.Consumer<Boolean> onConnection) {
        return openSocket(e -> {
            if ("position".equals(e.path("type").asText())) {
                onEvent.accept(new Ops.LiveEvent(e.path("vehicleId").asText(), e.path("registration").asText(),
                        e.path("missionRef").asText(""), e.path("lat").asDouble(), e.path("lon").asDouble(),
                        e.hasNonNull("speedKmh") ? e.get("speedKmh").asDouble() : null, instant(e, "recordedAt")));
            }
        }, onConnection);
    }

    /** Signal « nouveau message » (identifiant de conversation seulement) ; le contenu est relu par l'API. */
    @Override
    public AutoCloseable subscribeMessages(java.util.function.Consumer<String> onConversation) {
        return openSocket(e -> {
            if ("message".equals(e.path("type").asText())) onConversation.accept(e.path("conversationId").asText());
        }, ok -> { });
    }

    private AutoCloseable openSocket(java.util.function.Consumer<JsonNode> onEvent, java.util.function.Consumer<Boolean> onConnection) {
        String scheme = "https".equals(base.getScheme()) ? "wss" : "ws";
        URI ws = URI.create(scheme + "://" + base.getRawAuthority() + "/api/v1/live");
        java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<java.net.http.WebSocket> current = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = Thread.ofPlatform().daemon().name("nf-live-ws").start(() -> {
            long backoff = 1000;
            while (open.get()) {
                String tok = token;
                if (tok == null) return;
                java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
                try {
                    java.net.http.WebSocket socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + tok)
                            .connectTimeout(Duration.ofSeconds(8)).buildAsync(ws, new java.net.http.WebSocket.Listener() {
                                final StringBuilder buf = new StringBuilder();

                                @Override
                                public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket w, CharSequence data, boolean last) {
                                    buf.append(data);
                                    if (last) {
                                        try {
                                            onEvent.accept(json.readTree(buf.toString()));
                                        } catch (IOException | RuntimeException ignored) {
                                            // message illisible : ignoré
                                        }
                                        buf.setLength(0);
                                    }
                                    w.request(1);
                                    return null;
                                }

                                @Override
                                public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket w, int code, String reason) {
                                    closed.countDown();
                                    return null;
                                }

                                @Override
                                public void onError(java.net.http.WebSocket w, Throwable error) {
                                    closed.countDown();
                                }
                            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
                    current.set(socket);
                    onConnection.accept(true);
                    backoff = 1000;
                    while (open.get() && !closed.await(25, java.util.concurrent.TimeUnit.SECONDS)) {
                        socket.sendText("ping", true);   // maintient la connexion à travers les proxys
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    // serveur injoignable : on réessaie
                }
                onConnection.accept(false);
                if (!open.get()) return;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    return;
                }
                backoff = Math.min(backoff * 2, 30_000);
            }
        });
        return () -> {
            open.set(false);
            java.net.http.WebSocket w = current.get();
            if (w != null) w.abort();
            t.interrupt();
        };
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
                    state == null ? null : cm.camtel.nextfleet.domain.chat.Chat.DeliveryState.valueOf(state), n.path("clientMessageId").asText(""),
                    attachments(n.path("attachments"))));
        });
        return out;
    }

    private static List<Prod.Attachment> attachments(JsonNode arr) {
        List<Prod.Attachment> l = new ArrayList<>();
        arr.forEach(a -> l.add(new Prod.Attachment(UUID.fromString(a.path("id").asText()), a.path("name").asText(), a.path("mimeType").asText(),
                a.path("sizeBytes").asLong())));
        return l;
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
                    n.path("orgUnit").asText(""), grants, n.path("status").asText(), null, n.path("driverStaffNumber").asText(null)));
        });
        return out;
    }

    @Override
    public Prod.DriverLink attachDriver(String userId, UUID driverId, String staffNumber) {
        ObjectNode b = json.createObjectNode();
        if (driverId != null) b.put("driverId", driverId.toString());
        if (staffNumber != null && !staffNumber.isBlank()) b.put("staffNumber", staffNumber.strip());
        JsonNode r = send("POST", "/api/v1/admin/users/" + userId + "/driver", b, true);
        return new Prod.DriverLink(UUID.fromString(r.path("driverId").asText()), r.path("staffNumber").asText(),
                r.path("fullName").asText(), r.path("created").asBoolean());
    }

    @Override
    public void detachDriver(String userId, String reason) {
        send("POST", "/api/v1/admin/users/" + userId + "/driver/unlink", json.createObjectNode().put("reason", reason), true);
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

    // ------------------------------------------------------------------ production : entités, missions, conducteurs

    @Override
    public List<Prod.OrgUnit> orgUnits(String permission) {
        List<Prod.OrgUnit> out = new ArrayList<>();
        String q = permission == null ? "" : "?permission=" + URLEncoder.encode(permission, StandardCharsets.UTF_8);
        send("GET", "/api/v1/org-units" + q, null, true).forEach(n -> out.add(new Prod.OrgUnit(uuid(n, "id"), n.path("code").asText(),
                n.path("name").asText(), uuid(n, "parentId"), n.path("parentName").asText(null))));
        return out;
    }

    @Override
    public Ops.Mission createMission(Prod.MissionDraft d) {
        ObjectNode b = json.createObjectNode().put("vehicleId", d.vehicleId().toString()).put("driverId", d.driverId().toString())
                .put("purpose", d.purpose()).put("plannedStart", d.plannedStart().toString()).put("plannedEnd", d.plannedEnd().toString());
        if (d.origin() != null) b.put("origin", d.origin());
        if (d.destination() != null) b.put("destination", d.destination());
        return toMission(send("POST", "/api/v1/missions", b, true));
    }

    @Override
    public Ops.Mission authorizeMission(UUID missionId) {
        return toMission(send("POST", "/api/v1/missions/" + missionId + "/authorize", null, true));
    }

    @Override
    public Ops.Mission cancelMission(UUID missionId, String reason) {
        return toMission(send("POST", "/api/v1/missions/" + missionId + "/cancel", json.createObjectNode().put("reason", reason), true));
    }

    @Override
    public void assignDriver(UUID vehicleId, UUID driverId, String reason) {
        ObjectNode b = json.createObjectNode().put("reason", reason);
        if (driverId != null) b.put("driverId", driverId.toString());
        send("POST", "/api/v1/vehicles/" + vehicleId + "/assignment", b, true);
    }

    @Override
    public Models.Driver createDriver(Prod.DriverDraft d) {
        ObjectNode b = json.createObjectNode().put("staffNumber", d.staffNumber()).put("fullName", d.fullName())
                .put("orgUnitId", d.orgUnitId().toString());
        if (d.phone() != null && !d.phone().isBlank()) b.put("phone", d.phone());
        if (d.email() != null && !d.email().isBlank()) b.put("email", d.email());
        if (d.idCardNumber() != null && !d.idCardNumber().isBlank()) b.put("idCardNumber", d.idCardNumber());
        if (d.idCardExpiresOn() != null) b.put("idCardExpiresOn", d.idCardExpiresOn().toString());
        JsonNode created = send("POST", "/api/v1/drivers", b, true);
        if (d.licenseNumber() != null && !d.licenseNumber().isBlank() && d.licenseExpiresOn() != null) {
            ObjectNode l = json.createObjectNode().put("licenseNumber", d.licenseNumber()).put("expiresOn", d.licenseExpiresOn().toString());
            var cats = l.putArray("categories");
            (d.licenseCategories() == null || d.licenseCategories().isEmpty() ? List.of("B") : d.licenseCategories()).forEach(cats::add);
            created = send("POST", "/api/v1/drivers/" + created.path("id").asText() + "/licenses", l, true);
        }
        return toDriver(created);
    }

    @Override
    public List<Prod.VehicleDocument> vehicleDocuments(UUID vehicleId) {
        List<Prod.VehicleDocument> out = new ArrayList<>();
        send("GET", "/api/v1/vehicles/" + vehicleId + "/documents", null, true).forEach(n -> out.add(new Prod.VehicleDocument(uuid(n, "id"),
                n.path("docType").asText(), n.path("reference").asText(null), date(n, "validFrom"), date(n, "validUntil"), uuid(n, "fileId"),
                n.path("fileName").asText(null))));
        return out;
    }

    @Override
    public Prod.VehicleDocument addVehicleDocument(UUID vehicleId, String docType, String reference, java.time.LocalDate validFrom,
                                                   java.time.LocalDate validUntil, UUID fileId) {
        ObjectNode b = json.createObjectNode().put("docType", docType).put("validUntil", validUntil.toString());
        if (reference != null && !reference.isBlank()) b.put("reference", reference);
        if (validFrom != null) b.put("validFrom", validFrom.toString());
        if (fileId != null) b.put("fileId", fileId.toString());
        JsonNode n = send("POST", "/api/v1/vehicles/" + vehicleId + "/documents", b, true);
        return new Prod.VehicleDocument(uuid(n, "id"), n.path("docType").asText(), n.path("reference").asText(null), date(n, "validFrom"),
                date(n, "validUntil"), uuid(n, "fileId"), n.path("fileName").asText(null));
    }

    // ------------------------------------------------------------------ production : fichiers

    @Override
    public Prod.StoredFile upload(String purpose, UUID vehicleId, java.nio.file.Path file) {
        byte[] content;
        try {
            if (java.nio.file.Files.size(file) > 10L * 1024 * 1024) {
                throw new GatewayException(GatewayException.Kind.VALIDATION, "Fichier trop volumineux (10 Mo maximum).");
            }
            content = java.nio.file.Files.readAllBytes(file);
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Kind.VALIDATION, "Fichier illisible : " + file.getFileName());
        }
        String boundary = "nf" + UUID.randomUUID().toString().replace("-", "");
        String name = file.getFileName().toString().replace("\"", "_").replace("\r", "").replace("\n", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(content.length + 512);
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        out.writeBytes(head);
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        String q = "?purpose=" + purpose + (vehicleId == null ? "" : "&vehicleId=" + vehicleId);
        JsonNode n = parse(raw("POST", "/api/v1/files" + q, HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()),
                "multipart/form-data; boundary=" + boundary, "application/json"));
        return new Prod.StoredFile(uuid(n, "id"), n.path("purpose").asText(), n.path("originalName").asText(), n.path("mimeType").asText(),
                n.path("sizeBytes").asLong(), n.path("sha256").asText());
    }

    @Override
    public byte[] download(UUID fileId) {
        return raw("GET", "/api/v1/files/" + fileId + "/content", HttpRequest.BodyPublishers.noBody(), null, "*/*").body();
    }

    // ------------------------------------------------------------------ production : atelier

    @Override
    public List<Prod.Category> maintenanceCategories() {
        List<Prod.Category> out = new ArrayList<>();
        send("GET", "/api/v1/maintenance/categories", null, true).forEach(n -> out.add(new Prod.Category(n.path("code").asText(), n.path("label").asText())));
        return out;
    }

    @Override
    public List<Prod.WorkOrder> workOrders(boolean openOnly) {
        List<Prod.WorkOrder> out = new ArrayList<>();
        send("GET", "/api/v1/maintenance-orders" + (openOnly ? "?open=true" : ""), null, true).forEach(n -> out.add(toWork(n)));
        return out;
    }

    @Override
    public Prod.WorkOrder requestWork(UUID vehicleId, String categoryCode, String title) {
        ObjectNode b = json.createObjectNode().put("vehicleId", vehicleId.toString()).put("categoryCode", categoryCode).put("title", title);
        return toWork(send("POST", "/api/v1/maintenance-orders", b, true));
    }

    @Override
    public Prod.WorkOrder transitionWork(UUID orderId, long version, Prod.WorkTransition t) {
        ObjectNode b = json.createObjectNode().put("status", t.status().name());
        if (t.garageLabel() != null && !t.garageLabel().isBlank()) b.put("garageLabel", t.garageLabel());
        if (t.odometerKm() != null) b.put("odometerKm", t.odometerKm());
        if (t.costXaf() != null) b.put("costXaf", t.costXaf());
        if (t.workDone() != null && !t.workDone().isBlank()) b.put("workDone", t.workDone());
        if (t.proofFileId() != null) b.put("proofFileId", t.proofFileId().toString());
        if (t.reason() != null && !t.reason().isBlank()) b.put("reason", t.reason());
        extraHeader.set(new String[]{"If-Match", "\"" + version + "\""});
        try {
            return toWork(send("POST", "/api/v1/maintenance-orders/" + orderId + "/transition", b, true));
        } finally {
            extraHeader.remove();
        }
    }

    static Prod.WorkOrder toWork(JsonNode n) {
        Prod.WorkStatus st;
        try {
            st = Prod.WorkStatus.valueOf(n.path("status").asText());
        } catch (IllegalArgumentException e) {
            st = Prod.WorkStatus.DEMANDE;
        }
        return new Prod.WorkOrder(uuid(n, "id"), uuid(n, "vehicleId"), n.path("registration").asText(), n.path("vehicleLabel").asText(""),
                n.path("categoryCode").asText(), n.path("categoryLabel").asText(), n.path("title").asText(), st, n.path("garageLabel").asText(null),
                instant(n, "immobilisedFrom"), instant(n, "immobilisedTo"), dec(n, "odometerKm"), dec(n, "costXaf"), n.path("workDone").asText(null),
                uuid(n, "proofFileId"), n.path("technicalVisaBy").asText(null), n.path("requestedBy").asText(""), instant(n, "createdAt"),
                n.path("version").asLong());
    }

    @Override
    public List<Prod.OilChange> oilChanges() {
        List<Prod.OilChange> out = new ArrayList<>();
        send("GET", "/api/v1/oil-changes", null, true).forEach(n -> out.add(toOil(n)));
        return out;
    }

    @Override
    public Prod.OilChange addOilChange(Prod.OilChangeDraft d) {
        ObjectNode b = json.createObjectNode().put("vehicleId", d.vehicleId().toString()).put("doneOn", d.doneOn().toString())
                .put("odometerKm", d.odometerKm()).put("oilFilter", d.oilFilter()).put("airFilter", d.airFilter()).put("fuelFilter", d.fuelFilter());
        if (d.oilLabel() != null && !d.oilLabel().isBlank()) b.put("oilLabel", d.oilLabel());
        if (d.oilLiters() != null) b.put("oilLiters", d.oilLiters());
        if (d.intervalKm() != null) b.put("intervalKm", d.intervalKm());
        if (d.intervalMonths() != null) b.put("intervalMonths", d.intervalMonths());
        if (d.costXaf() != null) b.put("costXaf", d.costXaf());
        if (d.garageLabel() != null && !d.garageLabel().isBlank()) b.put("garageLabel", d.garageLabel());
        return toOil(send("POST", "/api/v1/oil-changes", b, true));
    }

    static Prod.OilChange toOil(JsonNode n) {
        List<String> f = new ArrayList<>();
        n.path("filters").forEach(x -> f.add(x.asText()));
        return new Prod.OilChange(uuid(n, "id"), uuid(n, "vehicleId"), n.path("registration").asText(), date(n, "doneOn"), dec(n, "odometerKm"),
                n.path("oilLabel").asText(null), f, n.hasNonNull("intervalKm") ? n.get("intervalKm").asInt() : null,
                n.hasNonNull("intervalMonths") ? n.get("intervalMonths").asInt() : null, dec(n, "nextDueKm"), date(n, "nextDueOn"),
                dec(n, "costXaf"), n.path("garageLabel").asText(null), n.path("author").asText(""));
    }

    // ------------------------------------------------------------------ production : carburant

    @Override
    public List<Prod.Supplier> suppliers() {
        List<Prod.Supplier> out = new ArrayList<>();
        send("GET", "/api/v1/suppliers", null, true).forEach(n -> out.add(new Prod.Supplier(uuid(n, "id"), n.path("code").asText(),
                n.path("name").asText(), n.path("kind").asText())));
        return out;
    }

    @Override
    public Prod.Supplier createSupplier(String code, String name, String kind) {
        JsonNode n = send("POST", "/api/v1/suppliers", json.createObjectNode().put("code", code).put("name", name).put("kind", kind), true);
        return new Prod.Supplier(uuid(n, "id"), n.path("code").asText(), n.path("name").asText(), n.path("kind").asText());
    }

    @Override
    public List<Prod.FuelOrder> fuelOrders() {
        List<Prod.FuelOrder> out = new ArrayList<>();
        send("GET", "/api/v1/fuel-orders", null, true).forEach(n -> out.add(toFuelOrder(n)));
        return out;
    }

    static Prod.FuelOrder toFuelOrder(JsonNode n) {
        Prod.FuelOrderStatus st;
        try {
            st = Prod.FuelOrderStatus.valueOf(n.path("status").asText());
        } catch (IllegalArgumentException e) {
            st = Prod.FuelOrderStatus.DRAFT;
        }
        return new Prod.FuelOrder(uuid(n, "id"), n.path("orderNumber").asText(), uuid(n, "vehicleId"), n.path("registration").asText(),
                n.path("supplierName").asText(), n.path("requestedBy").asText(""), n.path("approvedBy").asText(null), dec(n, "authorizedLiters"),
                dec(n, "authorizedAmountXaf"), dec(n, "deliveredLiters"), dec(n, "deliveredAmountXaf"), date(n, "validUntil"), st,
                n.path("reason").asText(null), instant(n, "createdAt"), n.path("mine").asBoolean(), n.path("version").asLong());
    }

    @Override
    public Prod.FuelOrder createFuelOrder(Prod.FuelOrderDraft d, boolean submit) {
        ObjectNode b = json.createObjectNode().put("vehicleId", d.vehicleId().toString()).put("supplierId", d.supplierId().toString())
                .put("authorizedLiters", d.liters()).put("authorizedAmountXaf", d.amountXaf()).put("validUntil", d.validUntil().toString());
        if (d.reason() != null && !d.reason().isBlank()) b.put("reason", d.reason());
        JsonNode created = send("POST", "/api/v1/fuel-orders", b, true);
        String id = created.path("id").asText();
        if (submit) send("POST", "/api/v1/fuel-orders/" + id + "/submit", null, true);
        return fuelOrders().stream().filter(o -> o.id().toString().equals(id)).findFirst()
                .orElseThrow(() -> new GatewayException(GatewayException.Kind.SERVER, "Bon créé mais introuvable dans la liste."));
    }

    @Override
    public void approveFuelOrder(UUID orderId, String comment) {
        send("POST", "/api/v1/fuel-orders/" + orderId + "/approve", json.createObjectNode().put("comment", comment == null ? "" : comment), true);
    }

    @Override
    public void cancelFuelOrder(UUID orderId, String reason) {
        send("POST", "/api/v1/fuel-orders/" + orderId + "/cancel", json.createObjectNode().put("reason", reason), true);
    }

    @Override
    public List<Prod.FuelFill> fuelFills() {
        List<Prod.FuelFill> out = new ArrayList<>();
        send("GET", "/api/v1/fuel-fills", null, true).forEach(n -> out.add(new Prod.FuelFill(uuid(n, "id"), n.path("orderNumber").asText(null),
                uuid(n, "vehicleId"), n.path("registration").asText(), n.path("driverName").asText(null), n.path("supplierName").asText(),
                instant(n, "filledAt"), dec(n, "odometerKm"), dec(n, "liters"), dec(n, "unitPriceXaf"), dec(n, "amountXaf"),
                n.path("fullTank").asBoolean(), n.path("receiptReference").asText(), uuid(n, "receiptFileId"),
                n.path("exceptionReason").asText(null), n.path("enteredBy").asText(""))));
        return out;
    }

    @Override
    public void recordFill(String requestKey, Prod.FillDraft d) {
        ObjectNode b = json.createObjectNode().put("vehicleId", d.vehicleId().toString()).put("supplierId", d.supplierId().toString())
                .put("filledAt", d.filledAt().toString()).put("liters", d.liters()).put("unitPriceXaf", d.unitPriceXaf())
                .put("fullTank", d.fullTank()).put("receiptReference", d.receiptReference());
        if (d.fuelOrderId() != null) b.put("fuelOrderId", d.fuelOrderId().toString());
        if (d.exceptionReason() != null && !d.exceptionReason().isBlank()) b.put("exceptionReason", d.exceptionReason());
        if (d.odometerKm() != null) b.put("odometerKm", d.odometerKm());
        if (d.receiptFileId() != null) b.put("receiptFileId", d.receiptFileId().toString());
        extraHeader.set(new String[]{"Idempotency-Key", requestKey});
        try {
            send("POST", "/api/v1/fuel-fills", b, true);
        } finally {
            extraHeader.remove();
        }
    }

    // ------------------------------------------------------------------ production : coûts et rapports

    @Override
    public Prod.CostSummary costs(int year) {
        JsonNode n = send("GET", "/api/v1/costs?year=" + year, null, true);
        List<Prod.CostMonth> months = new ArrayList<>();
        n.path("months").forEach(m -> months.add(new Prod.CostMonth(m.path("month").asInt(), dec0(m, "fuelXaf"), dec0(m, "maintenanceXaf"),
                dec0(m, "oilXaf"), dec0(m, "totalXaf"), dec0(m, "liters"))));
        List<Prod.CostCategory> cats = new ArrayList<>();
        n.path("categories").forEach(c -> cats.add(new Prod.CostCategory(c.path("code").asText(), c.path("label").asText(), dec0(c, "actualXaf"),
                dec0(c, "budgetXaf"), dec(c, "consumedPercent"))));
        List<Prod.CostVehicle> vehicles = new ArrayList<>();
        n.path("vehicles").forEach(v -> vehicles.add(new Prod.CostVehicle(uuid(v, "vehicleId"), v.path("registration").asText(), v.path("label").asText(),
                dec0(v, "fuelXaf"), dec0(v, "maintenanceXaf"), dec0(v, "totalXaf"), dec0(v, "liters"), v.path("interventions").asInt())));
        return new Prod.CostSummary(n.path("year").asInt(), dec0(n, "totalXaf"), dec0(n, "fuelXaf"), dec0(n, "maintenanceXaf"), dec0(n, "oilXaf"),
                dec0(n, "budgetXaf"), dec0(n, "liters"), months, cats, vehicles);
    }

    @Override
    public Prod.ImportResult importBudget(int year, UUID orgUnitId, String sourceFile, String csv) {
        String q = "?year=" + year + "&orgUnitId=" + orgUnitId + "&sourceFile=" + URLEncoder.encode(sourceFile, StandardCharsets.UTF_8);
        JsonNode n = parse(raw("POST", "/api/v1/budget-lines/import" + q, HttpRequest.BodyPublishers.ofString(csv, StandardCharsets.UTF_8),
                "text/plain; charset=UTF-8", "application/json"));
        List<String> errors = new ArrayList<>();
        n.path("errors").forEach(e -> errors.add(e.asText()));
        return new Prod.ImportResult(n.path("read").asInt(), n.path("inserted").asInt(), n.path("duplicates").asInt(), errors);
    }

    @Override
    public List<Prod.ReportInfo> reports() {
        List<Prod.ReportInfo> out = new ArrayList<>();
        send("GET", "/api/v1/reports", null, true).forEach(n -> out.add(new Prod.ReportInfo(n.path("code").asText(), n.path("title").asText(),
                n.path("description").asText(), n.path("period").asBoolean())));
        return out;
    }

    @Override
    public byte[] exportReport(String code, java.time.LocalDate from, java.time.LocalDate to) {
        String q = "?from=" + from + "&to=" + to;
        return raw("GET", "/api/v1/reports/" + URLEncoder.encode(code, StandardCharsets.UTF_8) + ".csv" + q,
                HttpRequest.BodyPublishers.noBody(), null, "text/csv").body();
    }

    // ------------------------------------------------------------------ production : comptes

    @Override
    public Prod.OrgUnit createOrgUnit(String code, String name, UUID parentId) {
        ObjectNode b = json.createObjectNode().put("code", code).put("name", name);
        if (parentId != null) b.put("parentId", parentId.toString());
        JsonNode n = send("POST", "/api/v1/admin/org-units", b, true);
        return new Prod.OrgUnit(uuid(n, "id"), n.path("code").asText(), n.path("name").asText(), uuid(n, "parentId"), n.path("parentName").asText(null));
    }

    @Override
    public List<Prod.RoleInfo> roles() {
        List<Prod.RoleInfo> out = new ArrayList<>();
        send("GET", "/api/v1/admin/roles", null, true).forEach(n -> {
            List<String> perms = new ArrayList<>();
            n.path("permissions").forEach(p -> perms.add(p.asText()));
            out.add(new Prod.RoleInfo(n.path("code").asText(), n.path("label").asText(), perms));
        });
        return out;
    }

    @Override
    public Prod.CreatedAccount createAccount(Prod.AccountDraft d) {
        ObjectNode b = json.createObjectNode().put("login", d.login()).put("displayName", d.displayName()).put("orgUnitId", d.orgUnitId().toString());
        if (d.email() != null && !d.email().isBlank()) b.put("email", d.email());
        if (d.phone() != null && !d.phone().isBlank()) b.put("phone", d.phone());
        if (d.driverStaffNumber() != null && !d.driverStaffNumber().isBlank()) b.put("driverStaffNumber", d.driverStaffNumber());
        if (d.role() != null) b.putArray("roles").addObject().put("role", d.role()).put("scopeOrgId", d.orgUnitId().toString());
        JsonNode n = send("POST", "/api/v1/admin/users", b, true);
        return new Prod.CreatedAccount(n.path("user").path("id").asText(), n.path("user").path("login").asText(), n.path("temporaryPassword").asText());
    }

    @Override
    public List<Prod.RoleGrant> grants(String userId) {
        List<Prod.RoleGrant> out = new ArrayList<>();
        send("GET", "/api/v1/admin/users/" + UUID.fromString(userId) + "/grants", null, true).forEach(n -> out.add(new Prod.RoleGrant(uuid(n, "id"),
                n.path("role").asText(), n.path("roleLabel").asText(), uuid(n, "scopeOrgId"), n.path("scopeName").asText(), instant(n, "validFrom"),
                instant(n, "validUntil"), n.path("delegation").asBoolean(), instant(n, "revokedAt"), n.path("grantedBy").asText(null))));
        return out;
    }

    @Override
    public void grantRole(String userId, String role, UUID scopeOrgId, Instant validUntil) {
        ObjectNode b = json.createObjectNode().put("role", role).put("delegation", validUntil != null);
        if (scopeOrgId != null) b.put("scopeOrgId", scopeOrgId.toString());
        if (validUntil != null) b.put("validUntil", validUntil.toString());
        send("POST", "/api/v1/admin/users/" + UUID.fromString(userId) + "/grants", b, true);
    }

    @Override
    public void revokeGrant(String userId, UUID grantId, String reason) {
        send("POST", "/api/v1/admin/users/" + UUID.fromString(userId) + "/grants/" + grantId + "/revoke",
                json.createObjectNode().put("reason", reason), true);
    }

    @Override
    public String resetPassword(String userId, String reason) {
        return send("POST", "/api/v1/admin/users/" + UUID.fromString(userId) + "/password-reset",
                json.createObjectNode().put("reason", reason), true).path("temporaryPassword").asText();
    }

    @Override
    public void setAccountStatus(String userId, boolean active, String reason) {
        long version = -1;
        for (JsonNode n : send("GET", "/api/v1/admin/users", null, true)) {
            if (userId.equals(n.path("id").asText())) version = n.path("version").asLong();
        }
        if (version < 0) throw new GatewayException(GatewayException.Kind.NOT_FOUND, "Compte introuvable.");
        send("POST", "/api/v1/admin/users/" + UUID.fromString(userId) + "/status", json.createObjectNode()
                .put("status", active ? "ACTIVE" : "SUSPENDED").put("reason", reason).put("version", version), true);
    }

    // ------------------------------------------------------------------ production : messagerie

    @Override
    public List<Prod.Contact> contacts() {
        List<Prod.Contact> out = new ArrayList<>();
        send("GET", "/api/v1/conversations/contacts", null, true).forEach(n -> out.add(new Prod.Contact(n.path("userId").asText(),
                n.path("displayName").asText(), n.path("role").asText(""), n.path("orgUnit").asText(""))));
        return out;
    }

    @Override
    public Ops.Conversation openConversation(List<String> userIds, String title) {
        ObjectNode b = json.createObjectNode();
        var arr = b.putArray("participantIds");
        userIds.forEach(arr::add);
        if (title != null && !title.isBlank()) b.put("title", title);
        JsonNode n = send("POST", "/api/v1/conversations", b, true);
        cm.camtel.nextfleet.domain.chat.Chat.Kind kind;
        try {
            kind = cm.camtel.nextfleet.domain.chat.Chat.Kind.valueOf(n.path("kind").asText("DIRECT"));
        } catch (IllegalArgumentException e) {
            kind = cm.camtel.nextfleet.domain.chat.Chat.Kind.DIRECT;
        }
        return new Ops.Conversation(n.path("id").asText(), kind, n.path("title").asText(""), n.path("subtitle").asText(""),
                n.path("lastBody").asText(null), null, instant(n, "lastAt"), n.path("unread").asLong(), List.of());
    }

    @Override
    public Ops.ChatLine sendMessage(String conversationId, String clientMessageId, String body, List<UUID> attachmentIds) {
        ObjectNode b = json.createObjectNode().put("clientMessageId", clientMessageId).put("body", body);
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            var arr = b.putArray("attachmentIds");
            attachmentIds.forEach(a -> arr.add(a.toString()));
        }
        JsonNode ack = send("POST", "/api/v1/conversations/" + UUID.fromString(conversationId) + "/messages", b, true);
        return new Ops.ChatLine(ack.path("serverSeq").asLong(), null, "Vous", body, instant(ack, "createdAt"), true,
                cm.camtel.nextfleet.domain.chat.Chat.DeliveryState.ENVOYE, clientMessageId);
    }

    // ------------------------------------------------------------------ utilitaires

    private static UUID uuid(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return v == null || v.isEmpty() || "null".equals(v) ? null : UUID.fromString(v);
    }

    private static java.time.LocalDate date(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return v == null || v.isEmpty() || "null".equals(v) ? null : java.time.LocalDate.parse(v);
    }

    private static BigDecimal dec(JsonNode n, String field) {
        return n.hasNonNull(field) ? new BigDecimal(n.get(field).asText()) : null;
    }

    private static BigDecimal dec0(JsonNode n, String field) {
        BigDecimal v = dec(n, field);
        return v == null ? BigDecimal.ZERO : v;
    }

    private JsonNode parse(HttpResponse<byte[]> r) {
        try {
            byte[] body = r.body();
            return body == null || body.length == 0 ? json.createObjectNode() : json.readTree(body);
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Kind.SERVER, "Réponse du serveur illisible (" + r.statusCode() + ").");
        }
    }

    /** Transport binaire (fichiers, CSV, multipart) avec le même traitement d'erreurs que {@link #send}. */
    private HttpResponse<byte[]> raw(String method, String path, HttpRequest.BodyPublisher body, String contentType, String accept) {
        if (token == null) throw new GatewayException(GatewayException.Kind.UNAUTHORIZED, "Session expirée. Reconnectez-vous.");
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(60))
                .header("Accept", accept).header("X-Request-ID", UUID.randomUUID().toString()).header("Authorization", "Bearer " + token)
                .method(method, body);
        if (contentType != null) b.header("Content-Type", contentType);
        b.header("Accept-Encoding", "gzip");
        HttpResponse<byte[]> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Kind.NETWORK, "Serveur injoignable. Vérifiez la connexion réseau ; aucune donnée n'a été modifiée.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(GatewayException.Kind.NETWORK, "Requête interrompue.");
        }
        int s = res.statusCode();
        byte[] decoded = Compression.decode(res);
        if (s >= 200 && s < 300) {
            if (!"GET".equals(method)) ViewCache.invalidate();
            return Compression.withBody(res, decoded);
        }
        String detail;
        try {
            detail = json.readTree(decoded).path("detail").asText("Erreur " + s);
        } catch (IOException | RuntimeException e) {
            detail = "Erreur " + s;
        }
        GatewayException.Kind kind = switch (s) {
            case 400, 413, 422 -> GatewayException.Kind.VALIDATION;
            case 401 -> GatewayException.Kind.UNAUTHORIZED;
            case 403 -> GatewayException.Kind.FORBIDDEN;
            case 404 -> GatewayException.Kind.NOT_FOUND;
            case 409 -> GatewayException.Kind.CONFLICT;
            default -> GatewayException.Kind.SERVER;
        };
        if (kind == GatewayException.Kind.UNAUTHORIZED) token = null;
        throw new GatewayException(kind, s == 413 ? "Fichier trop volumineux (10 Mo maximum)." : detail);
    }

    private static Instant instant(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return v == null || v.isEmpty() || "null".equals(v) ? null : Instant.parse(v);
    }

    private JsonNode sendWithHeader(String method, String path, String header, String value) {
        extraHeader.set(new String[]{header, value});
        try {
            return send(method, path, null, true);
        } finally {
            extraHeader.remove();
        }
    }

    /** En-tête propre à l'appel en cours (If-Match, Idempotency-Key) : par fil, car les écrans lancent leurs appels en parallèle. */
    private final ThreadLocal<String[]> extraHeader = new ThreadLocal<>();

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
                status, n.path("driverName").asText(null), null, n.path("siteLabel").asText(n.path("orgUnitName").asText(null)),
                dec(n, "odometerKm"),
                n.hasNonNull("tankCapacityLiters") ? new BigDecimal(n.get("tankCapacityLiters").asText()) : null,
                date(n, "nextDueOn"), n.path("category").asText(n.path("vehicleType").asText(null)), n.path("version").asLong());
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
        String[] extra = extraHeader.get();
        if (extra != null) b.header(extra[0], extra[1]);
        b.header("Accept-Encoding", "gzip");
        boolean get = "GET".equals(method);
        String cacheKey = get && auth ? path : null;
        EtagCache.Saved cached = cacheKey == null ? null : etags.get(cacheKey);
        if (cached != null) b.header("If-None-Match", cached.etag());
        HttpResponse<byte[]> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Kind.NETWORK,
                    "Serveur injoignable. Vérifiez la connexion réseau ; aucune donnée n'a été modifiée.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(GatewayException.Kind.NETWORK, "Requête interrompue.");
        }
        int s = res.statusCode();
        byte[] bytes;
        if (s == 304 && cached != null) {
            // contenu inchangé : rien n'a transité sur le réseau hormis l'en-tête
            bytes = cached.body();
            s = 200;
        } else {
            bytes = Compression.decode(res);
            if (cacheKey != null && s == 200) {
                res.headers().firstValue("ETag").ifPresent(tag -> etags.put(cacheKey, tag, bytes));
            }
        }
        JsonNode payload;
        try {
            payload = bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Kind.SERVER, "Réponse du serveur illisible (" + s + ").");
        }
        if (s >= 200 && s < 300) {
            if (!get) ViewCache.invalidate();
            return payload;
        }
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
