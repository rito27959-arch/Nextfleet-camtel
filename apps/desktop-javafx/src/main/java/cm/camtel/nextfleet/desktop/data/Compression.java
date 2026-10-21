package cm.camtel.nextfleet.desktop.data;

import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Décompression gzip des réponses : le client Java ne la fait pas seul. Les listes JSON de l'API se compressent
 * d'environ 85 %, ce qui compte sur les liaisons mobiles et satellitaires des sites CAMTEL.
 */
final class Compression {

    private Compression() { }

    static byte[] decode(HttpResponse<byte[]> res) {
        byte[] body = res.body() == null ? new byte[0] : res.body();
        boolean gz = res.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase().contains("gzip")).orElse(false);
        if (!gz || body.length == 0) return body;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new GatewayException(GatewayException.Kind.SERVER, "Réponse compressée illisible.");
        }
    }

    /** Même réponse, corps décompressé (pour les appelants du transport binaire). */
    static HttpResponse<byte[]> withBody(HttpResponse<byte[]> res, byte[] body) {
        if (body == res.body()) return res;
        return new HttpResponse<>() {
            @Override public int statusCode() { return res.statusCode(); }
            @Override public HttpRequest request() { return res.request(); }
            @Override public Optional<HttpResponse<byte[]>> previousResponse() { return res.previousResponse(); }
            @Override public HttpHeaders headers() { return res.headers(); }
            @Override public byte[] body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return res.sslSession(); }
            @Override public URI uri() { return res.uri(); }
            @Override public HttpClient.Version version() { return res.version(); }
        };
    }
}
