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
            JSONObject body = new JSONObject().put("login", login).put("password", new String(password));
            Arrays.fill(password, '\0');
            JSONObject r = new JSONObject(call("POST", "/api/v1/auth/login", body.toString(), null));
            JSONObject u = r.getJSONObject("user");
            List<String> roles = new ArrayList<>();
            JSONArray ra = u.optJSONArray("roles");
            if (ra != null) for (int i = 0; i < ra.length(); i++) roles.add(ra.getJSONObject(i).getString("role"));
            session = new Session(r.getString("token"), u.optString("login", login), u.optString("displayName", login), roles, false);
            return session;
        } catch (JSONException e) {
            throw new RepositoryException(RepositoryException.Kind.SERVER, "Réponse du serveur illisible.");
        }
    }

    @Override
    public void logout() {
        Session s = session;
        session = null;
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
        return new ChatLine(l.getLong("serverSeq"), l.optString("senderName", ""), l.getString("body"), instant(l, "createdAt"),
                l.optBoolean("mine", false), state == null || state.isEmpty() || "null".equals(state) ? null : Chat.DeliveryState.valueOf(state),
                l.optString("clientMessageId", ""));
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

    private String call(String method, String path, String json, String bearer) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(apiUrl + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(8_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-Request-ID", UUID.randomUUID().toString());
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
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
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 200 && code < 300) return text.isEmpty() ? "{}" : text;
            throw error(code, text);
        } catch (IOException e) {
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
