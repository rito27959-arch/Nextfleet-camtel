package cm.camtel.nextfleet.desktop.ui;

import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Ouverture d'une pièce justificative : téléchargement par l'API (droits revérifiés), copie dans un dossier temporaire
 * privé à l'utilisateur, ouverture par l'application système ; à défaut, « Enregistrer sous ».
 */
final class Files {

    private Files() { }

    static void open(AppShell shell, UUID fileId, String baseName) {
        Ui.async(() -> shell.gateway().download(fileId), bytes -> {
            String ext = extension(bytes);
            try {
                Path dir = java.nio.file.Files.createTempDirectory("nexfleet-");
                Path f = dir.resolve(baseName.replaceAll("[^A-Za-z0-9._-]", "_") + ext);
                java.nio.file.Files.write(f, bytes);
                f.toFile().deleteOnExit();
                dir.toFile().deleteOnExit();
                Thread.ofPlatform().daemon().start(() -> {
                    try {
                        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                            java.awt.Desktop.getDesktop().open(f.toFile());
                            return;
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // repli : enregistrer sous
                    }
                    Ui.later(() -> saveAs(shell, bytes, baseName + ext));
                });
            } catch (IOException e) {
                saveAs(shell, bytes, baseName + ext);
            }
        }, err -> shell.toasts().info("Ouverture impossible", err.getMessage()));
    }

    static void saveAs(AppShell shell, byte[] bytes, String suggested) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Enregistrer sous");
        fc.setInitialFileName(suggested);
        java.io.File f = fc.showSaveDialog(shell.getScene() == null ? null : shell.getScene().getWindow());
        if (f == null) return;
        try {
            java.nio.file.Files.write(f.toPath(), bytes);
            shell.toasts().success("Fichier enregistré", f.getName());
        } catch (IOException e) {
            shell.toasts().info("Enregistrement impossible", e.getMessage());
        }
    }

    static String extension(byte[] b) {
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return ".pdf";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return ".jpg";
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P') return ".png";
        if (b.length >= 12 && b[8] == 'W' && b[9] == 'E') return ".webp";
        return ".bin";
    }
}
