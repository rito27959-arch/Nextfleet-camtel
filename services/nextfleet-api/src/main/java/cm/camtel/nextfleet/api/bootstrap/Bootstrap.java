package cm.camtel.nextfleet.api.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Amorçage d'une instance neuve, activé seulement par {@code NF_BOOTSTRAP=true} (jamais implicite) :
 * <ol>
 *   <li>déclare le type d'environnement de la base (DEV, TEST, DEMO, PREPROD, PRODUCTION) s'il ne l'est pas ;</li>
 *   <li>si aucun compte n'existe, crée l'entité racine et un administrateur technique à partir de
 *       {@code NF_BOOTSTRAP_ADMIN_LOGIN} / {@code NF_BOOTSTRAP_ADMIN_PASSWORD} (12 caractères au moins) ;</li>
 *   <li>en environnement DEMO uniquement, avec {@code NF_DEMO_PASSWORD}, charge le jeu de démonstration fictif.</li>
 * </ol>
 * Aucun mot de passe n'est écrit dans le dépôt ni dans les journaux.
 */
@Component
public class Bootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String environment;
    private final String adminLogin;
    private final String adminPassword;
    private final String demoPassword;

    public Bootstrap(JdbcTemplate jdbc, PasswordEncoder encoder, TransactionTemplate tx,
                     @Value("${nextfleet.bootstrap.enabled:false}") boolean enabled,
                     @Value("${nextfleet.environment:DEV}") String environment,
                     @Value("${nextfleet.bootstrap.admin-login:}") String adminLogin,
                     @Value("${nextfleet.bootstrap.admin-password:}") String adminPassword,
                     @Value("${nextfleet.bootstrap.demo-password:}") String demoPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.tx = tx;
        this.enabled = enabled;
        this.environment = environment;
        this.adminLogin = adminLogin;
        this.adminPassword = adminPassword;
        this.demoPassword = demoPassword;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void run() {
        if (!enabled) return;
        tx.executeWithoutResult(s -> {
            Integer env = jdbc.queryForObject("SELECT count(*) FROM app_environment", Integer.class);
            if (env != null && env == 0) {
                jdbc.update("INSERT INTO app_environment(kind) VALUES (?)", environment);
                LOG.info("Amorçage : environnement déclaré {}", environment);
            }
            boolean demo = "DEMO".equals(jdbc.queryForObject("SELECT kind FROM app_environment", String.class));
            if (demo && demoPassword.length() >= 8
                    && jdbc.queryForObject("SELECT count(*) FROM app_user WHERE login_normalized = 'gestionnaire'", Integer.class) == 0) {
                seedDemo();
            }
            if (jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class) == 0) createAdmin();
            boolean production = "PRODUCTION".equals(jdbc.queryForObject("SELECT kind FROM app_environment", String.class));
            if (production && !adminLogin.isBlank()) {
                // en production, le mot de passe d'amorçage (connu de l'hébergement) doit être remplacé à la première
                // connexion, y compris pour un compte créé par une version antérieure qui ne l'imposait pas
                int n = jdbc.update("""
                        UPDATE app_user SET must_change_password = true
                         WHERE login_normalized = lower(?) AND password_changed_at IS NULL AND NOT must_change_password""",
                        adminLogin.strip());
                if (n > 0) LOG.info("Amorçage : changement du mot de passe d'amorçage imposé à « {} »", adminLogin.strip());
            }
        });
    }

    private void createAdmin() {
        if (adminLogin.isBlank() || adminPassword.length() < 12) {
            LOG.warn("Amorçage : aucun compte et NF_BOOTSTRAP_ADMIN_LOGIN / NF_BOOTSTRAP_ADMIN_PASSWORD (≥ 12 caractères) absents");
            return;
        }
        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO organisation_unit(id, code, name) VALUES (?, 'CAMTEL', 'CAMTEL')", org);
        jdbc.update("INSERT INTO app_user(id, login, display_name, org_unit_id, password_hash) VALUES (?, ?, 'Administration technique', ?, ?)",
                user, adminLogin.strip(), org, encoder.encode(adminPassword));
        jdbc.update("INSERT INTO user_role_grant(user_id, role_code, scope_org_id) VALUES (?, 'ADMIN', ?)", user, org);
        jdbc.update("INSERT INTO audit_event(actor_id, action, object_type, object_id, reason) VALUES (NULL, 'BOOTSTRAP_ADMIN', 'app_user', ?, 'Amorçage de l''instance')",
                user.toString());
        LOG.info("Amorçage : administrateur technique « {} » créé", adminLogin.strip());
    }

    private void seedDemo() {
        try {
            String sql = new String(new ClassPathResource("bootstrap/demo-seed.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .replace("{{PWD}}", encoder.encode(demoPassword));
            jdbc.execute(sql);
            jdbc.update("INSERT INTO audit_event(actor_id, action, object_type, object_id, reason) VALUES (NULL, 'BOOTSTRAP_DEMO', 'environment', 'DEMO', 'Jeu de démonstration fictif chargé')");
            LOG.info("Amorçage : jeu de démonstration chargé (comptes fictifs, mot de passe NF_DEMO_PASSWORD)");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Jeu de démonstration illisible", e);
        }
    }
}
