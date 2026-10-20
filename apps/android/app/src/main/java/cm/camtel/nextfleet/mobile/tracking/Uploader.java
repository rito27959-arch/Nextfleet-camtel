package cm.camtel.nextfleet.mobile.tracking;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Envoi des positions à la passerelle GPS NexFleet ({@code POST /gps/android/fixes}, jeton « Mission »).
 * La passerelle contrôle chaque point (mission active, émetteur autorisé) puis relaie les points acceptés à Traccar.
 */
public final class Uploader {

    public static final class Result {
        public final int accepted, duplicates, rejected;
        public final List<String> settled;

        Result(int accepted, int duplicates, int rejected, List<String> settled) {
            this.accepted = accepted;
            this.duplicates = duplicates;
            this.rejected = rejected;
            this.settled = settled;
        }
    }

    public static final class AuthException extends IOException {
        private static final long serialVersionUID = 1L;

        AuthException(String m) {
            super(m);
        }
    }

    private Uploader() { }

    public static Result send(String gatewayUrl, String missionToken, List<FixCodec.Fix> fixes) throws IOException {
        String body;
        try {
            body = FixCodec.batch(fixes);
        } catch (JSONException e) {
            throw new IOException("Lot illisible", e);
        }
        String base = gatewayUrl.endsWith("/") ? gatewayUrl.substring(0, gatewayUrl.length() - 1) : gatewayUrl;
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/gps/android/fixes").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(8_000);
            c.setReadTimeout(20_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Mission " + missionToken);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 401) throw new AuthException("Jeton de mission refusé (expiré ou révoqué)");
            if (code != 200) throw new IOException("Passerelle : code " + code);
            return parse(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    public static Result parse(String response) throws IOException {
        try {
            int a = 0, d = 0, r = 0;
            List<String> settled = new ArrayList<>();
            for (FixCodec.Outcome o : FixCodec.outcomes(response)) {
                if (!o.settled()) continue;
                settled.add(o.clientFixId);
                if ("ACCEPTED".equals(o.outcome)) a++;
                else if ("DUPLICATE".equals(o.outcome)) d++;
                else r++;
            }
            return new Result(a, d, r, settled);
        } catch (JSONException e) {
            throw new IOException("Réponse de la passerelle illisible", e);
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = s.read(b)) > 0) out.write(b, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
