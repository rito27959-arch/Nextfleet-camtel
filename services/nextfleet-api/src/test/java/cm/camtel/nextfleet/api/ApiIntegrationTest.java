package cm.camtel.nextfleet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration API sur PostgreSQL/PostGIS réel (Testcontainers).
 * STATUT : écrits mais NON EXÉCUTÉS dans l'environnement de réalisation (Maven Central et Docker indisponibles).
 * Les mêmes invariants sont prouvés côté base par infra/db/tests/run_db_tests.sh (exécuté, 32 tests + concurrence).
 */
@SpringBootTest(properties = "nextfleet.environment=TEST")
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {

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

    @BeforeEach
    void seed() {
        if (jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class) > 0) return;
        jdbc.update("INSERT INTO app_environment(kind) VALUES ('TEST')");
        jdbc.update("INSERT INTO organisation_unit(id, code, name, is_demo) VALUES (?::uuid,'YDE','Yaoundé',true),(?::uuid,'DLA','Douala',true)", YDE, DLA);
        String h = encoder.encode("Mot-de-passe-test-1");
        user("00000000-0000-0000-0000-000000000001", "gestionnaire.yde", YDE, h, "FLEET_MANAGER");
        user("00000000-0000-0000-0000-000000000002", "controleur.yde", YDE, h, "CONTROLLER");
        user("00000000-0000-0000-0000-000000000004", "gestionnaire.dla", DLA, h, "FLEET_MANAGER");
        jdbc.update("""
                INSERT INTO vehicle(id, registration, make, model, org_unit_id, tank_capacity_l, is_demo)
                VALUES ('00000000-0000-0000-0000-0000000000b1','CE 4401 LT','Toyota','Hilux',?::uuid,80,true)""", YDE);
        jdbc.update("INSERT INTO supplier(id, code, name, kind, is_demo) VALUES ('00000000-0000-0000-0000-0000000000d1','ST-1','Station','STATION',true)");
    }

    private void user(String id, String login, String org, String hash, String role) {
        jdbc.update("INSERT INTO app_user(id, login, display_name, org_unit_id, password_hash, is_demo) VALUES (?::uuid,?,?,?::uuid,?,true)",
                id, login, login, org, hash);
        jdbc.update("INSERT INTO user_role_grant(user_id, role_code, scope_org_id) VALUES (?::uuid,?,?::uuid)", id, role, org);
    }

    private String login(String login) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + login + "\",\"password\":\"Mot-de-passe-test-1\",\"clientKind\":\"DESKTOP\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    @Test
    @DisplayName("NF-01 : requête non authentifiée rejetée ; échec de connexion générique")
    void unauthenticated() throws Exception {
        mvc.perform(get("/api/v1/vehicles")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"inconnu\",\"password\":\"x\",\"clientKind\":\"DESKTOP\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    @DisplayName("NF-01 : session révoquée après déconnexion")
    void logoutRevokes() throws Exception {
        String t = login("gestionnaire.yde");
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + t)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("REC-SEC-01 : le gestionnaire de Douala ne voit ni ne lit le véhicule de Yaoundé")
    void crossEntity() throws Exception {
        String dla = login("gestionnaire.dla");
        mvc.perform(get("/api/v1/vehicles").header("Authorization", "Bearer " + dla))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/vehicles/00000000-0000-0000-0000-0000000000b1").header("Authorization", "Bearer " + dla))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("NF-10 : doublon d'immatriculation normalisée refusé (409)")
    void duplicateRegistration() throws Exception {
        String t = login("gestionnaire.yde");
        mvc.perform(post("/api/v1/vehicles").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registration\":\"ce-4401-lt\",\"make\":\"Toyota\",\"model\":\"Hilux\",\"orgUnitId\":\"" + YDE + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE"));
    }

    @Test
    @DisplayName("NF-40 / CAR-01 : auto-validation refusée ; 40 L puis 30 L sur un bon de 60 L → second refusé")
    void fuelChain() throws Exception {
        String mgr = login("gestionnaire.yde");
        String ctl = login("controleur.yde");
        JsonNode order = mapper.readTree(mvc.perform(post("/api/v1/fuel-orders").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"00000000-0000-0000-0000-0000000000b1\",\"supplierId\":\"00000000-0000-0000-0000-0000000000d1\","
                                + "\"authorizedLiters\":60,\"authorizedAmountXaf\":60000,\"validUntil\":\"2099-12-31\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = order.get("id").asText();
        mvc.perform(post("/api/v1/fuel-orders/" + id + "/submit").header("Authorization", "Bearer " + mgr)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/fuel-orders/" + id + "/approve").header("Authorization", "Bearer " + mgr))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.rule").value("NF-40"));
        mvc.perform(post("/api/v1/fuel-orders/" + id + "/approve").header("Authorization", "Bearer " + ctl))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        // Le pleins sont saisis ici par le gestionnaire pour le test ; en exploitation : conducteur (fuel_fill:create).
        jdbc.update("INSERT INTO user_role_grant(user_id, role_code, scope_org_id) VALUES ('00000000-0000-0000-0000-000000000001','DRIVER',?::uuid)", YDE);
        String fill = "{\"fuelOrderId\":\"" + id + "\",\"vehicleId\":\"00000000-0000-0000-0000-0000000000b1\","
                + "\"supplierId\":\"00000000-0000-0000-0000-0000000000d1\",\"filledAt\":\"2026-10-01T08:00:00Z\","
                + "\"liters\":%s,\"unitPriceXaf\":800,\"fullTank\":false,\"receiptReference\":\"%s\"}";
        mvc.perform(post("/api/v1/fuel-fills").header("Authorization", "Bearer " + mgr).header("Idempotency-Key", "fill-0001")
                .contentType(MediaType.APPLICATION_JSON).content(fill.formatted("40", "R-1"))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/fuel-fills").header("Authorization", "Bearer " + mgr).header("Idempotency-Key", "fill-0001")
                .contentType(MediaType.APPLICATION_JSON).content(fill.formatted("40", "R-1"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/fuel-fills").header("Authorization", "Bearer " + mgr).header("Idempotency-Key", "fill-0002")
                        .contentType(MediaType.APPLICATION_JSON).content(fill.formatted("30", "R-2")))
                .andExpect(status().isUnprocessableEntity());
    }
}
