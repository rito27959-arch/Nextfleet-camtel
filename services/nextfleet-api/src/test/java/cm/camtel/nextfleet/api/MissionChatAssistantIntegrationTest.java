package cm.camtel.nextfleet.api;

import cm.camtel.nextfleet.domain.gps.MissionToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot 2 de l'API sur PostgreSQL/PostGIS réel : missions et jeton GPS, messagerie confidentielle, assistant en
 * lecture seule avec activation contrôlée, administration et journal. Un seul scénario ordonné (la mission traverse
 * ses états), pour rester lisible et déterministe.
 */
@SpringBootTest(properties = {
        "nextfleet.environment=TEST",
        "nextfleet.gps.mission-token-key=" + MissionChatAssistantIntegrationTest.KEY_B64,
        "nextfleet.gps.gateway-public-url=https://gps.test.invalid",
        "nextfleet.gps.notice-versions=NOTICE-GPS-v1"})
@AutoConfigureMockMvc
@Testcontainers
class MissionChatAssistantIntegrationTest {

    static final String KEY_B64 = "Y2xlLWRlLXRlc3QtdW5pcXVlbWVudC0zMi1vY3RldHMhIQ=="; // "cle-de-test-uniquement-32-octets!!"

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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    static final String YDE = "00000000-0000-0000-0000-0000000000a1";
    static final String DLA = "00000000-0000-0000-0000-0000000000a2";
    static final String VEH = "00000000-0000-0000-0000-0000000000b1";
    static final String DRV = "00000000-0000-0000-0000-0000000000c1";
    static final String U_MGR = "00000000-0000-0000-0000-000000000001";
    static final String U_MGR2 = "00000000-0000-0000-0000-000000000003";
    static final String U_DRIVER = "00000000-0000-0000-0000-000000000005";

