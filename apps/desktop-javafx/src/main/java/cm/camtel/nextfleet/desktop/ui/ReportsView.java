package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Prod;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rapports (NF-22, NF-25) : exports CSV produits par le serveur dans le périmètre de l'utilisateur, ouverts directement
 * dans Excel (séparateur « ; », UTF-8). Chaque export est journalisé.
 */
final class ReportsView extends ScrollPane {

    private final AppShell shell;
    private final VBox col;
    private final DatePicker from = FormSheet.date(Views.today().withDayOfMonth(1));
    private final DatePicker to = FormSheet.date(Views.today());

    ReportsView(AppShell shell) {
        this.shell = shell;
        col = Views.page(this);
        load();
    }

    private void load() {
        Ui.load("reports", () -> shell.gateway().reports(), this::render, err -> col.getChildren().setAll(header(), Ui.errorBanner(err, this::load)),
                () -> col.getChildren().setAll(header(), Ui.skeleton(-1, 300)));
    }

    private Node header() {
        return Views.header("Rapports", "Exports CSV de votre périmètre, prêts pour Excel. Chaque export est tracé dans le journal d'audit.");
    }

    private void render(List<Prod.ReportInfo> reports) {
        HBox period = new HBox(10, Ui.label("Période", "row-title"), Ui.spacer(), Ui.label("du", "footnote", "muted"), from,
                Ui.label("au", "footnote", "muted"), to);
        period.setAlignment(Pos.CENTER_LEFT);
        period.getStyleClass().add("list-row");
        List<Node> quick = new ArrayList<>();
        LocalDate t = Views.today();
        for (String[] q : new String[][]{{"Ce mois", "M"}, {"Mois dernier", "P"}, {"Trimestre", "Q"}, {"Année", "Y"}}) {
            Button b = new Button(q[0]);
            b.getStyleClass().add("chip");
            b.setOnAction(e -> {
                switch (q[1]) {
                    case "M" -> { from.setValue(t.withDayOfMonth(1)); to.setValue(t); }
                    case "P" -> { from.setValue(t.minusMonths(1).withDayOfMonth(1)); to.setValue(t.withDayOfMonth(1).minusDays(1)); }
                    case "Q" -> { from.setValue(t.withMonth(((t.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1)); to.setValue(t); }
                    default -> { from.setValue(t.withDayOfYear(1)); to.setValue(t); }
                }
            });
            quick.add(b);
        }
        HBox chips = new HBox(8);
        chips.getChildren().addAll(quick);
        List<Node> rows = new ArrayList<>();
        for (Prod.ReportInfo r : reports) {
            Button export = new Button("Exporter", Icons.stroke(Icons.DOWNLOAD, 14, "-nf-accent"));
            export.getStyleClass().add("btn-tinted");
            export.setOnAction(e -> export(r, export));
            Label d = Ui.label(r.description() + (r.period() ? "" : " Instantané à la date du jour."), "footnote", "muted");
            d.setWrapText(true);
            VBox text = new VBox(2, Ui.label(r.title(), "row-title"), d);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMinWidth(0);
            HBox row = new HBox(12, Ui.symbolTile(Icons.REPORT, r.period() ? "tile-indigo" : "tile-navy"), text, export);
            row.getStyleClass().add("list-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.getProperties().put("hasIcon", true);
            rows.add(row);
        }
        col.getChildren().setAll(header(), Ui.insetGroup("Période des rapports datés", List.of(period), null), chips,
                rows.isEmpty() ? Ui.statePanel(Icons.REPORT, "Aucun rapport disponible", "Votre profil ne permet pas l'export de rapports.")
                        : Ui.insetGroup("Rapports disponibles", rows, "Format CSV : séparateur « ; », dates au fuseau de Douala, montants en FCFA."));
        Motion.stagger(col.getChildren());
    }

    private void export(Prod.ReportInfo r, Button b) {
        LocalDate f = from.getValue() == null ? Views.today().withDayOfMonth(1) : from.getValue();
        LocalDate t = to.getValue() == null ? Views.today() : to.getValue();
        if (t.isBefore(f)) {
            shell.toasts().info("Période invalide", "La fin de période précède le début.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Enregistrer le rapport");
        fc.setInitialFileName("nexfleet-" + r.code() + "-" + f + (r.period() ? "_" + t : "") + ".csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File dest = fc.showSaveDialog(getScene().getWindow());
        if (dest == null) return;
        b.setDisable(true);
        b.setText("Export…");
        Ui.async(() -> shell.gateway().exportReport(r.code(), f, t), bytes -> {
            b.setDisable(false);
            b.setText("Exporter");
            try {
                java.nio.file.Files.write(dest.toPath(), bytes);
                shell.toasts().success("Rapport enregistré", dest.getName());
            } catch (java.io.IOException e) {
                shell.toasts().info("Enregistrement impossible", e.getMessage());
            }
        }, err -> {
            b.setDisable(false);
            b.setText("Exporter");
            shell.toasts().info("Export refusé", err.getMessage());
        });
    }
}
