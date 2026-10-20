package cm.camtel.nextfleet.api.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Simulateur de démonstration : fait avancer les missions en cours dont l'émetteur est {@code SIM-DEMO-*}, le long
 * d'itinéraires réels, pour que la carte en direct vive sans téléphone. Ne fonctionne QUE si la base est déclarée DEMO
 * ET que {@code NF_DEMO_SIMULATOR=true} ; les points portent le fournisseur {@code simulation-demo} et passent par les
 * mêmes contrôles de la base qu'un vrai téléphone (mission active, émetteur autorisé).
 */
@Component
public class DemoSimulator {

    private static final Logger LOG = LoggerFactory.getLogger(DemoSimulator.class);

    /** Itinéraires par émetteur : Yaoundé → Mbalmayo (aller-retour en 90 min), Kribi (véhicule garé). */
    static final Map<String, double[][]> ROUTES = Map.of(
            "SIM-DEMO-014", new double[][]{{3.8667, 11.5167}, {3.8410, 11.5098}, {3.8020, 11.5030}, {3.7560, 11.4990},
                    {3.7105, 11.4952}, {3.6680, 11.5010}, {3.6240, 11.5035}, {3.5790, 11.5012}, {3.5390, 11.4990}, {3.5172, 11.5003}},
            "SIM-DEMO-013", new double[][]{{2.6150, 9.8480}});

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private Boolean demoDatabase;

    public DemoSimulator(JdbcTemplate jdbc, @Value("${nextfleet.demo.simulator:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void tick() {
        if (!enabled) return;
        try {
            if (demoDatabase == null) demoDatabase = "DEMO".equals(jdbc.queryForObject("SELECT kind FROM app_environment", String.class));
            if (!demoDatabase) return;
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT m.id, m.vehicle_id, a.source_device_id, m.started_at FROM mission m
                      JOIN tracking_authorization a ON a.mission_id = m.id AND a.revoked_at IS NULL
                     WHERE m.status = 'ACTIVE' AND a.source_device_id LIKE 'SIM-DEMO-%'""");
            Instant now = Instant.now();
            for (Map<String, Object> r : rows) {
                String dev = (String) r.get("source_device_id");
                double[][] route = ROUTES.get(dev);
                if (route == null) continue;
                Instant started = ((Timestamp) r.get("started_at")).toInstant();
                double[] p;
                double speed;
                if (route.length == 1) {
                    p = route[0];
                    speed = 0;
                } else {
                    double cycle = 5400;   // aller en 45 min, retour en 45 min
                    double t = ((now.getEpochSecond() - started.getEpochSecond()) % (long) cycle) / cycle;
                    double f = t < 0.5 ? t * 2 : (1 - t) * 2;
                    p = along(route, f);
                    speed = 8 + 3 * Math.sin(now.getEpochSecond() / 37.0);
                    if (Math.abs(f - 1) < 0.02) speed = 0;   // arrêt sur site
                }
                jdbc.update("""
                        INSERT INTO location_point(mission_id, vehicle_id, source_device_id, client_fix_id, recorded_at, position,
                                                   accuracy_m, speed_mps, provider)
                        VALUES (?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, 'simulation-demo')
                        ON CONFLICT (source_device_id, client_fix_id) DO NOTHING""",
                        r.get("id"), r.get("vehicle_id"), dev, "sim-" + now.getEpochSecond(), Timestamp.from(now.minusSeconds(1)),
                        p[1], p[0], 6.0, speed);
            }
        } catch (RuntimeException e) {
            LOG.warn("Simulateur de démonstration : {}", e.getMessage());
        }
    }

    static double[] along(double[][] route, double fraction) {
        double total = 0;
        double[] cum = new double[route.length];
        for (int i = 1; i < route.length; i++) {
            total += Math.hypot(route[i][0] - route[i - 1][0], route[i][1] - route[i - 1][1]);
            cum[i] = total;
        }
        double target = total * Math.max(0, Math.min(1, fraction));
        for (int i = 1; i < route.length; i++) {
            if (cum[i] >= target) {
                double seg = cum[i] - cum[i - 1];
                double k = seg == 0 ? 0 : (target - cum[i - 1]) / seg;
                return new double[]{route[i - 1][0] + k * (route[i][0] - route[i - 1][0]), route[i - 1][1] + k * (route[i][1] - route[i - 1][1])};
            }
        }
        return route[route.length - 1];
    }
}