    private void seed() {
        if (jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class) > 0) return;
        jdbc.update("INSERT INTO app_environment(kind) VALUES ('TEST')");
        jdbc.update("INSERT INTO organisation_unit(id, code, name, is_demo) VALUES (?::uuid,'YDE','Yaoundé',true),(?::uuid,'DLA','Douala',true)", YDE, DLA);
        String h = encoder.encode("Mot-de-passe-test-1");
        user(U_MGR, "gestionnaire.yde", YDE, h, "FLEET_MANAGER");
        user(U_MGR2, "chef.parc.yde", YDE, h, "FLEET_MANAGER");
        user("00000000-0000-0000-0000-000000000004", "gestionnaire.dla", DLA, h, "FLEET_MANAGER");
        user(U_DRIVER, "conducteur.yde", YDE, h, "DRIVER");
        user("00000000-0000-0000-0000-000000000006", "admin.tech", YDE, h, "ADMIN");
        user("00000000-0000-0000-0000-000000000007", "auditeur", YDE, h, "AUDITOR");
        jdbc.update("""
                INSERT INTO vehicle(id, registration, make, model, org_unit_id, tank_capacity_l, is_demo, category)
                VALUES (?::uuid,'CE 4401 LT','Toyota','Hilux',?::uuid,80,true,'Pick-up')""", VEH, YDE);
        jdbc.update("INSERT INTO driver(id, user_id, staff_number, full_name, org_unit_id, is_demo) VALUES (?::uuid,?::uuid,'MAT-0005','Paul Nkodo',?::uuid,true)",
                DRV, U_DRIVER, YDE);
    }

    private void user(String id, String login, String org, String hash, String role) {
        jdbc.update("INSERT INTO app_user(id, login, display_name, org_unit_id, password_hash, is_demo) VALUES (?::uuid,?,?,?::uuid,?,true)",
                id, login, login, org, hash);
        jdbc.update("INSERT INTO user_role_grant(user_id, role_code, scope_org_id) VALUES (?::uuid,?,?::uuid)", id, role, org);
    }

    private String login(String login) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + login + "\",\"password\":\"Mot-de-passe-test-1\",\"clientKind\":\"ANDROID\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder b, String token, String json) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (json != null) b.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(b);
    }

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Mission → jeton GPS → messagerie → clôture → assistant → journal")
    void lot2Scenario() throws Exception {
        seed();
        String mgr = login("gestionnaire.yde");
        String mgr2 = login("chef.parc.yde");
        String dla = login("gestionnaire.dla");
        String drv = login("conducteur.yde");
        String admin = login("admin.tech");
        String auditor = login("auditeur");

        // --- Création et autorisation (aucune auto-approbation)
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        JsonNode m = json(call(post("/api/v1/missions"), mgr, """
                {"vehicleId":"%s","driverId":"%s","purpose":"Maintenance BTS Mvog-Mbi","origin":"Siège CAMTEL","destination":"BTS Mvog-Mbi",
                 "plannedStart":"%s","plannedEnd":"%s"}""".formatted(VEH, DRV, start, start.plus(8, ChronoUnit.HOURS)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.category").value("Pick-up")));
        String id = m.get("id").asText();
        String conv = m.get("conversationId").asText();
        call(post("/api/v1/missions/" + id + "/authorize"), mgr, null).andExpect(status().isBadRequest());
        call(post("/api/v1/missions/" + id + "/authorize"), mgr2, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHORIZED"));

        // --- Cloisonnement : Douala ne voit pas la mission de Yaoundé
        call(get("/api/v1/missions"), dla, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/missions/" + id + "/track"), dla, null).andExpect(status().isForbidden());

        // --- Démarrage : conducteur affecté seulement, notice connue, jeton signé lié à la mission et à l'émetteur
        call(get("/api/v1/missions"), drv, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        call(post("/api/v1/missions/" + id + "/start"), mgr, "{\"noticeVersion\":\"NOTICE-GPS-v1\",\"deviceId\":\"AND-TEST01\"}")
                .andExpect(status().isForbidden());
        call(post("/api/v1/missions/" + id + "/start"), drv, "{\"noticeVersion\":\"NOTICE-GPS-v9\",\"deviceId\":\"AND-TEST01\"}")
                .andExpect(status().isBadRequest());
        JsonNode started = json(call(post("/api/v1/missions/" + id + "/start"), drv,
                "{\"noticeVersion\":\"NOTICE-GPS-v1\",\"deviceId\":\"AND-TEST01\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.mission.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mission.deviceId").value("AND-TEST01"))
                .andExpect(jsonPath("$.gatewayUrl").value("https://gps.test.invalid")));
        var claims = new MissionToken(Base64.getDecoder().decode(KEY_B64)).verify(started.get("missionToken").asText(), Instant.now());
        assertEquals(UUID.fromString(id), claims.missionId());
        call(post("/api/v1/missions/" + id + "/start"), drv, "{\"noticeVersion\":\"NOTICE-GPS-v1\",\"deviceId\":\"AND-TEST01\"}")
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tracking_authorization WHERE mission_id = ?::uuid", Integer.class, id));

        // --- Messagerie : idempotence, états Envoyé → Distribué → Lu, confidentialité
        String msg = "{\"clientMessageId\":\"and-0001\",\"body\":\"Arrivé sur site, début d'intervention.\"}";
        long seq = json(call(post("/api/v1/conversations/" + conv + "/messages"), drv, msg).andExpect(status().isCreated()))
                .get("serverSeq").asLong();
        call(post("/api/v1/conversations/" + conv + "/messages"), drv, msg)
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.serverSeq").value(seq));
        call(get("/api/v1/conversations/" + conv + "/messages"), drv, null)
                .andExpect(jsonPath("$[0].mine").value(true)).andExpect(jsonPath("$[0].state").value("ENVOYE"));
        call(get("/api/v1/conversations"), mgr, null).andExpect(jsonPath("$[0].unread").value(1));
        call(get("/api/v1/conversations/" + conv + "/messages"), mgr, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].senderName").value("conducteur.yde"));
        call(get("/api/v1/conversations/" + conv + "/messages"), drv, null).andExpect(jsonPath("$[0].state").value("DISTRIBUE"));
        call(post("/api/v1/conversations/" + conv + "/read"), mgr, null).andExpect(status().isNoContent());
        call(get("/api/v1/conversations/" + conv + "/messages"), drv, null).andExpect(jsonPath("$[0].state").value("LU"));
        call(get("/api/v1/conversations/" + conv + "/messages"), mgr2, null).andExpect(status().isForbidden());
        call(get("/api/v1/conversations/" + conv + "/messages"), auditor, null).andExpect(status().isForbidden());

        // --- Clôture par le conducteur : l'autorisation de collecte est révoquée
        call(post("/api/v1/missions/" + id + "/finish"), drv, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINISHED"));
        call(get("/api/v1/missions/" + id + "/track"), mgr, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // --- Assistant : désactivé par défaut, activation avec signataire distinct, lecture seule
        call(post("/api/v1/assistant/ask"), mgr, "{\"question\":\"Combien a coûté CE 4401 LT ce mois ?\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.refused").value(true));
        call(post("/api/v1/assistant/activation"), mgr, "{\"enable\":true,\"securityReviewRef\":\"DS-2026-041\",\"approvedBy\":\"RSSI CAMTEL\",\"reason\":\"Validation de sécurité obtenue\"}")
                .andExpect(status().isForbidden());
        call(post("/api/v1/assistant/activation"), admin, "{\"enable\":true,\"securityReviewRef\":\"DS-2026-041\",\"approvedBy\":\"admin.tech\",\"reason\":\"Validation de sécurité obtenue\"}")
                .andExpect(status().isBadRequest());
        call(post("/api/v1/assistant/activation"), admin, "{\"enable\":true,\"securityReviewRef\":\"DS-2026-041\",\"approvedBy\":\"RSSI CAMTEL\",\"reason\":\"Validation de sécurité obtenue\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        call(post("/api/v1/assistant/ask"), mgr, "{\"question\":\"Combien a coûté CE 4401 LT ce mois ?\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.refused").value(false));
        call(post("/api/v1/assistant/ask"), mgr, "{\"question\":\"Supprime le véhicule CE 4401 LT\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.refused").value(true));
        call(post("/api/v1/assistant/ask"), drv, "{\"question\":\"Quelles missions sont en cours ?\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.refused").value(true));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM assistant_run", Integer.class) >= 4);

        // --- Administration et journal
        call(get("/api/v1/admin/users"), mgr, null).andExpect(status().isForbidden());
        JsonNode users = json(call(get("/api/v1/admin/users?q=conducteur"), admin, null).andExpect(status().isOk()));
        assertEquals(1, users.size());
        assertTrue(users.get(0).get("password_hash") == null && users.get(0).get("passwordHash") == null);
        JsonNode events = json(call(get("/api/v1/admin/audit?objectType=mission&objectId=" + id), auditor, null).andExpect(status().isOk()));
        assertNotNull(events);
        assertTrue(events.toString().contains("MISSION_STARTED") && events.toString().contains("MISSION_FINISHED"), events.toString());
        call(get("/api/v1/admin/audit"), drv, null).andExpect(status().isForbidden());
    }
}
