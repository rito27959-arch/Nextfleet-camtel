package cm.camtel.nextfleet.desktop.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Journal chaîné : chaque événement porte le SHA-256 de (empreinte précédente | contenu). L'auditeur recalcule toute
 * la chaîne sur son poste ; une modification, une suppression ou une insertion la casse.
 */
public final class AuditChain {

    public static final String GENESIS = "0".repeat(64);

    private AuditChain() { }

    public static String hash(String previous, long seq, java.time.Instant at, String actor, String action, String object, String detail) {
        return sha256(previous + "|" + seq + "|" + at + "|" + actor + "|" + action + "|" + object + "|" + detail);
    }

    /** @return le numéro du premier événement invalide, ou -1 si la chaîne est intègre */
    public static long firstBroken(List<Ops.AuditEntry> entries) {
        List<Ops.AuditEntry> list = new ArrayList<>(entries);
        list.sort(Comparator.comparingLong(Ops.AuditEntry::seq));
        String prev = GENESIS;
        for (Ops.AuditEntry e : list) {
            if (!e.previousHash().equals(prev)) return e.seq();
            String h = hash(prev, e.seq(), e.at(), e.actor(), e.action(), e.object(), e.detail());
            if (!h.equals(e.hash())) return e.seq();
            prev = h;
        }
        return -1;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
