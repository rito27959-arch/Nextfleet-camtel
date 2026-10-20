package cm.camtel.nextfleet.chat;

import cm.camtel.nextfleet.domain.chat.Chat;
import cm.camtel.nextfleet.domain.chat.Chat.AccessDenied;
import cm.camtel.nextfleet.domain.chat.Chat.Cursor;
import cm.camtel.nextfleet.domain.chat.Chat.Message;
import cm.camtel.nextfleet.domain.chat.Chat.PostResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Stockage PostgreSQL de la messagerie. Les règles sont appliquées par la base (triggers V3/V5) :
 * participant actif, permission chat:use, séquence sous verrou, curseurs monotones. Le message est validé
 * (COMMIT) AVANT que l'accusé ne parte vers le client.
 */
public final class JdbcChatStore implements Chat.Store {

    private final Supplier<Connection> connections;

    public JdbcChatStore(Supplier<Connection> connections) {
        this.connections = connections;
    }

    @Override
    public PostResult post(String conversationId, String senderId, String clientMessageId, String body) {
        Chat.validateBody(body);
        if (clientMessageId == null || clientMessageId.isBlank() || clientMessageId.length() > 80) {
            throw new IllegalArgumentException("clientMessageId invalide");
        }
        try (Connection c = connections.get()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO message(conversation_id, sender_id, client_message_id, body) VALUES (?, ?, ?, ?)
                    ON CONFLICT (sender_id, client_message_id) DO NOTHING
                    RETURNING server_seq, created_at""")) {
                ps.setObject(1, UUID.fromString(conversationId));
                ps.setObject(2, UUID.fromString(senderId));
                ps.setString(3, clientMessageId);
                ps.setString(4, body);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Message m = new Message(conversationId, senderId, clientMessageId, rs.getLong(1), body,
                                rs.getTimestamp(2).toInstant());
                        markOwnRead(c, conversationId, senderId, m.serverSeq());
                        return new PostResult(m, false);
                    }
                }
            }
            // Réémission : on renvoie le message déjà enregistré (même conversation exigée).
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT conversation_id, server_seq, body, created_at FROM message WHERE sender_id = ? AND client_message_id = ?""")) {
                ps.setObject(1, UUID.fromString(senderId));
                ps.setString(2, clientMessageId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getObject(1, UUID.class).toString().equals(conversationId)) {
                        throw new IllegalArgumentException("clientMessageId déjà utilisé dans une autre conversation");
                    }
                    return new PostResult(new Message(conversationId, senderId, clientMessageId, rs.getLong(2), rs.getString(3),
                            rs.getTimestamp(4).toInstant()), true);
                }
            }
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    private static void markOwnRead(Connection c, String conv, String user, long seq) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE conversation_participant SET last_read_seq = ? WHERE conversation_id = ? AND user_id = ?")) {
            ps.setLong(1, seq);
            ps.setObject(2, UUID.fromString(conv));
            ps.setObject(3, UUID.fromString(user));
            ps.executeUpdate();
        }
    }

    @Override
    public List<Message> after(String conversationId, String readerId, long afterSeq, int limit) {
        try (Connection c = connections.get()) {
            requireReader(c, conversationId, readerId);
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT sender_id, client_message_id, server_seq, body, created_at FROM message
                     WHERE conversation_id = ? AND server_seq > ? ORDER BY server_seq LIMIT ?""")) {
                ps.setObject(1, UUID.fromString(conversationId));
                ps.setLong(2, afterSeq);
                ps.setInt(3, Math.min(Math.max(limit, 1), 500));
                List<Message> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Message(conversationId, rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getLong(3),
                                rs.getString(4), rs.getTimestamp(5).toInstant()));
                    }
                }
                return out;
            }
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    @Override
    public void ackDelivered(String conversationId, String userId, long seq) {
        cursor(conversationId, userId, "last_delivered_seq", seq);
    }

    @Override
    public void ackRead(String conversationId, String userId, long seq) {
        cursor(conversationId, userId, "last_read_seq", seq);
    }

    private void cursor(String conversationId, String userId, String column, long seq) {
        try (Connection c = connections.get()) {
            requireReader(c, conversationId, userId);
            try (PreparedStatement ps = c.prepareStatement("UPDATE conversation_participant SET " + column
                    + " = ? WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL")) {
                ps.setLong(1, seq);
                ps.setObject(2, UUID.fromString(conversationId));
                ps.setObject(3, UUID.fromString(userId));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    @Override
    public Map<String, Cursor> cursors(String conversationId, String readerId) {
        try (Connection c = connections.get()) {
            requireReader(c, conversationId, readerId);
            Map<String, Cursor> m = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT user_id, last_delivered_seq, last_read_seq FROM conversation_participant
                     WHERE conversation_id = ? AND left_at IS NULL AND nf_has_permission(user_id, 'chat:use')""")) {
                ps.setObject(1, UUID.fromString(conversationId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) m.put(rs.getObject(1, UUID.class).toString(), new Cursor(rs.getLong(2), rs.getLong(3)));
                }
            }
            return m;
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /** Participants actifs autorisés à recevoir les messages en temps réel. */
    public List<String> audience(String conversationId) {
        try (Connection c = connections.get();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT user_id FROM conversation_participant
                      WHERE conversation_id = ? AND left_at IS NULL AND nf_has_permission(user_id, 'chat:use')""")) {
            ps.setObject(1, UUID.fromString(conversationId));
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getObject(1, UUID.class).toString());
            }
            return out;
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /** La lecture exige aussi participation active + chat:use (confidentialité des conversations). */
    private static void requireReader(Connection c, String conversationId, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM conversation_participant WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
                   AND nf_has_permission(user_id, 'chat:use')""")) {
            ps.setObject(1, UUID.fromString(conversationId));
            ps.setObject(2, UUID.fromString(userId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new AccessDenied("Conversation inaccessible.");
            }
        }
    }

    private static RuntimeException translate(SQLException e) {
        if ("42501".equals(e.getSQLState())) return new AccessDenied("Vous ne pouvez pas écrire dans cette conversation.");
        if ("23514".equals(e.getSQLState())) return new IllegalArgumentException("Accusé invalide");
        return new IllegalStateException("Messagerie momentanément indisponible", e);
    }
}
