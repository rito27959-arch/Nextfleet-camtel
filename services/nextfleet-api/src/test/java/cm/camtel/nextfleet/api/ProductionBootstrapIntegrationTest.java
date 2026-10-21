package cm.camtel.nextfleet.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Environnement PRODUCTION : le mot de passe d'amorçage, connu de l'hébergement, doit être remplacé à la première
 * connexion de l'administrateur avant toute autre action.
 */
@SpringBootTest(properties = {"nextfleet.environment=PRODUCTION", "nextfleet.bootstrap.enabled=true",
        "nextfleet.bootstrap.admin-login=admin", "nextfleet.bootstrap.admin-password=Amorcage-Prod-2026!"})
@AutoConfigureMockMvc
@Testcontainers
class ProductionBootstrapIntegrationTest {

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
    @Autowired ObjectMapper mapper;

    @Test
    @DisplayName("Production : l'administrateur d'amorçage doit changer son mot de passe avant tout")
    void bootstrapAdminMustChangePassword() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("login", "admin", "password", "Amorcage-Prod-2026!", "clientKind", "DESKTOP"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();
        String t = "Bearer " + mapper.readTree(body).get("token").asText();
        mvc.perform(get("/api/v1/admin/roles").header("Authorization", t))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(post("/api/v1/me/password").header("Authorization", t).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("currentPassword", "Amorcage-Prod-2026!", "newPassword", "Administration-Camtel-2026"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/roles").header("Authorization", t)).andExpect(status().isOk())
                .andExpect(header().exists("ETag"));
    }
}
