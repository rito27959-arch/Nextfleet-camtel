package cm.camtel.nextfleet.api.missions;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Paramètres GPS de l'API : clé HMAC des jetons de mission (partagée avec la passerelle, fournie par le coffre),
 * durée de vie des jetons, adresse publique de la passerelle, versions de notice d'information acceptées.
 */
@ConfigurationProperties(prefix = "nextfleet.gps")
public record GpsProperties(String missionTokenKey, Duration missionTokenTtl, String gatewayPublicUrl, List<String> noticeVersions) {

    public GpsProperties {
        if (missionTokenTtl == null) missionTokenTtl = Duration.ofHours(12);
        if (noticeVersions == null || noticeVersions.isEmpty()) noticeVersions = List.of("NOTICE-GPS-v1");
    }
}
