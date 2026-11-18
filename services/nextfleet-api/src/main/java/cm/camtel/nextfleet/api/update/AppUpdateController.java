package cm.camtel.nextfleet.api.update;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Mise à jour des applications (public, sans connexion) : la version attendue est celle du serveur, et les liens
 * pointent vers les paquets publiés de cette même version. Les applications bureau et Android se comparent à elle au
 * démarrage et s'installent d'elles-mêmes : mettre le serveur à jour suffit à mettre à jour tout le parc de postes.
 * <p>
 * {@code NF_UPDATE_BASE_URL} (« {version} » remplacé) : où sont les paquets (par défaut, les publications GitHub).
 * {@code NF_UPDATE_ANDROID_APK} : nom de l'APK (ex. « nexfleet-{version}-release.apk ») ; vide = pas de mise à jour
 * Android automatique (un APK n'en remplace un autre que s'il est signé avec la même clé).
 */
@RestController
public class AppUpdateController {

    public record UpdateInfo(String version, String windowsMsi, String linuxDeb, String androidApk, String notesUrl) { }

    private final String version;
    private final String base;
    private final String apk;

    public AppUpdateController(ObjectProvider<BuildProperties> build,
                               @Value("${nextfleet.update.base-url:https://github.com/rito27959-arch/Nextfleet-camtel/releases/download/v{version}/}") String base,
                               @Value("${nextfleet.update.android-apk:}") String apk,
                               @Value("${nextfleet.update.version:}") String forced) {
        BuildProperties b = build.getIfAvailable();
        this.version = forced != null && !forced.isBlank() ? forced.strip() : b != null ? b.getVersion() : null;
        this.base = base.endsWith("/") ? base : base + "/";
        this.apk = apk == null ? "" : apk.strip();
    }

    @GetMapping("/api/v1/app/update")
    public ResponseEntity<UpdateInfo> update() {
        if (version == null || version.isBlank() || version.contains("SNAPSHOT")) return ResponseEntity.noContent().build();
        String root = base.replace("{version}", version);
        UpdateInfo info = new UpdateInfo(version, root + "NexFleet-" + version + ".msi", root + "nexfleet_" + version + "_amd64.deb",
                apk.isEmpty() ? null : root + apk.replace("{version}", version),
                "https://github.com/rito27959-arch/Nextfleet-camtel/releases/tag/v" + version);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(info);
    }
}
