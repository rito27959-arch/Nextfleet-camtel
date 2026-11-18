package cm.camtel.nextfleet.desktop;

import cm.camtel.nextfleet.desktop.data.Updater;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    @Test
    void comparaison_des_versions() {
        assertTrue(Updater.newer("0.11.0", "0.10.0"));
        assertTrue(Updater.newer("0.10.1", "0.10.0"));
        assertTrue(Updater.newer("1.0.0", "0.99.9"));
        assertTrue(Updater.newer("0.10.0", "0.9.0"), "comparaison numérique, pas alphabétique");
        assertFalse(Updater.newer("0.10.0", "0.10.0"));
        assertFalse(Updater.newer("0.9.0", "0.10.0"), "jamais de retour en arrière");
        assertFalse(Updater.newer("abc", "0.10.0"));
        assertFalse(Updater.newer(null, "0.10.0"));
        assertFalse(Updater.newer("0.11.0-SNAPSHOT", "0.11.0"));
    }

    @Test
    void mode_d_installation_selon_le_poste() {
        assertEquals(Updater.Kind.WINDOWS_MSI, Updater.kind("Windows 11", "C:\\Users\\a\\AppData\\Local\\NexFleet\\NexFleet.exe",
                "C:\\Users\\a\\AppData\\Local"));
        assertEquals(Updater.Kind.MANUAL, Updater.kind("Windows 11", "D:\\Outils\\NexFleet\\NexFleet.exe",
                "C:\\Users\\a\\AppData\\Local"), "version portable : pas d'installation MSI");
        assertEquals(Updater.Kind.LINUX_DEB, Updater.kind("Linux", "/opt/nexfleet/bin/NexFleet", null));
        assertEquals(Updater.Kind.MANUAL, Updater.kind("Linux", null, null), "lancement de développement");
        assertEquals(Updater.Kind.MANUAL, Updater.kind("Mac OS X", "/Applications/NexFleet.app", null));
    }

    @Test
    void version_de_l_application() {
        assertTrue(AppVersion.CURRENT.matches("\\d+\\.\\d+\\.\\d+"));
    }
}
