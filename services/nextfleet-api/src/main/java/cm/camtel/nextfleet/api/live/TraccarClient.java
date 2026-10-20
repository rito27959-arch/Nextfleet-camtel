package cm.camtel.nextfleet.api.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Client SERVEUR de l'API REST de Traccar (compte de service à droits limités, jeton dans le coffre). Les clients
 * bureau et mobile ne parlent jamais à Traccar. Usages : déclarer automatiquement le téléphone d'une mission comme
 * appareil Traccar (sinon Traccar ignore le relais OsmAnd), lire l'état des boîtiers. Facultatif : sans
 * {@code NF_TRACCAR_URL}, NexFleet fonctionne seul (la passerelle reste la source de vérité).
 */
@Component
public class TraccarClient {

    private static final Logger LOG = LoggerFactory.getLogger(TraccarClient.class);

    public record DeviceStatus(String uniqueId, String name, String status, String lastUpdate, Long positionId) { }

    private final String base;
    private final String token;
    private final String user;
    private final String password;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper mapper;

    public TraccarClient(@Value("${nextfleet.traccar.url:}") String base, @Value("${nextfleet.traccar.token:}") String token,
                         @Value("${nextfleet.traccar.user:}") String user, @Value("${nextfleet.traccar.password:}") String password,
                         ObjectMapper mapper) {
        this.base = base == null ? "" : base.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.mapper = mapper;
    }

    public boolean configured() {
        return !base.isEmpty() && (!token.isEmpty() || (!user.isEmpty() && !password.isEmpty()));
    }

    /**
     * Instance Traccar neuve (aucun utilisateur) : crée le compte de service avec les identifiants du coffre. Traccar
     * n'accepte cette inscription que s'il n'a encore aucun utilisateur (le premier devient administrateur) ; ensuite
     * l'appel échoue sans effet. Réessaie tant que Traccar démarre.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void bootstrapServiceAccount() {
        if (base.isEmpty() || user.isEmpty() || password.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            for (int attempt = 0; attempt < 30; attempt++) {
                try {
                    HttpResponse<String> probe = http.send(req("/api/devices").GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (probe.statusCode() / 100 == 2) {
                        LOG.info("Traccar : compte de service opérationnel");
                        return;
                    }
                    String body = mapper.createObjectNode().put("name", "NexFleet (service)").put("email", user).put("password", password).toString();
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/users")).timeout(Duration.ofSeconds(8))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() / 100 == 2) {
                        LOG.info("Traccar : compte de service créé sur l'instance neuve");
                        return;
                    }
                    LOG.warn("Traccar : compte de service refusé ({}) — créer le compte dans Traccar ou fournir NF_TRACCAR_TOKEN", r.statusCode());
                    return;
                } catch (Exception e) {
                    try {
                        Thread.sleep(10_000);   // Traccar encore en démarrage
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    /** Crée l'appareil s'il n'existe pas ; asynchrone et sans effet bloquant sur le démarrage de mission. */
    public CompletableFuture<Void> ensureDeviceAsync(String uniqueId, String name) {
        if (!configured()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                if (find(uniqueId).isPresent()) return;
                String body = mapper.createObjectNode().put("name", name).put("uniqueId", uniqueId).toString();
                HttpResponse<String> r = http.send(req("/api/devices").POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) LOG.warn("Traccar : création de l'appareil refusée ({})", r.statusCode());
                else LOG.info("Traccar : appareil {} déclaré", uniqueId);
            } catch (Exception e) {
                LOG.warn("Traccar injoignable pour déclarer l'appareil : {}", e.getClass().getSimpleName());
            }
        });
    }

    public Optional<DeviceStatus> find(String uniqueId) throws java.io.IOException, InterruptedException {
        HttpResponse<String> r = http.send(req("/api/devices?uniqueId=" + URLEncoder.encode(uniqueId, StandardCharsets.UTF_8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) return Optional.empty();
        for (JsonNode d : mapper.readTree(r.body())) {
            if (uniqueId.equals(d.path("uniqueId").asText())) return Optional.of(status(d));
        }
        return Optional.empty();
    }

    /** État de tous les appareils visibles par le compte de service (vide si Traccar absent ou injoignable). */
    public Map<String, DeviceStatus> devices() {
        Map<String, DeviceStatus> out = new HashMap<>();
        if (!configured()) return out;
        try {
            HttpResponse<String> r = http.send(req("/api/devices").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) for (JsonNode d : mapper.readTree(r.body())) out.put(d.path("uniqueId").asText(), status(d));
        } catch (Exception e) {
            LOG.warn("Traccar injoignable : {}", e.getClass().getSimpleName());
        }
        return out;
    }

    private static DeviceStatus status(JsonNode d) {
        return new DeviceStatus(d.path("uniqueId").asText(), d.path("name").asText(), d.path("status").asText("unknown"),
                d.path("lastUpdate").asText(null), d.hasNonNull("positionId") ? d.get("positionId").asLong() : null);
    }

    private HttpRequest.Builder req(String path) {
        String auth = !token.isEmpty() ? "Bearer " + token
                : "Basic " + java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8))
                .header("Authorization", auth).header("Accept", "application/json");
    }
}
