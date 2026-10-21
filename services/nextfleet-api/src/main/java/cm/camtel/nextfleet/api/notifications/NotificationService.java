package cm.camtel.nextfleet.api.notifications;

import cm.camtel.nextfleet.api.dues.DueService;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Centre de notifications calculé à la demande à partir de l'état réel (échéances, bons à valider, demandes d'atelier,
 * anomalies à instruire, missions autorisées) et filtré par les droits de chacun. Seul l'état « lu » est stocké.
 * Aucune donnée sensible dans les textes (pas de contenu de message, pas de position).
 */
@Service
public class NotificationService {

    public record NotificationView(String id, String kind, String title, String body, Instant at, boolean read,
                                   String targetSection, String targetQuery) { }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE);

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final DueService dues;

    public NotificationService(NamedParameterJdbcTemplate jdbc, AccessService access, DueService dues) {
        this.jdbc = jdbc;
        this.access = access;
        this.dues = dues;
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list() {
        AuthenticatedUser me = access.currentUser();
        Set<String> read = new HashSet<>(jdbc.queryForList("SELECT notification_key FROM notification_read WHERE user_id = :u",
                new MapSqlParameterSource("u", me.userId()), String.class));
        List<NotificationView> out = new ArrayList<>();

        for (DueService.DueView d : dues.upcoming(DueService.SOON_DAYS)) {
            String key = "due:" + d.kind() + ":" + d.subjectId() + ":" + d.dueOn();
            String what = label(d.kind());
            String title = d.daysLeft() < 0 ? what + " expirée" : what + " à renouveler";
            String body = d.subject() + " : " + (d.daysLeft() < 0
                    ? "échéance dépassée depuis le " + DAY.format(d.dueOn()) + "."
                    : "échéance le " + DAY.format(d.dueOn()) + " (dans " + d.daysLeft() + " jour" + (d.daysLeft() > 1 ? "s" : "") + ").");
            Instant at = d.dueOn().minusDays(DueService.SOON_DAYS).atTime(LocalTime.of(8, 0)).atZone(DueService.DOUALA).toInstant();
            out.add(new NotificationView(key, "ECHEANCE", title, body, at.isAfter(Instant.now()) ? Instant.now() : at,
                    read.contains(key), d.vehicle() ? "vehicles" : "drivers", d.subject()));
        }

        List<UUID> approve = access.scopeFor("fuel_order:approve");
        if (!approve.isEmpty()) {
            jdbc.query("""
                    SELECT o.id, o.order_number, v.registration, o.authorized_liters, o.created_at FROM fuel_order o
                      JOIN vehicle v ON v.id = o.vehicle_id
                     WHERE o.status = 'SUBMITTED' AND o.org_unit_id IN (:s) AND o.requested_by <> :me
                     ORDER BY o.created_at DESC LIMIT 50""",
                    new MapSqlParameterSource("s", approve).addValue("me", me.userId()), rs -> {
                        String key = "fuel:" + rs.getObject(1, UUID.class) + ":SUBMITTED";
                        out.add(new NotificationView(key, "CARBURANT", "Bon carburant à valider",
                                "Bon " + rs.getString(2) + " pour " + rs.getString(3) + " : " + rs.getBigDecimal(4).stripTrailingZeros().toPlainString()
                                        + " L demandés.", rs.getTimestamp(5).toInstant(), read.contains(key), "fuel", rs.getString(2)));
                    });
        }

        List<UUID> workshop = access.scopeFor("maintenance:write");
        if (!workshop.isEmpty()) {
            jdbc.query("""
                    SELECT mo.id, mo.title, v.registration, mo.created_at FROM maintenance_order mo JOIN vehicle v ON v.id = mo.vehicle_id
                     WHERE mo.status = 'DEMANDE' AND v.org_unit_id IN (:s) ORDER BY mo.created_at DESC LIMIT 50""",
                    new MapSqlParameterSource("s", workshop), rs -> {
                        String key = "mo:" + rs.getObject(1, UUID.class) + ":DEMANDE";
                        out.add(new NotificationView(key, "ENTRETIEN", "Nouvelle demande d'intervention",
                                rs.getString(3) + " : " + rs.getString(2) + ".", rs.getTimestamp(4).toInstant(), read.contains(key),
                                "workshop", rs.getString(3)));
                    });
        }

        List<UUID> review = access.scopeFor("anomaly:review");
        if (!review.isEmpty()) {
            jdbc.query("""
                    SELECT a.id, a.summary, a.detected_at FROM anomaly a
                     WHERE a.status = 'A_VERIFIER' AND a.org_unit_id IN (:s) ORDER BY a.detected_at DESC LIMIT 50""",
                    new MapSqlParameterSource("s", review), rs -> {
                        String key = "anomaly:" + rs.getObject(1, UUID.class);
                        out.add(new NotificationView(key, "SYSTEME", "Écart à vérifier", rs.getString(2),
                                rs.getTimestamp(3).toInstant(), read.contains(key), "anomalies", null));
                    });
        }

        // missions autorisées confiées au conducteur connecté
        jdbc.query("""
                SELECT m.id, m.reference, m.purpose, m.authorized_at FROM mission m JOIN driver d ON d.id = m.driver_id
                 WHERE d.user_id = :me AND m.status = 'AUTHORIZED' AND m.authorized_at IS NOT NULL
                 ORDER BY m.authorized_at DESC LIMIT 20""",
                new MapSqlParameterSource("me", me.userId()), rs -> {
                    String key = "mission:" + rs.getObject(1, UUID.class) + ":AUTHORIZED";
                    out.add(new NotificationView(key, "SYSTEME", "Mission autorisée",
                            rs.getString(2) + " : " + rs.getString(3) + ". Vous pouvez la démarrer depuis votre téléphone.",
                            rs.getTimestamp(4).toInstant(), read.contains(key), "missions", rs.getString(2)));
                });

        out.sort(Comparator.comparing(NotificationView::at).reversed());
        return out.size() > 150 ? out.subList(0, 150) : out;
    }

    @Transactional
    public void markRead(Collection<String> keys) {
        AuthenticatedUser me = access.currentUser();
        for (String k : keys) {
            if (k == null || k.length() < 3 || k.length() > 200) continue;
            jdbc.update("""
                    INSERT INTO notification_read(user_id, notification_key) VALUES (:u, :k)
                    ON CONFLICT (user_id, notification_key) DO NOTHING""",
                    new MapSqlParameterSource("u", me.userId()).addValue("k", k));
        }
    }

    static String label(String kind) {
        return switch (kind) {
            case "ASSURANCE" -> "Assurance";
            case "VIGNETTE" -> "Vignette";
            case "CONTROLE_TECHNIQUE" -> "Contrôle technique";
            case "CARTE_GRISE" -> "Carte grise";
            case "PERMIS" -> "Permis de conduire";
            case "CNI" -> "Carte d'identité";
            case "VIDANGE" -> "Vidange";
            default -> "Document";
        };
    }
}
