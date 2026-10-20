package cm.camtel.nextfleet.gps;

import cm.camtel.nextfleet.domain.gps.GpsFix;
import cm.camtel.nextfleet.gps.GpsIngestionService.Channel;
import cm.camtel.nextfleet.gps.GpsIngestionService.Outcome;
import cm.camtel.nextfleet.gps.GpsIngestionService.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Passerelle GPS NexFleet (service distinct, jamais exposée directement aux clients bureau) :
 * <ul>
 *   <li>{@code POST /gps/traccar/positions} — reçoit le {@code forward.type=json} de Traccar ; authentifiée par
 *       l'en-tête configuré dans {@code forward.header} ({@code Authorization: Bearer …}). Un point refusé reçoit
 *       quand même un 200 : Traccar réessaie sur toute réponse non 2xx et un refus métier ne doit pas être rejoué.</li>
 *   <li>{@code POST /gps/android/fixes} — lot de positions du téléphone, authentifié par le jeton de mission
 *       ({@code Authorization: Mission …}) ; seuls les points acceptés sont relayés à Traccar (OsmAnd).</li>
 * </ul>
 */
public final class GpsGatewayServer {

    private static final Logger LOG = Logger.getLogger(GpsGatewayServer.class.getName());
    private static final int MAX_BODY = 256 * 1024;
    private static final int MAX_FIXES = 500;

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final TraccarForwardParser parser = new TraccarForwardParser();
    private final GpsIngestionService ingestion;
    private final byte[] traccarSecret;
    private final MissionToken tokens;
    private final OsmAndRelay relay;
    private final Clock clock;

    public GpsGatewayServer(InetSocketAddress bind, GpsIngestionService ingestion, String traccarSecret,
                            MissionToken tokens, OsmAndRelay relay, Clock clock) throws IOException {
        if (traccarSecret == null || traccarSecret.length() < 24) throw new IllegalArgumentException("Secret Traccar de 24 caractères au moins");
        this.ingestion = ingestion;
        this.traccarSecret = traccarSecret.getBytes(StandardCharsets.UTF_8);
        this.tokens = tokens;
        this.relay = relay;
        this.clock = clock;
        server = HttpServer.create(bind, 0);
        server.createContext("/gps/traccar/positions", this::traccar);
        server.createContext("/gps/android/fixes", this::android);
        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ Traccar → NexFleet

    private void traccar(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                reply(ex, 405, error("Méthode non autorisée"));
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] given = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).strip().getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (!MessageDigest.isEqual(given, traccarSecret)) {
                reply(ex, 401, error("Authentification Traccar invalide"));
                return;
            }
            String body = readBody(ex);
            if (body == null) {
                reply(ex, 413, error("Corps trop volumineux"));
                return;
            }
            TraccarForwardParser.TraccarPosition p;
            try {
                p = parser.parse(body);
            } catch (TraccarForwardParser.InvalidPayload e) {
                reply(ex, 400, error(e.getMessage()));
                return;
            }
            if (!p.valid()) {   // fix GPS déclaré invalide par l'émetteur : ni stocké ni rejoué
                reply(ex, 200, outcome(new Outcome(Status.REJECTED, cm.camtel.nextfleet.domain.gps.MissionTrackingGate.Reason.DONNEE_INVALIDE, null)));
                return;
            }
            Outcome o = ingestion.ingest(p.uniqueId(), p.fix(), p.courseDeg(), "traccar:" + p.protocol(),
                    Channel.TRACCAR_FORWARD, Optional.empty());
            reply(ex, 200, outcome(o));
        } catch (java.sql.SQLException | IllegalStateException e) {
            LOG.warning("Base indisponible : " + e.getClass().getSimpleName());
            reply(ex, 503, error("Service momentanément indisponible"));   // Traccar réessaiera
        }
    }

    // ------------------------------------------------------------------ Android → NexFleet → Traccar

    private void android(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                reply(ex, 405, error("Méthode non autorisée"));
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            MissionToken.Claims claims;
            try {
                claims = tokens.verify(auth != null && auth.startsWith("Mission ") ? auth.substring(8).strip() : null, clock.instant());
            } catch (MissionToken.InvalidToken e) {
                reply(ex, 401, error("Jeton de mission refusé : " + e.getMessage()));
                return;
            }
            String body = readBody(ex);
            if (body == null) {
                reply(ex, 413, error("Lot trop volumineux"));
                return;
            }
            JsonNode fixes;
            try {
                fixes = json.readTree(body).path("fixes");
            } catch (IOException e) {
                reply(ex, 400, error("JSON illisible"));
                return;
            }
            if (!fixes.isArray() || fixes.size() > MAX_FIXES) {
                reply(ex, 400, error("Champ « fixes » attendu (500 positions au plus)"));
                return;
            }
            ArrayNode results = json.createArrayNode();
            int relayed = 0;
            int relayFailures = 0;
            for (JsonNode f : fixes) {
                ObjectNode r = results.addObject();
                String clientFixId = f.path("clientFixId").asText("");
                r.put("clientFixId", clientFixId);
                GpsFix fix;
                Double bearing = f.path("bearingDeg").isNumber() ? f.path("bearingDeg").asDouble() : null;
                try {
                    if (clientFixId.isBlank() || clientFixId.length() > 80) throw new IllegalArgumentException("clientFixId");
                    fix = new GpsFix(clientFixId, Instant.parse(f.path("recordedAt").asText()), f.path("lat").asDouble(Double.NaN),
                            f.path("lon").asDouble(Double.NaN), f.path("accuracyM").asDouble(-1),
                            f.path("speedMps").isNumber() ? f.path("speedMps").asDouble() : null);
                    if (Double.isNaN(fix.latitude()) || Double.isNaN(fix.longitude())) throw new IllegalArgumentException("coordonnées");
                } catch (RuntimeException invalid) {
                    r.put("outcome", "REJECTED").put("reason", "DONNEE_INVALIDE");
                    continue;
                }
                Outcome o = ingestion.ingest(claims.deviceId(), fix, bearing, "android", Channel.ANDROID_GATEWAY,
                        Optional.of(claims.missionId()));
                r.put("outcome", o.status().name());
                if (o.reason() != null) r.put("reason", o.reason().name());
                if (o.status() == Status.ACCEPTED && relay != null) {
                    try {
                        if (relay.send(claims.deviceId(), fix, bearing) / 100 == 2) relayed++;
                        else relayFailures++;
                    } catch (IOException | InterruptedException e) {
                        relayFailures++;   // NexFleet reste la source de vérité ; le relais sera rejoué par lot
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    }
                }
            }
            ObjectNode out = json.createObjectNode();
            out.set("results", results);
            out.put("relayedToTraccar", relayed).put("relayFailures", relayFailures);
            reply(ex, 200, out);
        } catch (java.sql.SQLException | IllegalStateException e) {
            LOG.warning("Base indisponible : " + e.getClass().getSimpleName());
            reply(ex, 503, error("Service momentanément indisponible ; le téléphone conserve son tampon"));
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            return b.length > MAX_BODY ? null : new String(b, StandardCharsets.UTF_8);
        }
    }

    private ObjectNode outcome(Outcome o) {
        ObjectNode n = json.createObjectNode().put("outcome", o.status().name());
        if (o.reason() != null) n.put("reason", o.reason().name());
        if (o.missionReference() != null) n.put("mission", o.missionReference());
        return n;
    }

    private ObjectNode error(String message) {
        return json.createObjectNode().put("error", message);
    }

    private void reply(HttpExchange ex, int code, JsonNode body) throws IOException {
        byte[] b = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }
}
