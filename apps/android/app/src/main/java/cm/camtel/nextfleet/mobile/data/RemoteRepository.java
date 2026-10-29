package cm.camtel.nextfleet.mobile.data;

import cm.camtel.nextfleet.domain.chat.Chat;
import cm.camtel.nextfleet.mobile.data.Models.ChatLine;
import cm.camtel.nextfleet.mobile.data.Models.Conversation;
import cm.camtel.nextfleet.mobile.data.Models.Mission;
import cm.camtel.nextfleet.mobile.data.Models.MissionStatus;
import cm.camtel.nextfleet.mobile.data.Models.StartResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Client de l'API NexFleet (contrat openapi.yaml + openapi-lot2.yaml) : HTTPS, jeton de session opaque,
 * identifiant de requête (X-Request-ID), délais courts adaptés aux réseaux mobiles, erreurs RFC 9457 traduites.
 */
public final class RemoteRepository implements Repository {

    private final String apiUrl;
    private volatile Session session;

    public RemoteRepository(String apiUrl) {
        String u = apiUrl.trim();
        this.apiUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    public String apiUrl() {
        return apiUrl;
    }

    @Override
    public boolean isDemo() {
        return false;
    }

    @Override
    public Session login(String login, char[] password) {
        try {
            JSONObject body = new JSONObject().put("login", login).put("password", new String(password)).put("clientKind", "ANDROID");
            Arrays.fill(password, '\0');
            JSONObject r = new JSONObject(call("POST", "/api/v1/auth/login", body.toString(), null));
            JSONObject u = r.getJSONObject("user");
            List<String> roles = new ArrayList<>();
            JSONArray ra = u.optJSONArray("roles");
            if (ra != null) for (int i = 0; i < ra.length(); i++) roles.add(ra.getJSONObject(i).getString("role"));
            session = new Session(r.getString("token"), u.optString("login", login), u.optString("displayName", login), roles, false);
            // nouvelle connexion : aucune donnée d'une session précédente (autre agent sur le même téléphone)
            store().clear();
            offlineSince = null;
            session.mustChangePassword = u.optBoolean("mustChangePassword", false);
            return session;
        } catch (JSONException e) {
            throw new RepositoryException(RepositoryException.Kind.SERVER, "Réponse du serveur illisible.");
        }
    }

    @Override
    public void logout() {
        Session s = session;
        session = null;
        offlineSince = null;
        store().clear();
        if (s != null) call("POST", "/api/v1/auth/logout", null, s.token);
    }

    private String token() {
        Session s = session;
        if (s == null) throw new RepositoryException(RepositoryException.Kind.UNAUTHORIZED, "Session expirée. Reconnectez-vous.");
        return s.token;
    }

    @Override
    public List<Mission> missions() {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/missions", null, token()));
            List<Mission> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(mission(a.getJSONObject(i)));
            return out;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public StartResult start(String missionId, String noticeVersion, String deviceId) {
        try {
            JSONObject body = new JSONObject().put("noticeVersion", noticeVersion).put("deviceId", deviceId);
            JSONObject r = new JSONObject(call("POST", "/api/v1/missions/" + missionId + "/start", body.toString(), token()));
            return new StartResult(mission(r.getJSONObject("mission")), r.getString("missionToken"), r.optString("gatewayUrl", null));
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public Mission finish(String missionId) {
        try {
            return mission(new JSONObject(call("POST", "/api/v1/missions/" + missionId + "/finish", null, token())));
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public List<Conversation> conversations() {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/conversations", null, token()));
            List<Conversation> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.getJSONObject(i);
                out.add(new Conversation(c.getString("id"), Chat.Kind.valueOf(c.optString("kind", "DIRECT")), c.optString("title", ""),
                        c.optString("subtitle", ""), c.optString("lastBody", null), instant(c, "lastAt"), c.optLong("unread", 0)));
            }
            return out;
        } catch (JSONException | IllegalArgumentException e) {
            throw unreadable();
        }
    }

    @Override
    public List<ChatLine> messages(String conversationId) {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/conversations/" + conversationId + "/messages", null, token()));
            List<ChatLine> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(line(a.getJSONObject(i)));
            return out;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public ChatLine send(String conversationId, String clientMessageId, String body) {
        try {
            JSONObject b = new JSONObject().put("clientMessageId", clientMessageId).put("body", body);
            JSONObject ack = new JSONObject(call("POST", "/api/v1/conversations/" + conversationId + "/messages", b.toString(), token()));
            return new ChatLine(ack.getLong("serverSeq"), "Vous", body, Instant.now(), true, Chat.DeliveryState.ENVOYE, clientMessageId);
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public void markRead(String conversationId) {
        call("POST", "/api/v1/conversations/" + conversationId + "/read", null, token());
    }

    // ------------------------------------------------------------------ conversion

    static Mission mission(JSONObject m) throws JSONException {
        return new Mission(m.getString("id"), m.getString("reference"), m.optString("purpose", ""), m.optString("origin", ""),
                m.optString("destination", ""), m.optString("registration", ""), m.optString("vehicleLabel", ""),
                m.optString("category", ""), m.optString("driverName", ""), MissionStatus.valueOf(m.getString("status")),
                instant(m, "plannedStart"), instant(m, "startedAt"), instant(m, "finishedAt"), m.optString("deviceId", null),
                m.optString("conversationId", null));
    }

    static ChatLine line(JSONObject l) throws JSONException {
        String state = l.optString("state", null);
        List<Models.Attachment> files = new ArrayList<>();
        JSONArray at = l.optJSONArray("attachments");
        if (at != null) {
            for (int i = 0; i < at.length(); i++) {
                JSONObject a = at.getJSONObject(i);
                files.add(new Models.Attachment(a.getString("id"), a.optString("name", "pièce"), a.optString("mimeType", ""), a.optLong("sizeBytes", 0)));
            }
        }
        return new ChatLine(l.getLong("serverSeq"), l.optString("senderName", ""), l.getString("body"), instant(l, "createdAt"),
                l.optBoolean("mine", false), state == null || state.isEmpty() || "null".equals(state) ? null : Chat.DeliveryState.valueOf(state),
                l.optString("clientMessageId", ""), files);
    }

    @Override
    public List<Models.VehicleLive> fleetLive() {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/fleet/live", null, token()));
            List<Models.VehicleLive> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(vehicleLive(a.getJSONObject(i)));
            return out;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    static Models.VehicleLive vehicleLive(JSONObject n) throws JSONException {
        return new Models.VehicleLive(n.getString("vehicleId"), n.optString("registration", ""), n.optString("vehicleLabel", ""),
                n.optString("missionRef", ""), n.optString("driverName", ""), n.optString("destination", ""),
                n.optString("state", "SIGNAL_ANCIEN"), n.optString("stateLabel", ""), n.getDouble("lat"), n.getDouble("lon"),
                n.isNull("speedKmh") || !n.has("speedKmh") ? null : n.getDouble("speedKmh"),
                n.isNull("distanceKm") || !n.has("distanceKm") ? null : n.getDouble("distanceKm"), instant(n, "recordedAt"));
    }

    /** Canal WebSocket du serveur (OkHttp, déjà embarqué par MapLibre) avec reconnexion progressive. */
    @Override
    public AutoCloseable live(java.util.function.Consumer<Models.LiveEvent> onEvent, java.util.function.Consumer<Boolean> onConnection) {
        String ws = apiUrl.replaceFirst("^http", "ws").replaceAll("/+$", "") + "/api/v1/live";
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).build();
        java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<okhttp3.WebSocket> current = new java.util.concurrent.atomic.AtomicReference<>();
        android.os.Handler retry = new android.os.Handler(android.os.Looper.getMainLooper());
        long[] backoff = {1000};
        Runnable[] connect = new Runnable[1];
        connect[0] = () -> {
            if (!open.get()) return;
            okhttp3.Request req = new okhttp3.Request.Builder().url(ws).header("Authorization", "Bearer " + token()).build();
            current.set(client.newWebSocket(req, new okhttp3.WebSocketListener() {
                @Override
                public void onOpen(okhttp3.WebSocket w, okhttp3.Response r) {
                    backoff[0] = 1000;
                    onConnection.accept(true);
                }

                @Override
                public void onMessage(okhttp3.WebSocket w, String text) {
                    try {
                        JSONObject e = new JSONObject(text);
                        if (!"position".equals(e.optString("type"))) return;
                        onEvent.accept(new Models.LiveEvent(e.getString("vehicleId"), e.getDouble("lat"), e.getDouble("lon"),
                                e.has("speedKmh") && !e.isNull("speedKmh") ? e.getDouble("speedKmh") : null, instant(e, "recordedAt")));
                    } catch (JSONException | RuntimeException ignored) {
                        // message illisible : ignoré
                    }
                }

                @Override
                public void onClosed(okhttp3.WebSocket w, int code, String reason) {
                    reconnect();
                }

                @Override
                public void onFailure(okhttp3.WebSocket w, Throwable t, okhttp3.Response r) {
                    reconnect();
                }

                private void reconnect() {
                    onConnection.accept(false);
                    if (!open.get()) return;
                    retry.postDelayed(connect[0], backoff[0]);
                    backoff[0] = Math.min(backoff[0] * 2, 30_000);
                }
            }));
        };
        connect[0].run();
        return () -> {
            open.set(false);
            retry.removeCallbacksAndMessages(null);
            okhttp3.WebSocket w = current.get();
            if (w != null) w.close(1000, "fermeture");
            client.dispatcher().executorService().shutdown();
        };
    }

    // ------------------------------------------------------------------ production

    @Override
    public void changePassword(char[] current, char[] next) {
        try {
            JSONObject b = new JSONObject().put("currentPassword", new String(current)).put("newPassword", new String(next));
            Arrays.fill(current, '\0');
            Arrays.fill(next, '\0');
            call("POST", "/api/v1/me/password", b.toString(), token());
            Session s = session;
            if (s != null) s.mustChangePassword = false;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public List<Models.Contact> contacts() {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/conversations/contacts", null, token()));
            List<Models.Contact> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.getJSONObject(i);
                out.add(new Models.Contact(c.getString("userId"), c.optString("displayName", ""), c.optString("role", ""), c.optString("orgUnit", "")));
            }
            return out;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public Conversation openConversation(List<String> userIds, String title) {
        try {
            JSONObject b = new JSONObject().put("participantIds", new JSONArray(userIds));
            if (title != null && !title.trim().isEmpty()) b.put("title", title.trim());
            JSONObject c = new JSONObject(call("POST", "/api/v1/conversations", b.toString(), token()));
            return new Conversation(c.getString("id"), Chat.Kind.valueOf(c.optString("kind", "DIRECT")), c.optString("title", ""),
                    c.optString("subtitle", ""), c.optString("lastBody", null), instant(c, "lastAt"), c.optLong("unread", 0));
        } catch (JSONException | IllegalArgumentException e) {
            throw unreadable();
        }
    }

    @Override
    public ChatLine send(String conversationId, String clientMessageId, String body, List<String> attachmentIds) {
        try {
            JSONObject b = new JSONObject().put("clientMessageId", clientMessageId).put("body", body);
            if (attachmentIds != null && !attachmentIds.isEmpty()) b.put("attachmentIds", new JSONArray(attachmentIds));
            JSONObject ack = new JSONObject(call("POST", "/api/v1/conversations/" + conversationId + "/messages", b.toString(), token()));
            return new ChatLine(ack.getLong("serverSeq"), "Vous", body, Instant.now(), true, Chat.DeliveryState.ENVOYE, clientMessageId);
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public String upload(String purpose, String vehicleId, String fileName, byte[] content) {
        if (content.length > 10 * 1024 * 1024) throw new RepositoryException(RepositoryException.Kind.VALIDATION, "Fichier trop volumineux (10 Mo maximum).");
        String boundary = "nf" + UUID.randomUUID().toString().replace("-", "");
        String name = (fileName == null || fileName.isEmpty() ? "piece" : fileName).replace("\"", "_").replace("\r", "").replace("\n", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 512);
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(head, 0, head.length);
        out.write(content, 0, content.length);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(tail, 0, tail.length);
        String q = "?purpose=" + purpose + (vehicleId == null ? "" : "&vehicleId=" + vehicleId);
        try {
            JSONObject r = new JSONObject(new String(raw("POST", "/api/v1/files" + q, out.toByteArray(), "multipart/form-data; boundary=" + boundary),
                    StandardCharsets.UTF_8));
            return r.getString("id");
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public byte[] download(String fileId) {
        return raw("GET", "/api/v1/files/" + fileId + "/content", null, null);
    }

    @Override
    public AutoCloseable messagesLive(java.util.function.Consumer<String> onConversation) {
        String ws = apiUrl.replaceFirst("^http", "ws").replaceAll("/+$", "") + "/api/v1/live";
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).build();
        java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<okhttp3.WebSocket> current = new java.util.concurrent.atomic.AtomicReference<>();
        android.os.Handler retry = new android.os.Handler(android.os.Looper.getMainLooper());
        long[] backoff = {1000};
        Runnable[] connect = new Runnable[1];
        connect[0] = () -> {
            if (!open.get() || session == null) return;
            okhttp3.Request req = new okhttp3.Request.Builder().url(ws).header("Authorization", "Bearer " + token()).build();
            current.set(client.newWebSocket(req, new okhttp3.WebSocketListener() {
                @Override
                public void onOpen(okhttp3.WebSocket w, okhttp3.Response r) {
                    backoff[0] = 1000;
                }

                @Override
                public void onMessage(okhttp3.WebSocket w, String text) {
                    try {
                        JSONObject e = new JSONObject(text);
                        if ("message".equals(e.optString("type"))) {
                            String conv = e.getString("conversationId");
                            retry.post(() -> onConversation.accept(conv));
                        }
                    } catch (JSONException | RuntimeException ignored) {
                        // message illisible : ignoré
                    }
                }

                @Override
                public void onClosed(okhttp3.WebSocket w, int code, String reason) {
                    again();
                }

                @Override
                public void onFailure(okhttp3.WebSocket w, Throwable t, okhttp3.Response r) {
                    again();
                }

                private void again() {
                    if (!open.get()) return;
                    retry.postDelayed(connect[0], backoff[0]);
                    backoff[0] = Math.min(backoff[0] * 2, 30_000);
                }
            }));
        };
        connect[0].run();
        return () -> {
            open.set(false);
            retry.removeCallbacksAndMessages(null);
            okhttp3.WebSocket w = current.get();
            if (w != null) w.close(1000, "fermeture");
            client.dispatcher().executorService().shutdown();
        };
    }

    @Override
    public List<Models.DueRef> vehicleDueDates(String vehicleId) {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/vehicles/" + vehicleId + "/due-dates", null, token()));
            List<Models.DueRef> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject d = a.getJSONObject(i);
                String kind = d.optString("kind", "");
                out.add(new Models.DueRef(kind, dueLabel(kind), java.time.LocalDate.parse(d.getString("dueOn")),
                        d.optLong("daysLeft", 0), d.optString("level", "A_JOUR")));
            }
            return out;
        } catch (JSONException | java.time.format.DateTimeParseException e) {
            throw unreadable();
        }
    }

    static String dueLabel(String kind) {
        switch (kind) {
            case "ASSURANCE": return "Assurance";
            case "VIGNETTE": return "Vignette";
            case "CONTROLE_TECHNIQUE": return "Contrôle technique";
            case "CARTE_GRISE": return "Carte grise";
            case "VIDANGE": return "Vidange";
            default: return "Document";
        }
    }

    @Override
    public List<Models.VehicleRef> vehicles() {
        try {
            JSONObject page = new JSONObject(call("GET", "/api/v1/vehicles?size=200", null, token()));
            JSONArray a = page.getJSONArray("items");
            List<Models.VehicleRef> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject v = a.getJSONObject(i);
                out.add(new Models.VehicleRef(v.getString("id"), v.optString("registration", ""), v.optString("make", "") + " " + v.optString("model", ""),
                        v.optString("status", "")));
            }
            return out;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public List<Models.Supplier> suppliers() {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/suppliers", null, token()));
            List<Models.Supplier> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject v = a.getJSONObject(i);
                if ("GARAGE".equals(v.optString("kind"))) continue;
                out.add(new Models.Supplier(v.getString("id"), v.optString("name", ""), v.optString("kind", "")));
            }
            return out;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public List<Models.FuelOrder> fuelOrders() {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/fuel-orders", null, token()));
            List<Models.FuelOrder> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(fuelOrder(a.getJSONObject(i)));
            return out;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    static Models.FuelOrder fuelOrder(JSONObject o) throws JSONException {
        String until = o.optString("validUntil", null);
        return new Models.FuelOrder(o.getString("id"), o.optString("orderNumber", ""), o.optString("vehicleId", ""), o.optString("registration", ""),
                o.optString("supplierId", ""), o.optString("supplierName", ""), o.optString("status", "DRAFT"), o.optDouble("authorizedLiters", 0),
                o.optDouble("deliveredLiters", 0), until == null || until.isEmpty() || "null".equals(until) ? null : java.time.LocalDate.parse(until));
    }

    @Override
    public Models.FuelOrder requestFuel(String vehicleId, String supplierId, double liters, double amountXaf, java.time.LocalDate validUntil, String reason) {
        try {
            JSONObject b = new JSONObject().put("vehicleId", vehicleId).put("supplierId", supplierId).put("authorizedLiters", liters)
                    .put("authorizedAmountXaf", amountXaf).put("validUntil", validUntil.toString());
            if (reason != null && !reason.trim().isEmpty()) b.put("reason", reason.trim());
            JSONObject created = new JSONObject(call("POST", "/api/v1/fuel-orders", b.toString(), token()));
            String id = created.getString("id");
            call("POST", "/api/v1/fuel-orders/" + id + "/submit", null, token());
            for (Models.FuelOrder o : fuelOrders()) if (o.id.equals(id)) return o;
            throw unreadable();
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public void recordFill(String requestKey, String fuelOrderId, String exceptionReason, String vehicleId, String supplierId, Instant filledAt,
                           Double odometerKm, double liters, double unitPriceXaf, boolean fullTank, String receiptReference, String receiptFileId) {
        try {
            JSONObject b = new JSONObject().put("vehicleId", vehicleId).put("supplierId", supplierId).put("filledAt", filledAt.toString())
                    .put("liters", liters).put("unitPriceXaf", unitPriceXaf).put("fullTank", fullTank).put("receiptReference", receiptReference);
            if (fuelOrderId != null) b.put("fuelOrderId", fuelOrderId);
            if (exceptionReason != null && !exceptionReason.trim().isEmpty()) b.put("exceptionReason", exceptionReason.trim());
            if (odometerKm != null) b.put("odometerKm", odometerKm);
            if (receiptFileId != null) b.put("receiptFileId", receiptFileId);
            extraHeader.set(new String[]{"Idempotency-Key", requestKey});
            try {
                call("POST", "/api/v1/fuel-fills", b.toString(), token());
            } finally {
                extraHeader.remove();
            }
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public List<Models.Category> maintenanceCategories() {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/maintenance/categories", null, token()));
            List<Models.Category> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(new Models.Category(a.getJSONObject(i).getString("code"), a.getJSONObject(i).optString("label", "")));
            return out;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public String requestWork(String vehicleId, String categoryCode, String title) {
        try {
            JSONObject b = new JSONObject().put("vehicleId", vehicleId).put("categoryCode", categoryCode).put("title", title);
            return new JSONObject(call("POST", "/api/v1/maintenance-orders", b.toString(), token())).optString("id", "");
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public List<Models.InspectionPoint> inspectionPoints() {
        try {
            JSONArray a = new JSONArray(call("GET", "/api/v1/inspection-points", null, token()));
            List<Models.InspectionPoint> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                out.add(new Models.InspectionPoint(p.getString("code"), p.optString("label", ""), p.optString("hint", "")));
            }
            return out;
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    @Override
    public String inspect(String vehicleId, String kind, Double odometerKm, java.util.Map<String, String[]> items, String notes) {
        try {
            JSONArray arr = new JSONArray();
            for (java.util.Map.Entry<String, String[]> e : items.entrySet()) {
                JSONObject it = new JSONObject().put("pointCode", e.getKey()).put("state", e.getValue()[0]).put("critical", "1".equals(e.getValue()[2]));
                if (e.getValue()[1] != null && !e.getValue()[1].trim().isEmpty()) it.put("comment", e.getValue()[1].trim());
                arr.put(it);
            }
            JSONObject b = new JSONObject().put("vehicleId", vehicleId).put("kind", kind).put("items", arr);
            if (odometerKm != null) b.put("odometerKm", odometerKm);
            if (notes != null && !notes.trim().isEmpty()) b.put("notes", notes.trim());
            return new JSONObject(call("POST", "/api/v1/inspections", b.toString(), token())).optString("result", "");
        } catch (JSONException e) {
            throw unreadable();
        }
    }

    /** En-tête propre à l'appel en cours : par fil, les écrans lancent plusieurs appels en même temps. */
    private final ThreadLocal<String[]> extraHeader = new ThreadLocal<>();
    /** Lecture depuis le stockage local seulement (affichage instantané avant la réponse du serveur). */
    private final ThreadLocal<Boolean> cacheOnly = new ThreadLocal<>();
    private volatile ResponseStore store;
    private volatile Long offlineSince;

    /** Transport binaire (fichiers) avec le même traitement d'erreurs que {@link #call}. */
    private byte[] raw(String method, String path, byte[] body, String contentType) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(apiUrl + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("X-Request-ID", UUID.randomUUID().toString());
            c.setRequestProperty("Authorization", "Bearer " + token());
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream s = in) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = s.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 12 * 1024 * 1024) throw new IOException("Réponse trop volumineuse");
                    }
                }
            }
            if (code >= 200 && code < 300) return out.toByteArray();
            if (code == 413) throw new RepositoryException(RepositoryException.Kind.VALIDATION, "Fichier trop volumineux (10 Mo maximum).");
            throw error(code, out.toString(StandardCharsets.UTF_8.name()));
        } catch (IOException e) {
            throw new RepositoryException(RepositoryException.Kind.NETWORK, "Serveur injoignable. Vérifiez la connexion ; aucune donnée n'a été modifiée.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static Instant instant(JSONObject o, String field) {
        if (o.isNull(field)) return null;
        String s = o.optString(field, null);
        return s == null || s.isEmpty() ? null : Instant.parse(s);
    }

    private static RepositoryException unreadable() {
        return new RepositoryException(RepositoryException.Kind.SERVER, "Réponse du serveur illisible.");
    }

    // ------------------------------------------------------------------ transport HTTP

    private ResponseStore store() {
        ResponseStore s = store;
        if (s == null) {
            synchronized (this) {
                if (store == null) store = new ResponseStore(cm.camtel.nextfleet.mobile.NexFleetApp.get());
                s = store;
            }
        }
        return s;
    }

    @Override
    public <T> T cached(java.util.function.Supplier<T> read) {
        cacheOnly.set(true);
        try {
            return read.get();
        } catch (RuntimeException e) {
            return null;
        } finally {
            cacheOnly.remove();
        }
    }

    @Override
    public Long offlineSince() {
        return offlineSince;
    }

    private String call(String method, String path, String json, String bearer) {
        boolean read = "GET".equals(method) && bearer != null;
        ResponseStore.Saved saved = read ? store().get(path) : null;
        if (read && Boolean.TRUE.equals(cacheOnly.get())) {
            if (saved == null) throw new RepositoryException(RepositoryException.Kind.NETWORK, "Pas encore de données locales.");
            return saved.body;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(apiUrl + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(8_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-Request-ID", UUID.randomUUID().toString());
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (saved != null && saved.etag != null) c.setRequestProperty("If-None-Match", saved.etag);
            String[] extra = extraHeader.get();
            if (extra != null) c.setRequestProperty(extra[0], extra[1]);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
            } else if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
            }
            int code = c.getResponseCode();
            if (code == 304 && saved != null) {
                offlineSince = null;
                return saved.body;   // inchangé : seuls les en-têtes ont transité
            }
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 200 && code < 300) {
                if (read) {
                    offlineSince = null;
                    if (code == 200 && !text.isEmpty()) store().put(path, c.getHeaderField("ETag"), text);
                }
                return text.isEmpty() ? "{}" : text;
            }
            throw error(code, text);
        } catch (IOException e) {
            if (saved != null) {
                // hors connexion : dernière version connue, signalée comme telle par l'écran (offlineSince)
                Long since = offlineSince;
                offlineSince = since == null ? saved.savedAt : Math.min(since, saved.savedAt);
                return saved.body;
            }
            throw new RepositoryException(RepositoryException.Kind.NETWORK,
                    "Serveur injoignable. Vérifiez la connexion ; aucune donnée n'a été modifiée.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static RepositoryException error(int code, String body) {
        String detail = null;
        try {
            JSONObject p = new JSONObject(body);
            detail = p.optString("detail", null);
        } catch (JSONException ignored) {
            // corps non JSON
        }
        RepositoryException.Kind k;
        switch (code) {
            case 401: k = RepositoryException.Kind.UNAUTHORIZED; break;
            case 403: k = RepositoryException.Kind.FORBIDDEN; break;
            case 404: k = RepositoryException.Kind.NOT_FOUND; break;
            case 409: case 412: k = RepositoryException.Kind.CONFLICT; break;
            case 400: case 422: k = RepositoryException.Kind.VALIDATION; break;
            default: k = RepositoryException.Kind.SERVER;
        }
        String msg = detail != null && !detail.isEmpty() ? detail : k == RepositoryException.Kind.UNAUTHORIZED
                ? "Identifiant ou mot de passe incorrect, ou session expirée." : k == RepositoryException.Kind.FORBIDDEN
                ? "Vous n'avez pas les droits nécessaires." : "Le serveur a refusé la demande (" + code + ").";
        return new RepositoryException(k, msg);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IOException("Réponse trop volumineuse");
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
