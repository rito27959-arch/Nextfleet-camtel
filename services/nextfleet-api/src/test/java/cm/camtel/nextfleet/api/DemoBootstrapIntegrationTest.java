package cm.camtel.nextfleet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Instance hébergée de démonstration : amorçage, comptes fictifs, simulateur qui alimente la carte en direct. */
@SpringBootTest(properties = {"nextfleet.environment=DEMO", "nextfleet.bootstrap.enabled=true",
        "nextfleet.bootstrap.demo-password=Demo-Camtel-2026", "nextfleet.demo.simulator=true"})
@AutoConfigureMockMvc
@Testcontainers
class DemoBootstrapIntegrationTest {

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
    @Autowired ObjectMapper mapper;

    @Test
    @DisplayName("Amorçage DEMO : comptes fictifs, mission démarrable, simulateur visible sur la carte de flotte")
    void demoInstance() throws Exception {
        assertEquals("DEMO", jdbc.queryForObject("SELECT kind FROM app_environment", String.class));
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class));
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"gestionnaire\",\"password\":\"Demo-Camtel-2026\",\"clientKind\":\"ANDROID\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(body).get("token").asText();
        long before = jdbc.queryForObject("SELECT count(*) FROM location_point WHERE provider = 'simulation-demo' AND source_device_id <> 'SIM-DEMO-012'", Long.class);
        long deadline = System.currentTimeMillis() + 45_000;
        long after = before;
        while (after <= before && System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000);
            after = jdbc.queryForObject("SELECT count(*) FROM location_point WHERE provider = 'simulation-demo' AND source_device_id <> 'SIM-DEMO-012'", Long.class);
        }
        assertTrue(after > before, "le simulateur a produit des positions acceptées par la base");
        JsonNode live = mapper.readTree(mvc.perform(get("/api/v1/fleet/live").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(live.size() >= 2, live.toString());
        String drv = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"conducteur\",\"password\":\"Demo-Camtel-2026\",\"clientKind\":\"ANDROID\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        JsonNode missions = mapper.readTree(mvc.perform(get("/api/v1/missions").header("Authorization", "Bearer " + drv))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(missions.toString().contains("\"status\":\"AUTHORIZED\""), "mission démarrable depuis un vrai téléphone");
    }
}
