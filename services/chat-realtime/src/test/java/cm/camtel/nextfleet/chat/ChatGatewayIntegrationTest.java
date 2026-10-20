package cm.camtel.nextfleet.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scénario D du cahier sur PostgreSQL, avec de vrais clients WebSocket (client HTTP du JDK, celui du poste bureau) :
 * temps réel entre participants, coupure du conducteur, reprise {@code afterSeq}, réémission sans doublon,
 * accusés distribué/lu, refus de l'intrus, de l'auditeur et d'une session invalide.
 */
@EnabledIfSystemProperty(named = "nf.db.url", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatGatewayIntegrationTest {

    static final String CONV = "00000000-0000-0000-0000-000000000301";
    static final String MANAGER = "00000000-0000-0000-0000-000000000001";
    static final String DRIVER = "00000000-0000-0000-0000-000000000003";
    static final ObjectMapper JSON = new ObjectMapper();
    static ChatWebSocketServer server;
    static int port;

    static Connection db() {
        try {
            Class.forName("org.postgresql.Driver");
            return DriverManager.getConnection(System.getProperty("nf.db.url"), System.getProperty("nf.db.user"),
                    System.getProperty("nf.db.password"));
        } catch (SQLException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Client de test : file des trames reçues. */
    static final class Client implements WebSocket.Listener {
        final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket ws;

        static Client connect(String token) throws Exception {
            Client c = new Client();
            c.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/chat"), c).get(5, TimeUnit.SECONDS);
            c.send("{\"type\":\"auth\",\"token\":\"" + token + "\"}");
            return c;
        }

        void send(String frame) {
            ws.sendText(frame, true).join();
        }

        JsonNode next(String type) throws InterruptedException {
            while (true) {
                JsonNode f = frames.poll(5, TimeUnit.SECONDS);
                assertNotNull(f, "trame « " + type + " » attendue");
                if (f.path("type").asText().equals(type)) return f;
            }
        }

        /** Message diffusé d'un autre émetteur (l'émetteur reçoit aussi sa propre diffusion : synchronisation multi-appareils). */
        JsonNode nextMessageFrom(String senderId) throws InterruptedException {
            while (true) {
                JsonNode f = next("message");
                if (f.path("senderId").asText().equals(senderId)) return f;
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    frames.add(JSON.readTree(partial.toString()));
                } catch (java.io.IOException ignored) {
                    // trame illisible : le test échouera sur l'attente
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        void close() {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "fin").join();
        }
    }

    static String send(String clientId, String body) {
        return "{\"type\":\"send\",\"conversationId\":\"" + CONV + "\",\"clientMessageId\":\"" + clientId + "\",\"body\":\"" + body + "\"}";
    }

    @BeforeAll
    static void start() throws Exception {
        try (var s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        server = new ChatWebSocketServer(new InetSocketAddress("127.0.0.1", port),
                new JdbcChatStore(ChatGatewayIntegrationTest::db), new SessionAuthenticator(ChatGatewayIntegrationTest::db));
        server.start();
        for (int i = 0; i < 50 && !server.isStarted(); i++) Thread.sleep(50);
        assertTrue(server.isStarted());
    }

    @AfterAll
    static void stop() throws Exception {
        server.stop(1000);
    }

    @Test
    @Order(1)
    void realtimeBetweenParticipantsWithAckAfterPersistence() throws Exception {
        Client manager = Client.connect("tok-gestionnaire");
        Client driver = Client.connect("tok-conducteur");
        assertEquals(MANAGER, manager.next("ready").path("userId").asText());
        driver.next("ready");
        manager.send(send("m-1", "Merci d'envoyer la photo du compteur au départ."));
        JsonNode ack = manager.next("ack");
        assertEquals(1, ack.path("serverSeq").asLong());
        assertFalse(ack.path("duplicate").asBoolean());
        JsonNode received = driver.nextMessageFrom(MANAGER);
        assertEquals("Merci d'envoyer la photo du compteur au départ.", received.path("body").asText());
        try (Connection c = db(); ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM message")) {
            rs.next();
            assertEquals(1, rs.getLong(1), "persisté avant l'accusé");
        }
        driver.send("{\"type\":\"read\",\"conversationId\":\"" + CONV + "\",\"seq\":1}");
        JsonNode receipt = manager.next("receipt");
        assertEquals(DRIVER, receipt.path("userId").asText());
        assertEquals(1, receipt.path("read").asLong());
        assertEquals(1, receipt.path("delivered").asLong(), "« lu » implique « distribué »");
        manager.close();
        driver.close();
    }

    @Test
    @Order(2)
    void driverOfflineThenRecoversMissedMessagesAndResendsWithoutDuplicate() throws Exception {
        Client manager = Client.connect("tok-gestionnaire");
        manager.next("ready");
        manager.send(send("m-2", "Point d'étape à 11 h ?"));
        manager.next("ack");
        manager.send(send("m-3", "Le site de Mbalmayo est prévenu."));
        manager.next("ack");

        Client driver = Client.connect("tok-conducteur");     // reconnexion avec son dernier numéro connu : 1
        driver.next("ready");
        driver.send("{\"type\":\"sync\",\"conversationId\":\"" + CONV + "\",\"afterSeq\":1}");
        JsonNode history = driver.next("history");
        assertEquals(2, history.path("messages").size());
        assertEquals(3, history.path("messages").get(1).path("serverSeq").asLong());

        driver.send(send("d-1", "Arrivé sur site."));
        assertEquals(4, driver.next("ack").path("serverSeq").asLong());
        assertEquals("Arrivé sur site.", manager.nextMessageFrom(DRIVER).path("body").asText());
        // l'accusé a été perdu côté téléphone : réémission avec le même identifiant
        driver.send(send("d-1", "Arrivé sur site."));
        JsonNode again = driver.next("ack");
        assertTrue(again.path("duplicate").asBoolean());
        assertEquals(4, again.path("serverSeq").asLong());
        driver.send(send("d-2", "Photo du compteur envoyée."));
        assertEquals(5, driver.next("ack").path("serverSeq").asLong(), "séquence sans trou après réémission");
        manager.close();
        driver.close();
    }

    @Test
    @Order(3)
    void intruderAuditorAndInvalidSessionAreRefused() throws Exception {
        Client intruder = Client.connect("tok-agent.dla");
        intruder.next("ready");
        intruder.send(send("x-1", "intrusion"));
        assertEquals("FORBIDDEN", intruder.next("error").path("code").asText());
        intruder.send("{\"type\":\"sync\",\"conversationId\":\"" + CONV + "\",\"afterSeq\":0}");
        assertEquals("FORBIDDEN", intruder.next("error").path("code").asText(), "lecture refusée aussi");

        Client auditor = Client.connect("tok-auditeur");
        auditor.next("ready");
        auditor.send(send("a-1", "test"));
        assertEquals("FORBIDDEN", auditor.next("error").path("code").asText(), "auditeur : pas de chat:use");

        Client nobody = Client.connect("tok-invente");
        assertEquals("UNAUTHENTICATED", nobody.next("error").path("code").asText());

        try (Connection c = db(); ResultSet rs = c.createStatement().executeQuery("SELECT count(*), max(server_seq) FROM message")) {
            rs.next();
            assertEquals(5, rs.getLong(1));
            assertEquals(5, rs.getLong(2));
        }
        intruder.close();
        auditor.close();
    }

    @Test
    @Order(4)
    void revokedSessionCannotReconnect() throws Exception {
        try (Connection c = db()) {
            c.createStatement().executeUpdate("UPDATE app_user SET status = 'SUSPENDED' WHERE login = 'conducteur'");
        }
        Client driver = Client.connect("tok-conducteur");
        assertEquals("UNAUTHENTICATED", driver.next("error").path("code").asText());
    }
}
