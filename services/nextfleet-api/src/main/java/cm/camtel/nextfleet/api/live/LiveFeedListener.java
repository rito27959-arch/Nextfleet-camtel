package cm.camtel.nextfleet.api.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Écoute PostgreSQL {@code LISTEN nf_position} et {@code nf_message} sur une connexion DÉDIÉE (hors pool) et redistribue chaque position
 * acceptée aux abonnés (WebSocket). Reconnexion automatique avec attente progressive : une coupure de la base ne fait
 * pas tomber l'API, le flux reprend seul.
 */
@Component
public class LiveFeedListener {

    private static final Logger LOG = LoggerFactory.getLogger(LiveFeedListener.class);

    private final DataSourceProperties ds;
    private final ObjectMapper mapper;
    private final List<Consumer<JsonNode>> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private volatile boolean connected;
    private Thread worker;

    public LiveFeedListener(DataSourceProperties ds, ObjectMapper mapper) {
        this.ds = ds;
        this.mapper = mapper;
    }

    public void subscribe(Consumer<JsonNode> s) {
        subscribers.add(s);
    }

    public boolean connected() {
        return connected;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        worker = Thread.ofPlatform().daemon().name("nf-live-listen").start(this::loop);
    }

    private void loop() {
        long backoff = 1000;
        while (running) {
            try (Connection c = DriverManager.getConnection(ds.determineUrl(), ds.determineUsername(), ds.determinePassword())) {
                try (Statement st = c.createStatement()) {
                    st.execute("LISTEN nf_position");
                    st.execute("LISTEN nf_message");
                }
                PGConnection pg = c.unwrap(PGConnection.class);
                connected = true;
                backoff = 1000;
                LOG.info("Flux temps réel : écoute de nf_position et nf_message active");
                while (running) {
                    PGNotification[] ns = pg.getNotifications(10_000);
                    if (ns == null) {
                        try (Statement ping = c.createStatement()) {
                            ping.execute("SELECT 1");   // détecte une connexion morte entre deux notifications
                        }
                        continue;
                    }
                    for (PGNotification n : ns) dispatch(n.getName(), n.getParameter());
                }
            } catch (Exception e) {
                connected = false;
                if (!running) return;
                LOG.warn("Flux temps réel interrompu ({}), nouvelle tentative dans {} ms", e.getClass().getSimpleName(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 30_000);
            }
        }
    }

    void dispatch(String channel, String payload) {
        JsonNode event;
        try {
            event = mapper.readTree(payload);
            if (event instanceof com.fasterxml.jackson.databind.node.ObjectNode o) o.put("_channel", channel);
        } catch (Exception e) {
            LOG.warn("Notification illisible ignorée");
            return;
        }
        for (Consumer<JsonNode> s : subscribers) {
            try {
                s.accept(event);
            } catch (RuntimeException e) {
                LOG.warn("Abonné en erreur : {}", e.getClass().getSimpleName());
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
    }
}
