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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Passerelle vers l'API NexFleet (contrat shared/api-contract/openapi.yaml, lot 1).
 * HTTPS imposé hors poste de développement ; le jeton reste en mémoire et n'est jamais écrit sur disque.
 * STATUT : compilée et testée contre un faux serveur HTTP local ; non encore éprouvée contre l'API Spring Boot réelle.
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
