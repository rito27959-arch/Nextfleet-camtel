package cm.camtel.nextfleet.desktop.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleConsumer;

/**
 * Mise à jour automatique du poste. Le serveur NexFleet annonce sa version et l'adresse des paquets de cette version
 * ({@code GET /api/v1/app/update}) ; si elle est plus récente que celle du poste, le paquet est téléchargé puis installé :
 * <ul>
 *   <li>Windows (installation MSI par utilisateur) : {@code msiexec /i … /passive}, sans droits administrateur ni
 *       clic, puis l'application redémarre ;</li>
 *   <li>Ubuntu / Debian (paquet .deb) : {@code pkexec apt-get install} (le mot de passe du poste est demandé par le
 *       système), puis redémarrage ;</li>
 *   <li>version portable ou lancement de développement : téléchargement manuel proposé.</li>
 * </ul>
 * Les paquets viennent uniquement de l'adresse annoncée par le serveur auquel l'application est connectée, en HTTPS.
 */
public final class Updater {

    public record Info(String version, String windowsMsi, String linuxDeb, String notesUrl) { }

    public enum Kind { WINDOWS_MSI, LINUX_DEB, MANUAL }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private Updater() { }

    /** Version annoncée par le serveur, si elle est plus récente que celle du poste. */
    public static Optional<Info> check(URI server, String current) {
        if (server == null || server.getHost() == null) return Optional.empty();
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(server.resolve("/api/v1/app/update"))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) return Optional.empty();
            JsonNode n = JSON.readTree(r.body());
            Info info = new Info(n.path("version").asText(""), n.path("windowsMsi").asText(null), n.path("linuxDeb").asText(null),
                    n.path("notesUrl").asText(null));
            return newer(info.version(), current) ? Optional.of(info) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** {@code true} si {@code remote} (ex. 0.11.0) est strictement plus récente que {@code local}. */
    public static boolean newer(String remote, String local) {
        int[] a = parse(remote), b = parse(local);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }

    private static int[] parse(String v) {
        if (v == null) return null;
        String[] p = v.strip().split("[.-]");
        if (p.length < 3) return null;
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Comment ce poste peut s'installer lui-même, selon la façon dont l'application a été installée. */
    public static Kind kind() {
        return kind(System.getProperty("os.name", ""), System.getProperty("jpackage.app-path"), System.getenv("LOCALAPPDATA"));
    }

    public static Kind kind(String os, String appPath, String localAppData) {
        if (appPath == null || appPath.isBlank()) return Kind.MANUAL;
        String o = os.toLowerCase(Locale.ROOT);
        String p = appPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (o.contains("win")) {
            // l'installateur MSI par utilisateur place l'application dans %LOCALAPPDATA% ; ailleurs : version portable
            String lad = localAppData == null ? "" : localAppData.replace('\\', '/').toLowerCase(Locale.ROOT);
            return !lad.isEmpty() && p.startsWith(lad) ? Kind.WINDOWS_MSI : Kind.MANUAL;
        }
        if (o.contains("linux") && p.startsWith("/opt/nexfleet/")) return Kind.LINUX_DEB;
        return Kind.MANUAL;
    }

    public static String packageUrl(Info info, Kind kind) {
        return switch (kind) {
            case WINDOWS_MSI -> info.windowsMsi();
            case LINUX_DEB -> info.linuxDeb();
            case MANUAL -> null;
        };
    }

    /** Télécharge le paquet dans le dossier utilisateur ; {@code progress} reçoit 0..1 (ou -1 si la taille est inconnue). */
    public static Path download(String url, String version, DoubleConsumer progress) throws IOException, InterruptedException {
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme())) throw new IOException("Adresse de téléchargement non sécurisée.");
        Path dir = Path.of(System.getProperty("user.home"), ".nexfleet", "mises-a-jour");
        Files.createDirectories(dir);
        String name = u.getPath().substring(u.getPath().lastIndexOf('/') + 1);
        if (name.isBlank() || name.contains("..")) name = "nexfleet-" + version;
        Path part = dir.resolve(name + ".part"), dest = dir.resolve(name);
        HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(u).timeout(Duration.ofMinutes(15)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException("Paquet de la version " + version + " introuvable (HTTP " + r.statusCode() + ").");
        }
        long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                progress.accept(total > 0 ? (double) done / total : -1);
            }
            if (total > 0 && done != total) throw new IOException("Téléchargement interrompu.");
            if (done < 1_000_000) throw new IOException("Paquet téléchargé invalide.");
        }
        Files.move(part, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    /**
     * Lance l'installation dans un processus séparé, qui attend la fermeture de l'application, installe le paquet puis
     * relance NexFleet. L'appelant doit quitter l'application juste après.
     */
    public static void installAndRestart(Kind kind, Path pkg) throws IOException {
        String app = System.getProperty("jpackage.app-path");
        Path dir = pkg.getParent();
        if (kind == Kind.WINDOWS_MSI) {
            Path log = dir.resolve("installation.log");
            Path script = dir.resolve("installer.cmd");
            Files.writeString(script, String.join("\r\n",
                    "@echo off",
                    "timeout /t 3 /nobreak >nul",
                    "msiexec /i \"" + pkg + "\" /passive /norestart /l*v \"" + log + "\"",
                    "start \"\" \"" + app + "\"", ""), StandardCharsets.UTF_8);
            new ProcessBuilder("cmd.exe", "/c", "start", "\"NexFleet\"", "/min", script.toString())
                    .directory(dir.toFile()).start();
        } else if (kind == Kind.LINUX_DEB) {
            Path script = dir.resolve("installer.sh");
            Files.writeString(script, String.join("\n",
                    "#!/bin/sh",
                    "sleep 2",
                    "pkexec apt-get install -y --allow-downgrades '" + pkg.toString().replace("'", "") + "' && exec '" + app + "'",
                    "exec '" + app + "'", ""), StandardCharsets.UTF_8);
            new ProcessBuilder("setsid", "sh", script.toString()).directory(dir.toFile()).start();
        } else {
            throw new IOException("Installation automatique impossible pour cette version de l'application.");
        }
    }
}
