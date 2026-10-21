package cm.camtel.nextfleet.desktop.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Adresse du serveur NexFleet : choix de l'utilisateur (~/.nexfleet/serveur.properties), sinon adresse de production
 * livrée avec l'application (server.properties). Le jeton de session, lui, n'est jamais écrit sur disque.
 */
public final class ServerConfig {

    private ServerConfig() { }

    static Path file() {
        return Path.of(System.getProperty("user.home"), ".nexfleet", "serveur.properties");
    }

    /** Adresse livrée avec l'application (vide si aucune). */
    public static String bundled() {
        try (InputStream in = ServerConfig.class.getResourceAsStream("/cm/camtel/nextfleet/desktop/server.properties")) {
            if (in == null) return "";
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("url", "").strip();
        } catch (IOException e) {
            return "";
        }
    }

    /** Adresse retenue : choix enregistré, sinon adresse livrée ; chaîne vide si aucune. */
    public static String load() {
        Path f = file();
        if (Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("url", "").strip();
                if (!v.isEmpty()) return v;
            } catch (IOException ignored) {
                // fichier illisible : adresse livrée
            }
        }
        return bundled();
    }

    public static void save(String url) {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Properties p = new Properties();
            p.setProperty("url", url);
            try (OutputStream out = Files.newOutputStream(f)) {
                p.store(out, "NexFleet - serveur choisi");
            }
        } catch (IOException e) {
            throw new FleetGateway.GatewayException(FleetGateway.GatewayException.Kind.SERVER,
                    "Impossible d'enregistrer l'adresse du serveur : " + e.getMessage());
        }
    }

    /** Adresse factice utilisée tant qu'aucun serveur n'est configuré (aucune requête n'y part : l'écran demande l'adresse). */
    public static final String UNSET = "https://serveur-a-configurer.invalid/";

    public static boolean isUnset(java.net.URI base) {
        return base == null || "serveur-a-configurer.invalid".equals(base.getHost());
    }
}
