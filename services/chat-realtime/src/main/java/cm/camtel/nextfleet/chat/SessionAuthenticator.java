package cm.camtel.nextfleet.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Authentification par le jeton de session opaque de l'API (table {@code auth_session}, stocké en SHA-256) :
 * session non révoquée, non expirée, compte actif. Le jeton n'est jamais journalisé.
 */
public final class SessionAuthenticator {

    public record Principal(String userId, String displayName) { }

    private final Supplier<Connection> connections;

    public SessionAuthenticator(Supplier<Connection> connections) {
        this.connections = connections;
    }

    public Optional<Principal> authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 512) return Optional.empty();
        try (Connection c = connections.get();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT u.id, u.display_name FROM auth_session s JOIN app_user u ON u.id = s.user_id
                      WHERE s.token_sha256 = ? AND s.revoked_at IS NULL AND s.expires_at > now() AND u.status = 'ACTIVE'""")) {
            ps.setBytes(1, MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Principal(rs.getObject(1, UUID.class).toString(), rs.getString(2))) : Optional.empty();
            }
        } catch (SQLException | NoSuchAlgorithmException e) {
            return Optional.empty();
        }
    }
}
