package cm.camtel.nextfleet.chat;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Lancement de la passerelle de messagerie pilote. Variables : NF_DB_URL, NF_DB_USER, NF_DB_PASSWORD,
 * NF_CHAT_BIND (défaut 127.0.0.1:8091). À placer derrière le frontal TLS (WSS) de la DSIR.
 */
public final class ChatGatewayMain {

    private ChatGatewayMain() { }

    public static void main(String[] args) {
        String url = env("NF_DB_URL");
        String user = env("NF_DB_USER");
        String password = env("NF_DB_PASSWORD");
        String[] bind = System.getenv().getOrDefault("NF_CHAT_BIND", "127.0.0.1:8091").split(":");
        Supplier<Connection> db = () -> {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                throw new IllegalStateException("Base indisponible", e);
            }
        };
        var server = new ChatWebSocketServer(new InetSocketAddress(bind[0], Integer.parseInt(bind[1])),
                new JdbcChatStore(db), new SessionAuthenticator(db));
        server.start();
        System.out.println("Messagerie NexFleet (pilote) à l'écoute sur ws://" + bind[0] + ":" + bind[1] + "/ws/chat");
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("Variable d'environnement manquante : " + name);
        return v;
    }
}
