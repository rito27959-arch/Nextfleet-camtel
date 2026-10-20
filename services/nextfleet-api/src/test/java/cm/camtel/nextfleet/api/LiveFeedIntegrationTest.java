package cm.camtel.nextfleet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Temps réel de bout en bout sur PostgreSQL/PostGIS : point inséré → NOTIFY au commit → canal WebSocket filtré par
 * droits, et carte de flotte REST avec état calculé côté serveur.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "nextfleet.environment=TEST")
@Testcontainers
class LiveFeedIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withInitScript("test-extensions.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    final HttpClient http = HttpClient.newHttpClient();

    static final String YDE = "00000000-0000-0000-0000-0000000000a1";
    static final String DLA = "00000000-0000-0000-0000-0000000000a2";
    static final String VEH = "00000000-0000-0000-0000-0000000000b1";
    static final String MIS = "00000000-0000-0000-0000-0000000000e1";

    private void seed() {
        jdbc.update("INSERT INTO app_environment(kind) VALUES ('TEST')");
        jdbc.update("INSERT INTO organisation_unit(id, code, name, is_demo) VALUES (?::uuid,'YDE','Yaoundé',true),(?::uuid,'DLA','Douala',true)", YDE, DLA);
        String h = encoder.encode("Mot-de-passe-test-1");
        user("00000000-0000-0000-0000-000000000001", "gestionnaire.yde", YDE, h, "FLEET_MANAGER");
        user("00000000-0000-0000-0000-000000000003", "chef.yde", YDE, h, "FLEET_MANAGER");
        user("00000000-0000-0000-0000-000000000004", "gestionnaire.dla", DLA, h, "FLEET_MANAGER");
        user("00000000-0000-0000-0000-000000000005", "conducteur.yde", YDE, h, "DRIVER");
        jdbc.update("INSERT INTO vehicle(id, registration, make, model, org_unit_id, tank_capacity_l, is_demo, category) "
                + "VALUES (?::uuid,'CE 4401 LT','Toyota','Hilux',?::uuid,80,true,'Pick-up')", VEH, YDE);
        jdbc.update("INSERT INTO driver(id, user_id, staff_number, full_name, org_unit_id, is_demo) VALUES "
                + "('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-000000000005','M-5','Paul Nkodo',?::uuid,true)", YDE);
        jdbc.update("""
                INSERT INTO mission(id, reference, org_unit_id, vehicle_id, driver_id, purpose, planned_start, planned_end, status,
                                    authorized_by, authorized_at, started_at, created_by, origin_label, destination_label)
                VALUES (?::uuid, 'MIS-2026-101', ?::uuid, ?::uuid, '00000000-0000-0000-0000-0000000000c1', 'Intervention BTS',
                        now() - interval '1 hour', now() + interval '6 hours', 'ACTIVE', '00000000-0000-0000-0000-000000000003', now(),
                        now() - interval '30 minutes', '00000000-0000-0000-0000-000000000001', 'Siège', 'BTS Mvog-Mbi')""", MIS, YDE, VEH);
        jdbc.update("INSERT INTO tracking_source(unique_id, kind, label, registered_by) VALUES ('AND-LIVE1','ANDROID','Test','00000000-0000-0000-0000-000000000005')");
        jdbc.update("INSERT INTO tracking_authorization(mission_id, source_device_id, informed_at, notice_version, granted_at) "
                + "VALUES (?::uuid, 'AND-LIVE1', now() - interval '30 minutes', 'NOTICE-GPS-v1', now() - interval '30 minutes')", MIS);
    }

    private void user(String id, String login, String org, String hash, String role) {
        jdbc.update("INSERT INTO app_user(id, login, display_name, org_unit_id, password_hash, is_demo) VALUES (?::uuid,?,?,?::uuid,?,true)",
                id, login, login, org, hash);
        jdbc.update("INSERT INTO user_role_grant(user_id, role_code, scope_org_id) VALUES (?::uuid,?,?::uuid)", id, role, org);
    }

    private String login(String login) throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"login\":\"" + login + "\",\"password\":\"Mot-de-passe-test-1\",\"clientKind\":\"DESKTOP\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return mapper.readTree(r.body()).get("token").asText();
    }

    private BlockingQueue<JsonNode> connect(String token) throws Exception {
        BlockingQueue<JsonNode> q = new LinkedBlockingQueue<>();
        http.newWebSocketBuilder().header("Authorization", "Bearer " + token).connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/live"), new WebSocket.Listener() {
                    final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            try {
                                q.add(mapper.readTree(buf.toString()));
                            } catch (Exception ignored) {
                                // message non JSON : ignoré
                            }
                            buf.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        JsonNode hello = q.poll(5, TimeUnit.SECONDS);
        assertNotNull(hello);
        assertEquals("hello", hello.path("type").asText());
        return q;
    }

    private void insertPoint(String fixId, double lat, double lon, double speedMps) {
        jdbc.update("""
                INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position, accuracy_m, speed_mps, provider)
                VALUES (?::uuid, ?::uuid, 'AND-LIVE1', ?, now() - interval '5 seconds', ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 6, ?, 'android')""",
                MIS, VEH, fixId, lon, lat, speedMps);
    }

    @Test
    @DisplayName("Position acceptée → canal temps réel filtré (gestionnaire et conducteur oui, autre entité non) → carte de flotte")
    void liveFeed() throws Exception {
        seed();
        BlockingQueue<JsonNode> mgr = connect(login("gestionnaire.yde"));
        BlockingQueue<JsonNode> drv = connect(login("conducteur.yde"));
        BlockingQueue<JsonNode> dla = connect(login("gestionnaire.dla"));
        var unauth = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/live")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauth.statusCode(), "poignée de main sans jeton refusée");

        insertPoint("f-1", 3.8667, 11.5167, 12.0);
        JsonNode e = mgr.poll(10, TimeUnit.SECONDS);
        assertNotNull(e, "le gestionnaire de l'entité reçoit la position");
        assertEquals("position", e.path("type").asText());
        assertEquals("CE 4401 LT", e.path("registration").asText());
        assertEquals("MIS-2026-101", e.path("missionRef").asText());
        assertEquals(43.2, e.path("speedKmh").asDouble(), 0.01);
        assertTrue(e.path("orgUnitId").isMissingNode() && e.path("driverUserId").isMissingNode(), "aucune donnée interne exposée");
        assertNotNull(drv.poll(10, TimeUnit.SECONDS), "le conducteur reçoit SA mission");
        assertNull(dla.poll(2, TimeUnit.SECONDS), "une autre entité ne reçoit rien");

        insertPoint("f-2", 3.8700, 11.5200, 0.2);
        assertNotNull(mgr.poll(10, TimeUnit.SECONDS));
        var live = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/fleet/live"))
                .header("Authorization", "Bearer " + login("gestionnaire.yde")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, live.statusCode(), live.body());
        JsonNode list = mapper.readTree(live.body());
        assertEquals(1, list.size());
        assertEquals("ARRET", list.get(0).path("state").asText(), "dernière vitesse 0,7 km/h → à l'arrêt");
        assertTrue(list.get(0).path("distanceKm").asDouble() > 0.4, "distance de la trace calculée par PostGIS");
        var other = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/fleet/live"))
                .header("Authorization", "Bearer " + login("gestionnaire.dla")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(0, mapper.readTree(other.body()).size());
    }
}
