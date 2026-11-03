package cm.camtel.nextfleet.desktop.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Enregistrement d'un export (PDF ou CSV) produit par le serveur : choix du fichier, export en arrière-plan,
 * bouton occupé pendant l'appel, puis notification « Ouvrir » qui lance le fichier dans l'application du poste.
 */
final class Exports {

    private Exports() { }

    /** Exporte un rapport du serveur ({@code format} « pdf » ou « csv »), filtré sur une direction ou un véhicule. */
    static void report(AppShell shell, Node owner, Button busy, String code, LocalDate from, LocalDate to, String direction,
                       UUID vehicleId, String nameHint, String format) {
        String name = "nexfleet-" + code + (nameHint == null || nameHint.isBlank() ? "" : "-" + slug(nameHint)) + "-" + from
                + (from.equals(to) ? "" : "_" + to) + "." + format;
        save(shell, owner, busy, name, format, () -> shell.gateway().exportReport(code, from, to, direction, vehicleId, format));
    }

    static void save(AppShell shell, Node owner, Button busy, String fileName, String format, Supplier<byte[]> producer) {
        boolean pdf = "pdf".equals(format);
        FileChooser fc = new FileChooser();
        fc.setTitle(pdf ? "Enregistrer le PDF" : "Enregistrer le fichier CSV");
        fc.setInitialFileName(fileName);
        fc.getExtensionFilters().add(pdf ? new FileChooser.ExtensionFilter("Document PDF", "*.pdf")
                : new FileChooser.ExtensionFilter("CSV (Excel)", "*.csv"));
        File dest = fc.showSaveDialog(owner.getScene().getWindow());
        if (dest == null) return;
        String label = busy == null ? null : busy.getText();
        if (busy != null) {
            busy.setDisable(true);
            busy.setText("Export…");
        }
        Ui.async(producer::get, bytes -> {
            restore(busy, label);
            try {
                java.nio.file.Files.write(dest.toPath(), bytes);
                shell.toasts().show(Ui.symbolTile(pdf ? Icons.PDF : Icons.DOC, pdf ? "tile-red" : "tile-green"),
                        pdf ? "PDF enregistré" : "Fichier enregistré", dest.getName() + " · cliquez pour l'ouvrir", () -> open(dest));
            } catch (java.io.IOException e) {
                shell.toasts().info("Enregistrement impossible", e.getMessage());
            }
        }, err -> {
            restore(busy, label);
            shell.toasts().info("Export refusé", err.getMessage());
        });
    }

    /** Bouton « Exporter » avec un menu PDF / CSV. */
    static Button menuButton(String text, java.util.function.Consumer<String> onFormat) {
        Button b = Views.tinted(text, Icons.DOWNLOAD, null);
        ContextMenu menu = new ContextMenu();
        MenuItem pdf = new MenuItem("Document PDF (impression, envoi)");
        pdf.setOnAction(e -> onFormat.accept("pdf"));
        MenuItem csv = new MenuItem("Tableur CSV (Excel)");
        csv.setOnAction(e -> onFormat.accept("csv"));
        menu.getItems().addAll(pdf, csv);
        b.setOnAction(e -> menu.show(b, javafx.geometry.Side.BOTTOM, 0, 6));
        return b;
    }

    private static void restore(Button b, String label) {
        if (b == null) return;
        b.setDisable(false);
        b.setText(label);
    }

    private static void open(File f) {
        Thread.ofVirtual().start(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(f);
            } catch (Exception ignored) {
                // pas d'application associée : le fichier reste disponible à l'emplacement choisi
            }
        });
    }

    static String slug(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase(java.util.Locale.ROOT);
    }
}
