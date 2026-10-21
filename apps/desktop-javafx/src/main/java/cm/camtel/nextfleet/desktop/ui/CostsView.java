package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Prod;
import cm.camtel.nextfleet.domain.security.Permission;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coûts (NF-21) : dépenses réelles de l'exercice (carburant, interventions clôturées, vidanges hors intervention — sans
 * double comptage) comparées au budget importé, par mois, par catégorie et par véhicule.
 */
final class CostsView extends ScrollPane {

    private final AppShell shell;
    private final VBox col;
    private int year = Views.today().getYear();

    CostsView(AppShell shell) {
        this.shell = shell;
        col = Views.page(this);
        load();
    }

    private void load() {
        col.getChildren().setAll(header(), Ui.skeleton(-1, 96), Ui.skeleton(-1, 300));
        int y = year;
        Ui.async(() -> shell.gateway().costs(y), this::render, err -> col.getChildren().set(1, Ui.errorBanner(err, this::load)));
    }

    private Node header() {
        int now = Views.today().getYear();
        Controls.Segmented years = new Controls.Segmented(List.of(String.valueOf(now - 2), String.valueOf(now - 1), String.valueOf(now)),
                2 - (now - year), i -> {
            year = now - 2 + i;
            load();
        });
        List<Node> actions = new ArrayList<>();
        actions.add(years);
        if (shell.session().can(Permission.VEHICLE_WRITE)) actions.add(Views.tinted("Importer un budget", Icons.DOWNLOAD, this::importSheet));
        return Views.header("Coûts", "Dépenses réelles de l'exercice comparées au budget. Montants en francs CFA.", actions.toArray(Node[]::new));
    }

    private void render(Prod.CostSummary c) {
        BigDecimal workshop = c.maintenanceXaf().add(c.oilXaf());
        String consumed = c.budgetXaf().signum() > 0
                ? c.totalXaf().multiply(BigDecimal.valueOf(100)).divide(c.budgetXaf(), 0, RoundingMode.HALF_UP) + " %" : "—";
        Node tiles = Views.tiles(Views.tile(Ui.compactXaf(c.totalXaf()), "Dépense totale " + c.year(), "summary-ok"),
                Views.tile(Ui.compactXaf(c.fuelXaf()), "Carburant · " + Views.liters(c.liters()), "summary-soon"),
                Views.tile(Ui.compactXaf(workshop), "Atelier et vidanges", "summary-expire"),
                Views.tile(consumed, c.budgetXaf().signum() > 0 ? "du budget (" + Ui.compactXaf(c.budgetXaf()) + ")" : "Aucun budget importé", "summary-ok"));
        List<Node> nodes = new ArrayList<>(List.of(header(), tiles));
        if (c.totalXaf().signum() == 0 && c.budgetXaf().signum() == 0) {
            nodes.add(Ui.statePanel(Icons.CHART, "Aucune dépense en " + c.year(),
                    "Les pleins, les interventions clôturées et les vidanges de l'exercice apparaîtront ici automatiquement."));
        } else {
            nodes.add(chart(c));
            nodes.add(categories(c));
            if (!c.vehicles().isEmpty()) nodes.add(vehicles(c));
        }
        col.getChildren().setAll(nodes);
        Motion.stagger(col.getChildren());
    }

