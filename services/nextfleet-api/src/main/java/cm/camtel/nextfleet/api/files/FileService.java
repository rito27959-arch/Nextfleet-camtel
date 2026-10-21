package cm.camtel.nextfleet.api.files;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stockage privé des pièces justificatives (PDF, JPEG, PNG, WebP ; 10 Mo maximum).
 * Le type est déterminé par le CONTENU (signature binaire), jamais par l'extension ni par l'en-tête du client.
 * Lecture : déposant, ou détenteur du droit de lecture correspondant à l'usage dans l'entité, ou participant d'une
 * conversation où la pièce a été envoyée.
 */
@Service
public class FileService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;

    public record FileView(UUID id, String purpose, String originalName, String mimeType, long sizeBytes, String sha256,
                           Instant createdAt) { }

    public record FileContent(FileView meta, byte[] content) { }

    /** Usage → (permission de dépôt, permissions de lecture). */
    private static final Map<String, List<String>> WRITE = Map.of(
            "MAINTENANCE", List.of("maintenance:write"),
            "INSPECTION", List.of("maintenance:write"),
            "FUEL_RECEIPT", List.of("fuel_fill:create", "fuel_order:approve"),
            "VEHICLE_DOCUMENT", List.of("vehicle:write"),
            "DRIVER_DOCUMENT", List.of("driver:write"));
    private static final Map<String, List<String>> READ = Map.of(
            "MAINTENANCE", List.of("vehicle:read", "maintenance:write", "cost:read"),
            "INSPECTION", List.of("vehicle:read", "maintenance:write"),
            "FUEL_RECEIPT", List.of("fuel_order:approve", "cost:read", "audit:read"),
            "VEHICLE_DOCUMENT", List.of("vehicle:read"),
            "DRIVER_DOCUMENT", List.of("driver:read"));

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;

    public FileService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public FileView upload(String purpose, UUID orgUnitId, String originalName, byte[] content) {
        AuthenticatedUser u = access.currentUser();
        if (content == null || content.length == 0) throw new IllegalArgumentException("Fichier vide.");
        if (content.length > MAX_BYTES) throw new IllegalArgumentException("Fichier trop volumineux (10 Mo maximum).");
        String mime = sniff(content);
        if (mime == null) throw new IllegalArgumentException("Type de fichier non accepté : PDF, JPEG, PNG ou WebP uniquement.");
        String name = sanitizeName(originalName, mime);
        UUID org;
        if ("CHAT".equals(purpose)) {
            Boolean ok = jdbc.queryForObject("SELECT nf_has_permission(:me, 'chat:use')", new MapSqlParameterSource("me", u.userId()), Boolean.class);
            if (!Boolean.TRUE.equals(ok)) throw new AccessDeniedException("Messagerie hors profil");
            org = u.orgUnitId();
        } else {
            List<String> perms = WRITE.get(purpose);
            if (perms == null) throw new IllegalArgumentException("Usage de fichier inconnu.");
            if (orgUnitId == null) throw new IllegalArgumentException("Entité (orgUnitId) obligatoire pour ce dépôt.");
            requireAny(perms, orgUnitId, "stored_file", "new");
            org = orgUnitId;
        }
        String sha = sha256(content);
        UUID id = jdbc.queryForObject("""
                INSERT INTO stored_file(purpose, org_unit_id, original_name, mime_type, size_bytes, sha256, content, uploaded_by)
                VALUES (:p, :o, :n, :m, :s, :h, :c, :u) RETURNING id""",
                new MapSqlParameterSource("p", purpose).addValue("o", org).addValue("n", name).addValue("m", mime)
                        .addValue("s", (long) content.length).addValue("h", sha).addValue("c", content).addValue("u", u.userId()), UUID.class);
        FileView v = meta(id);
        audit.record(u.userId(), "UPLOAD", "stored_file", id.toString(), null, null, Map.of("purpose", purpose, "sha256", sha,
                "sizeBytes", content.length, "mimeType", mime));
        return v;
    }

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public FileContent download(UUID id) {
        FileView v = meta(id);
        requireRead(id, v.purpose());
        byte[] c = jdbc.queryForObject("SELECT content FROM stored_file WHERE id = :id", new MapSqlParameterSource("id", id), byte[].class);
        return new FileContent(v, c);
    }

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public FileView metadata(UUID id) {
        FileView v = meta(id);
        requireRead(id, v.purpose());
        return v;
    }

    /** Contrôle qu'un fichier existe, a l'usage attendu et appartient à l'entité de l'objet qui le référence. */
    public void requireAttachable(UUID fileId, String purpose, UUID orgUnitId) {
        if (fileId == null) return;
        List<Map<String, Object>> r = jdbc.queryForList("SELECT purpose, org_unit_id FROM stored_file WHERE id = :id",
                new MapSqlParameterSource("id", fileId));
        if (r.isEmpty() || !purpose.equals(r.get(0).get("purpose")) || !orgUnitId.equals(r.get(0).get("org_unit_id"))) {
            throw new IllegalArgumentException("Justificatif inconnu ou déposé pour une autre entité.");
        }
    }

    private void requireRead(UUID id, String purpose) {
        AuthenticatedUser u = access.currentUser();
        Map<String, Object> r = jdbc.queryForMap("SELECT uploaded_by, org_unit_id FROM stored_file WHERE id = :id", new MapSqlParameterSource("id", id));
        if (u.userId().equals(r.get("uploaded_by"))) return;
        if ("CHAT".equals(purpose)) {
            Integer n = jdbc.queryForObject("""
                    SELECT count(*) FROM message m
                      JOIN conversation_participant p ON p.conversation_id = m.conversation_id AND p.user_id = :me AND p.left_at IS NULL
                     WHERE :f = ANY(m.attachment_ids) AND nf_has_permission(:me, 'chat:use')""",
                    new MapSqlParameterSource("me", u.userId()).addValue("f", id), Integer.class);
            if (n != null && n > 0) return;
            audit.recordDenied(u.userId(), "stored_file", id.toString(), "chat:use");
            throw new AccessDeniedException("Accès refusé");
        }
        requireAny(READ.getOrDefault(purpose, List.of()), (UUID) r.get("org_unit_id"), "stored_file", id.toString());
    }

    private void requireAny(List<String> perms, UUID org, String type, String objectId) {
        AccessDeniedException last = new AccessDeniedException("Accès refusé");
        for (int i = 0; i < perms.size(); i++) {
            List<UUID> scope = access.scopeFor(perms.get(i));
            if (scope.contains(org)) return;
        }
        // décision et journalisation par le point unique (refus tracé avec la première permission attendue)
        if (!perms.isEmpty()) access.require(perms.get(0), org, type, objectId);
        throw last;
    }

    private FileView meta(UUID id) {
        List<FileView> l = jdbc.query("""
                SELECT id, purpose, original_name, mime_type, size_bytes, sha256, created_at FROM stored_file WHERE id = :id""",
                new MapSqlParameterSource("id", id), (rs, i) -> new FileView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant()));
        if (l.isEmpty()) throw new NotFoundException("Fichier introuvable.");
        return l.get(0);
    }

    /** Signature binaire : %PDF, JPEG (FF D8 FF), PNG (89 50 4E 47 0D 0A 1A 0A), WebP (RIFF....WEBP). */
    static String sniff(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    static String sanitizeName(String name, String mime) {
        String n = name == null ? "" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").strip();
        if (n.isEmpty()) n = "piece";
        if (n.length() > 120) n = n.substring(0, 120);
        String ext = switch (mime) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".webp";
        };
        String lower = n.toLowerCase();
        if (!(lower.endsWith(ext) || (ext.equals(".jpg") && lower.endsWith(".jpeg")))) n = n + ext;
        return n;
    }

    static String sha256(byte[] c) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
