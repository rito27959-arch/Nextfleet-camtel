package cm.camtel.nextfleet.domain.gps;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Jeton de mission éphémère (architecture V5) : remis au téléphone au démarrage d'une mission autorisée, il lie
 * l'émetteur à UNE mission et expire. Format : {@code base64url(missionId|deviceId|expEpoch).base64url(HMAC-SHA256)}.
 * La clé est un secret serveur (coffre DSIR), jamais livré aux clients.
 */
public final class MissionToken {

    public record Claims(UUID missionId, String deviceId, Instant expiresAt) { }

    public static final class InvalidToken extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidToken(String message) {
            super(message);
        }
    }

    private final byte[] key;

    public MissionToken(byte[] key) {
        if (key.length < 32) throw new IllegalArgumentException("Clé HMAC de 32 octets au moins");
        this.key = key.clone();
    }

    public String issue(UUID missionId, String deviceId, Instant now, Duration ttl) {
        if (deviceId.contains("|")) throw new IllegalArgumentException("identifiant d'émetteur invalide");
        String payload = missionId + "|" + deviceId + "|" + now.plus(ttl).getEpochSecond();
        return b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + b64(mac(payload));
    }

    public Claims verify(String token, Instant now) {
        if (token == null) throw new InvalidToken("jeton absent");
        int dot = token.indexOf('.');
        if (dot <= 0) throw new InvalidToken("jeton mal formé");
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, mac(payload))) throw new InvalidToken("signature invalide");
        } catch (IllegalArgumentException e) {
            throw new InvalidToken("jeton mal formé");
        }
        String[] parts = payload.split("\\|");
        if (parts.length != 3) throw new InvalidToken("jeton mal formé");
        Instant exp = Instant.ofEpochSecond(Long.parseLong(parts[2]));
        if (!now.isBefore(exp)) throw new InvalidToken("jeton expiré");
        return new Claims(UUID.fromString(parts[0]), parts[1], exp);
    }

    private byte[] mac(String payload) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
