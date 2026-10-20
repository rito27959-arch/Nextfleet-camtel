package cm.camtel.nextfleet.api.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "nextfleet")
public record NextfleetProperties(String environment, Session session) {

    public record Session(Duration ttl, int maxFailedLogins, Duration lockout) { }

    public boolean isProduction() {
        return "PRODUCTION".equalsIgnoreCase(environment);
    }
}
