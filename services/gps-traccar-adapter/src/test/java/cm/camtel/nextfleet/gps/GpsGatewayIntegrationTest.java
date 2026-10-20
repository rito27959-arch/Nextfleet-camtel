package cm.camtel.nextfleet.gps;

import cm.camtel.nextfleet.domain.gps.MissionToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scénario B du cahier (sans le téléphone réel ni Traccar réel) sur PostgreSQL + PostGIS :
 * mission en cours → positions acceptées → doublons ignorés → refus hors mission → clôture → plus aucune position.
 * Traccar est remplacé par un serveur HTTP local qui enregistre les requêtes OsmAnd reçues.
 */
@EnabledIfSystemProperty(named = "nf.db.url", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GpsGatewayIntegrationTest {

    static final String SECRET = "secret-forward-traccar-de-test-0123456789";
    static final UUID MISSION = UUID.fromString("00000000-0000-0000-0000-000000000201");
    static final UUID MISSION_TOMORROW = UUID.fromString("00000000-0000-0000-0000-000000000202");
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final List<String> traccarReceived = new CopyOnWriteArrayList<>();
    static final MissionToken TOKENS = new MissionToken("cle-hmac-de-test-uniquement-32-octets".getBytes());
    static HttpServer fakeTraccar;
    static GpsGatewayServer gateway;

    static Connection db() {
        try {
            Class.forName("org.postgresql.Driver");
            return DriverManager.getConnection(System.getProperty("nf.db.url"), System.getProperty("nf.db.user"),
                    System.getProperty("nf.db.password"));
        } catch (SQLException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        fakeTraccar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeTraccar.createContext("/", ex -> {
            traccarReceived.add(ex.getRequestURI().getRawQuery());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        fakeTraccar.start();
        gateway = new GpsGatewayServer(new InetSocketAddress("127.0.0.1", 0), new GpsIngestionService(GpsGatewayIntegrationTest::db),
                SECRET, TOKENS, new OsmAndRelay(URI.create("http://127.0.0.1:" + fakeTraccar.getAddress().getPort())),
                Clock.systemUTC());
        gateway.start();
    }

    @AfterAll
    static void stop() {
        gateway.stop();
        fakeTraccar.stop(0);
    }

    static HttpResponse<String> post(String path, String auth, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gateway.port() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String forward(long positionId, String uniqueId, Instant fixTime, double lat, double lon) {
        return TraccarAdapterTest.FORWARD.replace("\"id\":81234", "\"id\":" + positionId)
                .replace("\"uniqueId\":\"AND-7F3K\"", "\"uniqueId\":\"" + uniqueId + "\"")
                .replace("\"fixTime\":\"2026-10-20T08:15:00.000+00:00\"", "\"fixTime\":\"" + fixTime + "\"")
                .replace("\"latitude\":3.5172", "\"latitude\":" + lat).replace("\"longitude\":11.5003", "\"longitude\":" + lon);
    }

    static long count(String sql) throws SQLException {
        try (Connection c = db(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @Order(1)
    void traccarForwardAcceptedThenDuplicateIgnored() throws Exception {
        Instant t = Instant.now().minus(20, ChronoUnit.MINUTES);
        var r = post("/gps/traccar/positions", "Bearer " + SECRET, forward(1001, "AND-7F3K", t, 3.5172, 11.5003));
        assertEquals(200, r.statusCode());
        assertEquals("ACCEPTED", JSON.readTree(r.body()).path("outcome").asText(), r.body());
        assertEquals("MIS-2026-014", JSON.readTree(r.body()).path("mission").asText());
        var again = post("/gps/traccar/positions", "Bearer " + SECRET, forward(1001, "AND-7F3K", t, 3.5172, 11.5003));
        assertEquals("DUPLICATE", JSON.readTree(again.body()).path("outcome").asText());
        assertEquals(1, count("SELECT count(*) FROM location_point"));
        assertEquals(11.11, count("SELECT round(speed_mps * 100) FROM location_point") / 100.0, 0.01, "nœuds convertis en m/s");
    }

    @Test
    @Order(2)
    void traccarAuthenticationAndRefusals() throws Exception {
        assertEquals(401, post("/gps/traccar/positions", "Bearer mauvais-secret", forward(1002, "AND-7F3K", Instant.now(), 3.5, 11.5)).statusCode());
        assertEquals(401, post("/gps/traccar/positions", null, forward(1002, "AND-7F3K", Instant.now(), 3.5, 11.5)).statusCode());
        assertEquals(400, post("/gps/traccar/positions", "Bearer " + SECRET, "{\"position\":{}}").statusCode());
        var unknown = post("/gps/traccar/positions", "Bearer " + SECRET, forward(1003, "TRK-INCONNU", Instant.now(), 3.5, 11.5));
        assertEquals(200, unknown.statusCode(), "un refus métier n'est pas rejoué par Traccar");
        assertEquals("SOURCE_INCONNUE", JSON.readTree(unknown.body()).path("reason").asText());
        var before = post("/gps/traccar/positions", "Bearer " + SECRET,
                forward(1004, "AND-7F3K", Instant.now().minus(2, ChronoUnit.HOURS), 3.5, 11.5));
        assertEquals("HORS_PERIODE", JSON.readTree(before.body()).path("reason").asText());
        assertEquals(1, count("SELECT count(*) FROM location_point"), "aucun point refusé n'est stocké");
        assertEquals(2, count("SELECT count(*) FROM gps_ingest_rejection WHERE channel = 'TRACCAR_FORWARD'"));
    }

    @Test
    @Order(3)
    void androidBatchWithMissionTokenIsFilteredThenRelayedToTraccar() throws Exception {
        String token = TOKENS.issue(MISSION, "AND-7F3K", Instant.now(), Duration.ofHours(8));
        Instant t = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        String body = """
                {"fixes":[
                 {"clientFixId":"and-1","recordedAt":"%s","lat":3.5201,"lon":11.4987,"accuracyM":5,"speedMps":12.5,"bearingDeg":182},
                 {"clientFixId":"and-1","recordedAt":"%s","lat":3.5201,"lon":11.4987,"accuracyM":5},
                 {"clientFixId":"and-0","recordedAt":"%s","lat":3.84,"lon":11.50,"accuracyM":5},
                 {"clientFixId":"and-x","recordedAt":"pas une date","lat":3.5,"lon":11.5,"accuracyM":5}]}"""
                .formatted(t, t, Instant.now().minus(3, ChronoUnit.HOURS));
        var r = post("/gps/android/fixes", "Mission " + token, body);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode res = JSON.readTree(r.body());
        assertEquals("ACCEPTED", res.path("results").get(0).path("outcome").asText());
        assertEquals("DUPLICATE", res.path("results").get(1).path("outcome").asText());
        assertEquals("HORS_PERIODE", res.path("results").get(2).path("reason").asText());
        assertEquals("DONNEE_INVALIDE", res.path("results").get(3).path("reason").asText());
        assertEquals(1, res.path("relayedToTraccar").asInt());
        assertEquals(1, traccarReceived.size(), "seul le point accepté est relayé");
        String q = traccarReceived.get(0);
        assertTrue(q.startsWith("id=AND-7F3K&timestamp=" + t.getEpochSecond() + "&lat=3.5201&lon=11.4987&speed=24.298"), q);
        assertTrue(q.endsWith("&bearing=182&accuracy=5&nfFix=and-1"), q);
        assertEquals(2, count("SELECT count(*) FROM location_point"));
        // Traccar renvoie le point relayé (forward JSON avec l'attribut nfFix) : reconnu comme écho, jamais stocké deux fois
        String echo = forward(1010, "AND-7F3K", t, 3.5201, 11.4987).replace("\"motion\":true}", "\"motion\":true,\"nfFix\":\"and-1\"}");
        var e = post("/gps/traccar/positions", "Bearer " + SECRET, echo);
        assertEquals("DUPLICATE", JSON.readTree(e.body()).path("outcome").asText(), e.body());
        assertEquals(2, count("SELECT count(*) FROM location_point"));
    }

    @Test
    @Order(4)
    void tokenOfAnotherMissionCannotFeedTheActiveOne() throws Exception {
        String other = TOKENS.issue(MISSION_TOMORROW, "AND-7F3K", Instant.now(), Duration.ofHours(8));
        String body = "{\"fixes\":[{\"clientFixId\":\"and-2\",\"recordedAt\":\"" + Instant.now().minusSeconds(60)
                + "\",\"lat\":3.52,\"lon\":11.49,\"accuracyM\":5}]}";
        JsonNode res = JSON.readTree(post("/gps/android/fixes", "Mission " + other, body).body());
        assertEquals("AUCUNE_MISSION_ACTIVE", res.path("results").get(0).path("reason").asText());
        assertEquals(401, post("/gps/android/fixes", "Mission " + other + "x", body).statusCode());
        String expired = TOKENS.issue(MISSION, "AND-7F3K", Instant.now().minus(9, ChronoUnit.HOURS), Duration.ofHours(8));
        assertEquals(401, post("/gps/android/fixes", "Mission " + expired, body).statusCode());
    }

    @Test
    @Order(5)
    void afterMissionClosureNoPositionIsEverStoredAgain() throws Exception {
        try (Connection c = db()) {
            c.createStatement().executeUpdate("UPDATE mission SET status = 'FINISHED', finished_at = now() WHERE id = '" + MISSION + "'");
        }
        long before = count("SELECT count(*) FROM location_point");
        Thread.sleep(20);
        var r = post("/gps/traccar/positions", "Bearer " + SECRET, forward(1010, "AND-7F3K", Instant.now(), 3.53, 11.49));
        assertEquals("REJECTED", JSON.readTree(r.body()).path("outcome").asText());
        String token = TOKENS.issue(MISSION, "AND-7F3K", Instant.now(), Duration.ofHours(1));
        var a = JSON.readTree(post("/gps/android/fixes", "Mission " + token,
                "{\"fixes\":[{\"clientFixId\":\"and-9\",\"recordedAt\":\"" + Instant.now() + "\",\"lat\":3.53,\"lon\":11.49,\"accuracyM\":5}]}").body());
        assertEquals("REJECTED", a.path("results").get(0).path("outcome").asText());
        assertEquals(before, count("SELECT count(*) FROM location_point"), "fin de mission = fin de collecte");
        assertEquals(1, count("SELECT count(*) FROM tracking_authorization WHERE mission_id = '" + MISSION + "' AND revoked_at IS NOT NULL"));
        assertEquals(0, count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'gps_ingest_rejection' AND udt_name = 'geography'"));
    }
}
