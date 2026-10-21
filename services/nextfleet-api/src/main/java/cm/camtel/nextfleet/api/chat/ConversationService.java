package cm.camtel.nextfleet.api.chat;

import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import cm.camtel.nextfleet.domain.chat.Chat;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Messagerie en REST (repli du canal temps réel, même base, mêmes règles) : seuls les participants actifs
 * détenant chat:use lisent et écrivent (confidentialité des conversations) ; réémission idempotente par
 * clientMessageId ; états Envoyé / Distribué / Lu calculés par {@link Chat#stateFor}.
 */
@Service
public class ConversationService {

    public record ConversationView(UUID id, String kind, String title, String subtitle, String lastBody, Instant lastAt, long unread) { }

    public record Attachment(UUID id, String name, String mimeType, long sizeBytes) { }

    public record Line(long serverSeq, String senderName, String body, Instant createdAt, boolean mine, String state, String clientMessageId,
                       List<Attachment> attachments) { }

    public record Contact(UUID userId, String displayName, String role, String orgUnit) { }

    public record Ack(long serverSeq, boolean duplicate, Instant createdAt) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;

    public ConversationService(NamedParameterJdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    private AuthenticatedUser chatter() {
        AuthenticatedUser u = access.currentUser();
        Boolean ok = jdbc.queryForObject("SELECT nf_has_permission(:me, 'chat:use')", new MapSqlParameterSource("me", u.userId()), Boolean.class);
        if (!Boolean.TRUE.equals(ok)) throw new AccessDeniedException("Messagerie hors profil");
        return u;
    }

    private void requireParticipant(UUID conv, UUID me) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM conversation_participant WHERE conversation_id = :c AND user_id = :me AND left_at IS NULL""",
                new MapSqlParameterSource("c", conv).addValue("me", me), Integer.class);
        // 403 générique : on ne révèle pas l'existence d'une conversation à un non-participant
        if (n == null || n == 0) throw new AccessDeniedException("Conversation inaccessible");
    }

    @Transactional(readOnly = true)
    public List<ConversationView> list() {
        AuthenticatedUser u = chatter();
        return jdbc.query("""
                SELECT c.id, c.kind,
                       coalesce(c.title, (SELECT string_agg(au.display_name, ', ') FROM conversation_participant op
                                            JOIN app_user au ON au.id = op.user_id
                                           WHERE op.conversation_id = c.id AND op.user_id <> :me AND op.left_at IS NULL)) AS title,
                       (SELECT m.reference || ' · ' || m.status FROM mission m WHERE m.id = c.mission_id) AS subtitle,
                       lm.body, lm.created_at, greatest(c.last_seq - p.last_read_seq, 0) AS unread
                  FROM conversation c
                  JOIN conversation_participant p ON p.conversation_id = c.id AND p.user_id = :me AND p.left_at IS NULL
                  LEFT JOIN LATERAL (SELECT body, created_at FROM message WHERE conversation_id = c.id ORDER BY server_seq DESC LIMIT 1) lm ON true
                 ORDER BY coalesce(lm.created_at, c.created_at) DESC""",
                new MapSqlParameterSource("me", u.userId()), (rs, i) -> new ConversationView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), ts(rs.getTimestamp(6)), rs.getLong(7)));
    }

    /** Messages après {@code afterSeq} ; marque la distribution jusqu'au dernier message renvoyé. */
    @Transactional
    public List<Line> messages(UUID conv, long afterSeq, int limit) {
        AuthenticatedUser u = chatter();
        requireParticipant(conv, u.userId());
        MapSqlParameterSource p = new MapSqlParameterSource("c", conv).addValue("me", u.userId()).addValue("after", afterSeq)
                .addValue("lim", Math.min(Math.max(limit, 1), 500));
        Map<String, Chat.Cursor> cursors = cursors(conv);
        record Raw(Chat.Message m, String sender, List<Attachment> files) { }
        List<Raw> raw = jdbc.query("""
                SELECT m.sender_id, m.client_message_id, m.server_seq, m.body, m.created_at, au.display_name,
                       coalesce((SELECT json_agg(json_build_object('id', f.id, 'name', f.original_name, 'mime', f.mime_type, 'size', f.size_bytes)
                                                 ORDER BY array_position(m.attachment_ids, f.id))::text
                                   FROM stored_file f WHERE f.id = ANY(m.attachment_ids)), '[]')
                  FROM message m JOIN app_user au ON au.id = m.sender_id
                 WHERE m.conversation_id = :c AND m.server_seq > :after ORDER BY m.server_seq LIMIT :lim""", p,
                (rs, i) -> new Raw(new Chat.Message(conv.toString(), rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant()), rs.getString(6), attachments(rs.getString(7))));
        if (!raw.isEmpty()) {
            jdbc.update("""
                    UPDATE conversation_participant SET last_delivered_seq = greatest(last_delivered_seq, :seq)
                     WHERE conversation_id = :c AND user_id = :me""", p.addValue("seq", raw.get(raw.size() - 1).m().serverSeq()));
        }
        String me = u.userId().toString();
        return raw.stream().map(r -> {
            boolean mine = r.m().senderId().equals(me);
            return new Line(r.m().serverSeq(), mine ? "Vous" : r.sender(), r.m().body(), r.m().createdAt(), mine,
                    mine ? Chat.stateFor(r.m(), cursors).name() : null, r.m().clientMessageId(), r.files());
        }).toList();
    }

    @Transactional
    public Ack post(UUID conv, String clientMessageId, String body) {
        return post(conv, clientMessageId, body, List.of());
    }

    @Transactional
    public Ack post(UUID conv, String clientMessageId, String body, List<UUID> attachmentIds) {
        AuthenticatedUser u = chatter();
        requireParticipant(conv, u.userId());
        Chat.validateBody(body);
        MapSqlParameterSource p = new MapSqlParameterSource("c", conv).addValue("me", u.userId()).addValue("cid", clientMessageId)
                .addValue("body", body).addValue("att", (attachmentIds == null ? List.<UUID>of() : attachmentIds).toArray(new UUID[0]));
        if (attachmentIds != null && attachmentIds.size() > 5) throw new IllegalArgumentException("5 pièces jointes au plus par message.");
        // Réémission reconnue AVANT l'insertion : le trigger de séquence s'exécute avant ON CONFLICT et
        // créerait sinon un trou dans la numérotation (compteur de non-lus faussé).
        Ack prior = prior(conv, p);
        if (prior != null) return prior;
        List<Ack> inserted = jdbc.query("""
                INSERT INTO message(conversation_id, sender_id, client_message_id, body, attachment_ids) VALUES (:c, :me, :cid, :body, :att)
                ON CONFLICT (sender_id, client_message_id) DO NOTHING RETURNING server_seq, created_at""", p,
                (rs, i) -> new Ack(rs.getLong(1), false, rs.getTimestamp(2).toInstant()));
        if (inserted.isEmpty()) return prior(conv, p); // course entre deux réémissions simultanées
        Ack a = inserted.get(0);
        jdbc.update("""
                UPDATE conversation_participant SET last_delivered_seq = greatest(last_delivered_seq, :seq),
                       last_read_seq = greatest(last_read_seq, :seq) WHERE conversation_id = :c AND user_id = :me""",
                p.addValue("seq", a.serverSeq()));
        return a;
    }

    private Ack prior(UUID conv, MapSqlParameterSource p) {
        record Prior(UUID conv, long seq, Instant at) { }
        List<Prior> found = jdbc.query("SELECT conversation_id, server_seq, created_at FROM message WHERE sender_id = :me AND client_message_id = :cid",
                p, (rs, i) -> new Prior(rs.getObject(1, UUID.class), rs.getLong(2), rs.getTimestamp(3).toInstant()));
        if (found.isEmpty()) return null;
        if (!conv.equals(found.get(0).conv())) throw new IllegalArgumentException("clientMessageId déjà utilisé dans une autre conversation.");
        return new Ack(found.get(0).seq(), true, found.get(0).at());
    }

    /** Lu jusqu'à {@code upTo} (ou jusqu'au dernier message si absent). */
    @Transactional
    public void markRead(UUID conv, Long upTo) {
        AuthenticatedUser u = chatter();
        requireParticipant(conv, u.userId());
        jdbc.update("""
                UPDATE conversation_participant p
                   SET last_delivered_seq = greatest(p.last_delivered_seq, s.seq), last_read_seq = greatest(p.last_read_seq, s.seq)
                  FROM (SELECT least(coalesce(CAST(:upTo AS bigint), c.last_seq), c.last_seq) AS seq FROM conversation c WHERE c.id = :c) s
                 WHERE p.conversation_id = :c AND p.user_id = :me""",
                new MapSqlParameterSource("c", conv).addValue("me", u.userId()).addValue("upTo", upTo));
    }

    private Map<String, Chat.Cursor> cursors(UUID conv) {
        Map<String, Chat.Cursor> m = new LinkedHashMap<>();
        jdbc.query("""
                SELECT user_id, last_delivered_seq, last_read_seq FROM conversation_participant
                 WHERE conversation_id = :c AND left_at IS NULL AND nf_has_permission(user_id, 'chat:use')""",
                new MapSqlParameterSource("c", conv), rs -> {
                    m.put(rs.getObject(1, UUID.class).toString(), new Chat.Cursor(rs.getLong(2), rs.getLong(3)));
                });
        return m;
    }

    /** Personnes joignables : comptes actifs avec chat:use dans les entités où l'on détient soi-même chat:use. */
    @Transactional(readOnly = true)
    public List<Contact> contacts(String q) {
        AuthenticatedUser u = chatter();
        List<UUID> scope = access.scopeFor("chat:use");
        if (scope.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT DISTINCT ON (au.display_name, au.id) au.id, au.display_name, r.label, o.name
                  FROM app_user au JOIN organisation_unit o ON o.id = au.org_unit_id
                  JOIN user_role_grant g ON g.user_id = au.id AND g.revoked_at IS NULL AND (g.valid_until IS NULL OR g.valid_until > now())
                  JOIN role r ON r.code = g.role_code
                 WHERE au.status = 'ACTIVE' AND au.id <> :me AND au.org_unit_id IN (:s) AND nf_has_permission(au.id, 'chat:use')
                   AND (CAST(:q AS text) IS NULL OR lower(au.display_name) LIKE :q)
                 ORDER BY au.display_name, au.id LIMIT 300""",
                new MapSqlParameterSource("me", u.userId()).addValue("s", scope)
                        .addValue("q", q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase() + "%"),
                (rs, i) -> new Contact(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    /**
     * Conversation directe (une autre personne : réutilise la conversation existante) ou de service (groupe nommé).
     * Chaque participant doit faire partie des contacts joignables.
     */
    @Transactional
    public ConversationView open(List<UUID> participantIds, String title) {
        AuthenticatedUser u = chatter();
        java.util.Set<UUID> others = new java.util.LinkedHashSet<>(participantIds == null ? List.of() : participantIds);
        others.remove(u.userId());
        if (others.isEmpty()) throw new IllegalArgumentException("Choisissez au moins un destinataire.");
        if (others.size() > 50) throw new IllegalArgumentException("50 participants au plus.");
        java.util.Set<UUID> reachable = new java.util.HashSet<>(contacts(null).stream().map(Contact::userId).toList());
        if (!reachable.containsAll(others)) throw new AccessDeniedException("Destinataire hors de votre périmètre de messagerie");
        UUID conv;
        if (others.size() == 1) {
            UUID other = others.iterator().next();
            List<UUID> existing = jdbc.queryForList("""
                    SELECT c.id FROM conversation c
                     WHERE c.kind = 'DIRECT'
                       AND EXISTS (SELECT 1 FROM conversation_participant p WHERE p.conversation_id = c.id AND p.user_id = :me AND p.left_at IS NULL)
                       AND EXISTS (SELECT 1 FROM conversation_participant p WHERE p.conversation_id = c.id AND p.user_id = :o AND p.left_at IS NULL)
                       AND (SELECT count(*) FROM conversation_participant p WHERE p.conversation_id = c.id AND p.left_at IS NULL) = 2
                     LIMIT 1""", new MapSqlParameterSource("me", u.userId()).addValue("o", other), UUID.class);
            if (!existing.isEmpty()) return list().stream().filter(c -> c.id().equals(existing.get(0))).findFirst().orElseThrow();
            conv = jdbc.queryForObject("INSERT INTO conversation(kind, org_unit_id) VALUES ('DIRECT', :o) RETURNING id",
                    new MapSqlParameterSource("o", u.orgUnitId()), UUID.class);
        } else {
            if (title == null || title.strip().length() < 3) throw new IllegalArgumentException("Nommez la conversation de groupe (3 caractères au moins).");
            conv = jdbc.queryForObject("INSERT INTO conversation(kind, org_unit_id, title) VALUES ('SERVICE', :o, :t) RETURNING id",
                    new MapSqlParameterSource("o", u.orgUnitId()).addValue("t", title.strip()), UUID.class);
        }
        java.util.List<UUID> all = new java.util.ArrayList<>(others);
        all.add(u.userId());
        for (UUID p : all) {
            jdbc.update("INSERT INTO conversation_participant(conversation_id, user_id) VALUES (:c, :u)",
                    new MapSqlParameterSource("c", conv).addValue("u", p));
        }
        UUID created = conv;
        return list().stream().filter(c -> c.id().equals(created)).findFirst().orElseThrow();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    static List<Attachment> attachments(String json) {
        if (json == null || json.equals("[]")) return List.of();
        try {
            List<Attachment> out = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : JSON.readTree(json)) {
                out.add(new Attachment(UUID.fromString(n.path("id").asText()), n.path("name").asText(), n.path("mime").asText(),
                        n.path("size").asLong()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
