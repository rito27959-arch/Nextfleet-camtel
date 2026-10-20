package cm.camtel.nextfleet.gps;

import cm.camtel.nextfleet.domain.gps.GpsFix;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Lecture du message envoyé par Traccar Server avec {@code forward.type=json} :
 * corps {@code {"position": {...}, "device": {...}}} produit par {@code PositionForwarderJson}
 * (source Traccar, commit 0745a8c du 19/10/2026). Points d'attention vérifiés dans ce code source :
 * <ul>
 *   <li>dates ISO 8601 avec décalage ({@code WRITE_DATES_AS_TIMESTAMPS} désactivé) ;</li>
 *   <li>vitesse en NŒUDS (unité interne de Traccar) ;</li>
 *   <li>l'identifiant métier de l'émetteur est {@code device.uniqueId}, pas l'identifiant numérique interne.</li>
 * </ul>
 */
public final class TraccarForwardParser {

    public static final double MPS_PER_KNOT = 0.514444;

    /** Position Traccar réduite aux champs utiles ; {@code fix} est la forme métier. */
    public record TraccarPosition(String uniqueId, long traccarPositionId, String protocol, boolean valid, GpsFix fix,
                                  Double courseDeg) { }

    public static final class InvalidPayload extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidPayload(String message) {
            super(message);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    public TraccarPosition parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (java.io.IOException e) {
            throw new InvalidPayload("JSON illisible");
        }
        if (root == null || !root.isObject()) throw new InvalidPayload("Objet JSON attendu");
        JsonNode p = root.path("position");
        JsonNode d = root.path("device");
        String uniqueId = text(d, "uniqueId");
        if (uniqueId == null || uniqueId.isBlank()) throw new InvalidPayload("device.uniqueId absent");
        if (!p.isObject()) throw new InvalidPayload("position absente");
        long id = p.path("id").asLong(0);
        if (id <= 0) throw new InvalidPayload("position.id absent");
        Instant fixTime = instant(text(p, "fixTime"));
        if (!p.path("latitude").isNumber() || !p.path("longitude").isNumber()) throw new InvalidPayload("coordonnées absentes");
        double lat = p.path("latitude").asDouble();
        double lon = p.path("longitude").asDouble();
        double accuracy = p.path("accuracy").isNumber() ? p.path("accuracy").asDouble() : 0;
        Double speedMps = p.path("speed").isNumber() ? p.path("speed").asDouble() * MPS_PER_KNOT : null;
        Double course = p.path("course").isNumber() ? p.path("course").asDouble() : null;
        try {
            GpsFix fix = new GpsFix("traccar:" + id, fixTime, lat, lon, Math.max(0, accuracy), speedMps);
            return new TraccarPosition(uniqueId.strip(), id, text(p, "protocol"), p.path("valid").asBoolean(true), fix, course);
        } catch (IllegalArgumentException e) {
            throw new InvalidPayload(e.getMessage());
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private static Instant instant(String s) {
        if (s == null) throw new InvalidPayload("position.fixTime absent");
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e2) {
                throw new InvalidPayload("position.fixTime illisible");
            }
        }
    }
}
