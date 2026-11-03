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
    private final javafx.scene.control.ComboBox<String> directionBox = new javafx.scene.control.ComboBox<>();

    ReportsView(AppShell shell) {
        this.shell = shell;
        col = Views.page(this);
        load();
    }

    private void load() {
        Ui.async(() -> shell.gateway().directions(), l -> {
            String keep = directionBox.getValue();
            directionBox.getItems().setAll(l);
            directionBox.getItems().add(0, "Toutes les directions");
            directionBox.setValue(keep == null || !directionBox.getItems().contains(keep) ? "Toutes les directions" : keep);
        }, err -> { });
        Ui.load("reports", () -> shell.gateway().reports(), this::render, err -> col.getChildren().setAll(header(), Ui.errorBanner(err, this::load)),
                () -> col.getChildren().setAll(header(), Ui.skeleton(-1, 300)));
    }

    private Node header() {
        return Views.header("Rapports", "Documents PDF à imprimer ou envoyer, ou fichiers CSV pour Excel, par direction. Chaque export est tracé dans le journal d'audit.");
    }

    private void render(List<Prod.ReportInfo> reports) {
        HBox period = new HBox(10, Ui.label("Période", "row-title"), Ui.spacer(), Ui.label("du", "footnote", "muted"), from,
                Ui.label("au", "footnote", "muted"), to);
        period.setAlignment(Pos.CENTER_LEFT);
        period.getStyleClass().add("list-row");
        directionBox.setMinWidth(210);
        directionBox.setAccessibleText("Direction à exporter");
        if (directionBox.getValue() == null) directionBox.setValue("Toutes les directions");
        HBox dirRow = new HBox(10, Ui.label("Direction", "row-title"), Ui.spacer(), directionBox);
        dirRow.setAlignment(Pos.CENTER_LEFT);
        dirRow.getStyleClass().add("list-row");
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
            Button[] self = new Button[1];
            self[0] = Exports.menuButton("Exporter", format -> export(r, self[0], format));
            Button export = self[0];
            Label d = Ui.label(r.description() + (r.period() ? "" : " Instantané à la date du jour.")
                    + (r.byDirection() ? "" : " Non filtrable par direction."), "footnote", "muted");
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
        col.getChildren().setAll(header(), Ui.insetGroup("Période et direction", List.of(period, dirRow), "La période ne s'applique qu'aux rapports datés."), chips,
                rows.isEmpty() ? Ui.statePanel(Icons.REPORT, "Aucun rapport disponible", "Votre profil ne permet pas l'export de rapports.")
                        : Ui.insetGroup("Rapports disponibles", rows, "PDF : A4 paysage avec totaux des montants et des litres. CSV : séparateur « ; », UTF-8, ouverture directe dans Excel. Dates au fuseau de Douala, montants en FCFA."));
        Motion.stagger(col.getChildren());
    }

    private void export(Prod.ReportInfo r, Button b, String format) {
        LocalDate f = from.getValue() == null ? Views.today().withDayOfMonth(1) : from.getValue();
        LocalDate t = to.getValue() == null ? Views.today() : to.getValue();
        if (t.isBefore(f)) {
            shell.toasts().info("Période invalide", "La fin de période précède le début.");
            return;
        }
        String v = directionBox.getValue();
        String dir = v == null || v.startsWith("Toutes") || !r.byDirection() ? null : v;
        Exports.report(shell, this, b, r.code(), f, t, dir, null, dir, format);
    }
}
