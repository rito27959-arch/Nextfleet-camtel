package cm.camtel.nextfleet.gps;

import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Base64;

/**
 * Lancement de la passerelle GPS. Configuration par variables d'environnement (aucun secret dans le dépôt) :
 * NF_DB_URL, NF_DB_USER, NF_DB_PASSWORD, NF_TRACCAR_FORWARD_SECRET, NF_MISSION_TOKEN_KEY (base64, 32 octets et plus),
 * NF_TRACCAR_OSMAND_URL (facultatif, ex. http://traccar:5055), NF_GPS_BIND (défaut 127.0.0.1:8090).
 */
public final class GpsGatewayMain {

    private GpsGatewayMain() { }

    public static void main(String[] args) throws Exception {
        String url = env("NF_DB_URL");
        String user = env("NF_DB_USER");
        String password = env("NF_DB_PASSWORD");
        String[] bind = System.getenv().getOrDefault("NF_GPS_BIND", "127.0.0.1:8090").split(":");
        String osmand = System.getenv("NF_TRACCAR_OSMAND_URL");
        var ingestion = new GpsIngestionService(() -> {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                throw new IllegalStateException("Base indisponible", e);
            }
        });
        var server = new GpsGatewayServer(new InetSocketAddress(bind[0], Integer.parseInt(bind[1])), ingestion,
                env("NF_TRACCAR_FORWARD_SECRET"), new MissionToken(Base64.getDecoder().decode(env("NF_MISSION_TOKEN_KEY"))),
                osmand == null || osmand.isBlank() ? null : new OsmAndRelay(URI.create(osmand)), Clock.systemUTC());
        server.start();
        System.out.println("Passerelle GPS NexFleet à l'écoute sur " + bind[0] + ":" + server.port());
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("Variable d'environnement manquante : " + name);
        return v;
    }
}