    private Node chart(Prod.CostSummary c) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Number n) {
                return Ui.compactXaf(BigDecimal.valueOf(n.doubleValue()));
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        StackedBarChart<String, Number> chart = new StackedBarChart<>(x, y);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setCategoryGap(14);
        chart.setPrefHeight(300);
        XYChart.Series<String, Number> fuel = new XYChart.Series<>();
        fuel.setName("Carburant");
        XYChart.Series<String, Number> work = new XYChart.Series<>();
        work.setName("Interventions");
        XYChart.Series<String, Number> oil = new XYChart.Series<>();
        oil.setName("Vidanges");
        for (Prod.CostMonth m : c.months()) {
            String label = Month.of(m.month()).getDisplayName(TextStyle.SHORT, Locale.FRANCE);
            fuel.getData().add(new XYChart.Data<>(label, m.fuelXaf()));
            work.getData().add(new XYChart.Data<>(label, m.maintenanceXaf()));
            oil.getData().add(new XYChart.Data<>(label, m.oilXaf()));
        }
        chart.getData().addAll(List.of(fuel, work, oil));
        VBox card = new VBox(8, Ui.label("Dépenses par mois", "headline"), chart);
        card.getStyleClass().add("card");
        return card;
    }

    private Node categories(Prod.CostSummary c) {
        List<Node> rows = new ArrayList<>();
        for (Prod.CostCategory cat : c.categories()) {
            Label name = Ui.label(cat.label(), "row-title");
            name.setMinWidth(170);
            String amounts = Views.xaf(cat.actualXaf()) + (cat.budgetXaf().signum() > 0 ? " sur " + Views.xaf(cat.budgetXaf()) : "");
            Label value = Ui.label(amounts, "row-value");
            HBox row = new HBox(12, name);
            if (cat.budgetXaf().signum() > 0) {
                double ratio = cat.actualXaf().doubleValue() / cat.budgetXaf().doubleValue();
                ProgressBar bar = new ProgressBar(Math.min(ratio, 1));
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.getStyleClass().add(ratio > 1 ? "progress-danger" : ratio > 0.85 ? "progress-warning" : "progress-ok");
                bar.setAccessibleText(cat.label() + " : " + Math.round(ratio * 100) + " % du budget consommé");
                HBox.setHgrow(bar, Priority.ALWAYS);
                row.getChildren().addAll(bar, Ui.label(Math.round(ratio * 100) + " %", "footnote", ratio > 1 ? "error-text" : "muted"));
            } else {
                row.getChildren().add(Ui.spacer());
            }
            row.getChildren().add(value);
            row.getStyleClass().add("list-row");
            row.setAlignment(Pos.CENTER_LEFT);
            rows.add(row);
        }
        return Ui.insetGroup("Par catégorie", rows, "Budget importé par catégorie d'intervention. Le carburant n'est pas budgété par catégorie.");
    }

    private Node vehicles(Prod.CostSummary c) {
        List<Node> rows = new ArrayList<>();
        for (Prod.CostVehicle v : c.vehicles().stream().limit(20).toList()) {
            String sub = "Carburant " + Views.xaf(v.fuelXaf()) + " (" + Views.liters(v.liters()) + ") · atelier " + Views.xaf(v.maintenanceXaf())
                    + (v.interventions() > 0 ? " · " + v.interventions() + " intervention" + (v.interventions() > 1 ? "s" : "") : "");
            Label total = Ui.label(Views.xaf(v.totalXaf()), "row-value");
            total.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            rows.add(Views.row(Ui.symbolTile(Icons.CAR, "tile-navy"), v.registration() + " · " + v.label(), sub, total,
                    () -> shell.openVehicles(v.registration())));
        }
        return Ui.insetGroup("Véhicules les plus coûteux", rows, c.vehicles().size() > 20 ? "20 premiers sur " + c.vehicles().size()
                + " ; le rapport « Coûts par véhicule » donne la liste complète." : null);
    }

    private void importSheet() {
        Ui.async(() -> shell.gateway().orgUnits("vehicle:write"), units -> {
            if (units.isEmpty()) {
                shell.toasts().info("Import impossible", "Vous ne gérez aucune entité.");
                return;
            }
            ComboBox<Prod.OrgUnit> org = FormSheet.combo(units);
            ComboBox<Integer> fiscal = FormSheet.combo(List.of(year, year + 1, year - 1));
            Label chosen = Ui.label("Aucun fichier", "footnote", "muted");
            java.nio.file.Path[] path = new java.nio.file.Path[1];
            javafx.scene.control.Button pick = Views.tinted("Choisir le fichier CSV", Icons.DOC, () -> {
                FileChooser fc = new FileChooser();
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV (séparateur ;)", "*.csv", "*.txt"));
                java.io.File f = fc.showOpenDialog(getScene().getWindow());
                if (f != null) {
                    path[0] = f.toPath();
                    chosen.setText(f.getName());
                }
            });
            FormSheet f = new FormSheet("Importer un budget", "Importer", shell::closeSheet)
                    .intro("Fichier CSV, séparateur « ; », une ligne d'en-tête puis : categorie;activite;tache;t1;t2;t3;t4. "
                            + "Catégories : MOTEUR, FREINAGE, POMPES, ADMINISTRATIF, VIDANGE, PNEUMATIQUE, TOLERIE, BATTERIE, ELECTRICITE, AUTRE. "
                            + "Réimporter le même fichier n'ajoute aucune ligne.")
                    .field("Entité *", org).field("Exercice *", fiscal).node(new HBox(10, pick, chosen));
            f.onSubmit(() -> {
                if (path[0] == null) throw new IllegalArgumentException("Choisissez le fichier CSV.");
                String csv;
                try {
                    csv = java.nio.file.Files.readString(path[0], java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.nio.charset.MalformedInputException e) {
                    try {
                        csv = java.nio.file.Files.readString(path[0], java.nio.charset.Charset.forName("windows-1252"));
                    } catch (java.io.IOException ex) {
                        throw new IllegalArgumentException("Fichier illisible.");
                    }
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException("Fichier illisible.");
                }
                if (csv.startsWith("﻿")) csv = csv.substring(1);
                return shell.gateway().importBudget(FormSheet.pick(fiscal, "Exercice"), FormSheet.pick(org, "Entité").id(),
                        path[0].getFileName().toString(), csv);
            }, (Prod.ImportResult r) -> {
                shell.closeSheet();
                String msg = r.inserted() + " ligne" + (r.inserted() > 1 ? "s" : "") + " importée" + (r.inserted() > 1 ? "s" : "")
                        + (r.duplicates() > 0 ? ", " + r.duplicates() + " déjà présente" + (r.duplicates() > 1 ? "s" : "") : "")
                        + (r.errors().isEmpty() ? "." : ", " + r.errors().size() + " refusée" + (r.errors().size() > 1 ? "s" : "") + ".");
                AlertDialog.show(shell, Ui.symbolTile(Icons.CHART, r.errors().isEmpty() ? "tile-green" : "tile-orange"), "Import du budget",
                        msg + (r.errors().isEmpty() ? "" : "\n\n" + String.join("\n", r.errors().stream().limit(8).toList())),
                        List.of(AlertDialog.Action.preferred("OK", this::load)));
            });
            shell.openSheet(f, 480);
        }, err -> shell.toasts().info("Import impossible", err.getMessage()));
    }
}
