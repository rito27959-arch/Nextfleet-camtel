package cm.camtel.nextfleet.mobile.data;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dernières réponses JSON des lectures, avec leur ETag : affichage instantané à l'ouverture d'un écran, revalidation
 * économe (304) et consultation hors connexion. Stockage privé de l'application (illisible des autres applications),
 * limité à 300 Ko par réponse, effacé à la déconnexion.
 */
final class ResponseStore {

    static final class Saved {
        final String etag;
        final String body;
        final long savedAt;

        Saved(String etag, String body, long savedAt) {
            this.etag = etag;
            this.body = body;
            this.savedAt = savedAt;
        }
    }

    private static final int MAX_BODY = 300_000;
    private final File dir;
    private final Map<String, Saved> memory = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Saved> e) {
            return size() > 64;
        }
    };

    ResponseStore(Context c) {
        dir = new File(c.getNoBackupFilesDir(), "nf-responses");
    }

    synchronized Saved get(String path) {
        Saved e = memory.get(path);
        if (e != null) return e;
        File f = file(path);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] all = new byte[(int) Math.min(f.length(), MAX_BODY + 4096)];
            int off = 0, n;
            while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
            String s = new String(all, 0, off, StandardCharsets.UTF_8);
            int a = s.indexOf('\n'), b = a < 0 ? -1 : s.indexOf('\n', a + 1);
            if (b < 0) return null;
            e = new Saved(s.substring(0, a).isEmpty() ? null : s.substring(0, a), s.substring(b + 1), Long.parseLong(s.substring(a + 1, b)));
            memory.put(path, e);
            return e;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    synchronized void put(String path, String etag, String body) {
        if (body.length() > MAX_BODY) return;
        Saved e = new Saved(etag, body, System.currentTimeMillis());
        memory.put(path, e);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(dir, file(path).getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(((etag == null ? "" : etag) + "\n" + e.savedAt + "\n" + body).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return;
        }
        if (!tmp.renameTo(file(path))) tmp.delete();
    }

    synchronized void clear() {
        memory.clear();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private File file(String path) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format("%02x", h[i]));
            return new File(dir, b.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
