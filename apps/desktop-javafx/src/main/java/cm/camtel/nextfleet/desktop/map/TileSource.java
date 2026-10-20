package cm.camtel.nextfleet.desktop.map;

import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Source de tuiles raster {z}/{x}/{y}. Par défaut le serveur de tuiles d'OpenStreetMap (usage modéré, attribution
 * obligatoire, identification de l'application) ; en exploitation, la DSIR fournit son propre serveur via
 * {@code --tiles=https://tuiles.camtel.cm/{z}/{x}/{y}.png} ou {@code NF_TILE_URL}. Cache mémoire et disque
 * (~/.nexfleet/tuiles, 7 jours) pour limiter la charge et fonctionner hors ligne sur les zones déjà vues.
 */
public final class TileSource {

    public static final String OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    public static final String OSM_ATTRIBUTION = "© les contributeurs d'OpenStreetMap";

    private final String template;
    private final String attribution;
    private final int maxZoom;
    private final Path cacheDir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService io = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "nf-tuiles");
        t.setDaemon(true);
        return t;
    });
    private final Semaphore inFlight = new Semaphore(6);   // politique OSM : peu de connexions simultanées
    private final Map<String, Image> memory = java.util.Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> e) {
            return size() > 600;
        }
    });
    private final Map<String, CompletableFuture<Image>> pending = new ConcurrentHashMap<>();
    private volatile boolean offline;

    public TileSource(String template, String attribution, int maxZoom, Path cacheDir) {
        if (!template.startsWith("https://") && !template.startsWith("http://localhost")) {
            throw new IllegalArgumentException("Serveur de tuiles HTTPS requis : " + template);
        }
        this.template = template;
        this.attribution = attribution;
        this.maxZoom = maxZoom;
        this.cacheDir = cacheDir;
    }

    public static TileSource fromEnvironment(String override) {
        String url = override != null && !override.isBlank() ? override : System.getenv().getOrDefault("NF_TILE_URL", OSM);
        String attr = url.equals(OSM) ? OSM_ATTRIBUTION : System.getenv().getOrDefault("NF_TILE_ATTRIBUTION", "Fond de carte : serveur de tuiles CAMTEL");
        return new TileSource(url, attr, 19, Path.of(System.getProperty("user.home"), ".nexfleet", "tuiles"));
    }

    public String attribution() {
        return attribution;
    }

    public int maxZoom() {
        return maxZoom;
    }

    public boolean offline() {
        return offline;
    }

    String url(int z, int x, int y) {
        return template.replace("{z}", Integer.toString(z)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
    }

    public Image cached(int z, int x, int y) {
        return memory.get(z + "/" + x + "/" + y);
    }

    public CompletableFuture<Image> load(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        Image m = memory.get(key);
        if (m != null) return CompletableFuture.completedFuture(m);
        return pending.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> fetch(z, x, y), io)
                .whenComplete((img, err) -> {
                    pending.remove(k);
                    if (img != null) memory.put(k, img);
                }));
    }

    private Image fetch(int z, int x, int y) {
        Path file = cacheDir.resolve(Integer.toString(z)).resolve(Integer.toString(x)).resolve(y + ".png");
        try {
            if (Files.isRegularFile(file)
                    && Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(Duration.ofDays(7)))) {
                return image(Files.readAllBytes(file));
            }
        } catch (IOException ignored) {
            // cache illisible : on retélécharge
        }
        try {
            inFlight.acquire();
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url(z, x, y))).timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "NexFleet-CAMTEL/0.3 (gestion de flotte; dsir@camtel.cm)").GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
                offline = false;
                try {
                    Files.createDirectories(file.getParent());
                    Files.write(file, r.body());
                    Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
                } catch (IOException ignored) {
                    // disque plein ou dossier en lecture seule : la tuile reste en mémoire
                }
                return image(r.body());
            } finally {
                inFlight.release();
            }
        } catch (IOException e) {
            offline = true;
            if (Files.isRegularFile(file)) {   // tuile périmée mais disponible : mieux qu'un trou hors ligne
                try {
                    return image(Files.readAllBytes(file));
                } catch (IOException ignored) {
                    // illisible
                }
            }
            throw new java.util.concurrent.CompletionException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    private static Image image(byte[] b) {
        return new Image(new ByteArrayInputStream(b));
    }
}
