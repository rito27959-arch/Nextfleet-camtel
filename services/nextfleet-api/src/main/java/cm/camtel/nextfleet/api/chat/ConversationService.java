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

    public record Line(long serverSeq, String senderName, String body, Instant createdAt, boolean mine, String state, String clientMessageId) { }

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
        record Raw(Chat.Message m, String sender) { }
        List<Raw> raw = jdbc.query("""
                SELECT m.sender_id, m.client_message_id, m.server_seq, m.body, m.created_at, au.display_name
                  FROM message m JOIN app_user au ON au.id = m.sender_id
                 WHERE m.conversation_id = :c AND m.server_seq > :after ORDER BY m.server_seq LIMIT :lim""", p,
                (rs, i) -> new Raw(new Chat.Message(conv.toString(), rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant()), rs.getString(6)));
        if (!raw.isEmpty()) {
            jdbc.update("""
                    UPDATE conversation_participant SET last_delivered_seq = greatest(last_delivered_seq, :seq)
                     WHERE conversation_id = :c AND user_id = :me""", p.addValue("seq", raw.get(raw.size() - 1).m().serverSeq()));
        }
        String me = u.userId().toString();
        return raw.stream().map(r -> {
            boolean mine = r.m().senderId().equals(me);
            return new Line(r.m().serverSeq(), mine ? "Vous" : r.sender(), r.m().body(), r.m().createdAt(), mine,
                    mine ? Chat.stateFor(r.m(), cursors).name() : null, r.m().clientMessageId());
        }).toList();
    }

    @Transactional
    public Ack post(UUID conv, String clientMessageId, String body) {
        AuthenticatedUser u = chatter();
        requireParticipant(conv, u.userId());
        Chat.validateBody(body);
        MapSqlParameterSource p = new MapSqlParameterSource("c", conv).addValue("me", u.userId()).addValue("cid", clientMessageId)
                .addValue("body", body);
        // Réémission reconnue AVANT l'insertion : le trigger de séquence s'exécute avant ON CONFLICT et
        // créerait sinon un trou dans la numérotation (compteur de non-lus faussé).
        Ack prior = prior(conv, p);
        if (prior != null) return prior;
        List<Ack> inserted = jdbc.query("""
                INSERT INTO message(conversation_id, sender_id, client_message_id, body) VALUES (:c, :me, :cid, :body)
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

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
