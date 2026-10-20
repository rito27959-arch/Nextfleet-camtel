package cm.camtel.nextfleet.api.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.time.Clock;

/**
 * Sécurité HTTP : sans état côté conteneur, jeton opaque en en-tête Authorization, refus par défaut (SEC-02).
 * Les contrôles par objet et par entité sont faits dans les services via {@link AccessService}.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, SessionService sessions, ObjectMapper mapper) throws Exception {
        http.csrf(c -> c.disable())   // pas de cookie de session : jeton porteur uniquement
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'")))
            .addFilterBefore(new TokenAuthenticationFilter(sessions), AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                    .requestMatchers("/actuator/health", "/api/v1/openapi/**").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().denyAll())
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) -> {
                        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                                "Session absente, expirée ou révoquée. Reconnectez-vous.");
                        p.setProperty("code", "SESSION_REQUIRED");
                        res.setStatus(401);
                        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                        mapper.writeValue(res.getOutputStream(), p);
                    }));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
