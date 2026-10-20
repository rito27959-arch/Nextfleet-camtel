package cm.camtel.nextfleet.gps;

import cm.camtel.nextfleet.domain.gps.GpsFix;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Relais des positions Android ACCEPTÉES vers Traccar Server, protocole OsmAnd (port 5055 par défaut).
 * Paramètres conformes à {@code OsmAndProtocolDecoder} (source Traccar) : {@code id}, {@code timestamp} (secondes
 * epoch), {@code lat}, {@code lon}, {@code speed} (nœuds, unité par défaut du protocole), {@code bearing},
 * {@code accuracy}. Seuls les points validés par la passerelle mission sont relayés : Traccar ne reçoit jamais un
 * point hors mission.
 */
public final class OsmAndRelay {

    private final URI base;
    private final HttpClient http;

    public OsmAndRelay(URI base) {
        this.base = base;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public URI uriFor(String uniqueId, GpsFix fix, Double bearingDeg) {
        StringBuilder q = new StringBuilder();
        add(q, "id", uniqueId);
        add(q, "timestamp", Long.toString(fix.recordedAt().getEpochSecond()));
        add(q, "lat", fmt(fix.latitude()));
        add(q, "lon", fmt(fix.longitude()));
        if (fix.speedMps() != null) add(q, "speed", fmt(fix.speedMps() / TraccarForwardParser.MPS_PER_KNOT));
        if (bearingDeg != null) add(q, "bearing", fmt(bearingDeg));
        add(q, "accuracy", fmt(fix.accuracyM()));
        String path = base.getPath() == null || base.getPath().isEmpty() ? "/" : base.getPath();
        return URI.create(base.getScheme() + "://" + base.getAuthority() + path + "?" + q);
    }

    /** @return code HTTP renvoyé par Traccar (200 attendu) */
    public int send(String uniqueId, GpsFix fix, Double bearingDeg) throws java.io.IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uriFor(uniqueId, fix, bearingDeg)).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void add(StringBuilder q, String k, String v) {
        if (q.length() > 0) q.append('&');
        q.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
