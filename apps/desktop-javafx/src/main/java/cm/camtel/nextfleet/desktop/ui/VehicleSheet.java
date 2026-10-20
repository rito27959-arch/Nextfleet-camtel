package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Fiche véhicule en panneau latéral (écran 10, version lot 1 ; maquette p. 18 écran 03).
 * Sans photo ni actif 3D licencié, une ILLUSTRATION GÉNÉRIQUE est affichée et signalée comme telle (3D-02).
 */
final class VehicleSheet extends VBox {

    VehicleSheet(Vehicle v, boolean demo, Runnable onClose) {
        getStyleClass().add("sheet");
        Button close = new Button(null, Icons.stroke(Icons.CLOSE, 16, "-nf-muted"));
        close.getStyleClass().add("icon-button");
        close.setAccessibleText("Fermer la fiche");
        close.setOnAction(e -> onClose.run());
        close.setCancelButton(true);

        Label overline = Ui.label(v.label().toUpperCase(Locale.FRANCE), "overline");
        Label reg = Ui.label(v.registration(), "t-28", "w-700");
        HBox title = new HBox(10, new VBox(2, overline, reg), Ui.spacer(), Ui.statusPill(v.status()), close);
        title.setAlignment(Pos.CENTER_LEFT);

        Label illusNote = Ui.label("Illustration générique — aucune photo ni modèle 3D associé", "muted", "t-12");
        illusNote.setWrapText(true);
        VBox illus = new VBox(6, genericVehicle(), illusNote);
        illus.setAlignment(Pos.CENTER);
        illus.getStyleClass().add("vehicle-illustration");

        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE);
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(14);
        grid.add(tile(Icons.USER, "Conducteur", v.driverName() == null ? "Non affecté" : v.driverName()), 0, 0);
        grid.add(tile(Icons.MAP_PIN, demo ? "Position déclarée (fictive)" : "Position déclarée",
                v.lastKnownPlace() == null ? "Non renseignée" : v.lastKnownPlace()), 1, 0);
        grid.add(tile(Icons.GAUGE, "Kilométrage", v.odometerKm() == null ? "Non renseigné" : Ui.decimal(v.odometerKm(), 0) + " km"), 0, 1);
        grid.add(tile(Icons.FUEL, "Carburant", v.fuelLevelPercent() == null ? "Non renseigné" : v.fuelLevelPercent() + " %"), 1, 1);
        grid.add(tile(Icons.CAR, "Catégorie", v.category() == null ? "Non renseignée" : v.category()), 0, 2);
        grid.add(tile(Icons.WRENCH, "Prochain entretien", v.nextMaintenance() == null ? "Non planifié" : v.nextMaintenance().format(f)), 1, 2);

        getChildren().addAll(title, illus, grid);

        if (v.fuelLevelPercent() != null && v.fuelLevelPercent() > 0 && v.fuelLevelPercent() < 20) {
            VBox t = new VBox(2, Ui.label("Niveau de carburant bas", "t-12", "w-600"),
                    Ui.label("Le niveau déclaré est inférieur à 20 %.", "muted", "t-12"));
            HBox warn = new HBox(12, Ui.bubble(Icons.FUEL, "warning", "-nf-warning"), t);
            warn.setAlignment(Pos.CENTER_LEFT);
            warn.setStyle("-fx-background-color: -nf-warning-soft; -fx-background-radius: 12; -fx-padding: 10;");
            getChildren().add(warn);
        }

        Label tabs = Ui.label("Vue 3D, photos, documents et historique : prévus aux lots 2 et 4 (écrans 08 à 13).", "muted", "t-12");
        tabs.setWrapText(true);
        Button maint = new Button("Voir les entretiens", Icons.stroke(Icons.CALENDAR, 16, "white"));
        maint.getStyleClass().add("btn-primary");
        maint.setMaxWidth(Double.MAX_VALUE);
        maint.setDisable(true);
        Node maintWrapped = DashboardView.wrapTooltip(maint, "Module Entretiens : lot 2 (NF-14).");
        getChildren().addAll(Ui.spacer(), tabs, maintWrapped);
        setAccessibleText("Fiche du véhicule " + v.registration());
    }

    private static Node tile(String icon, String caption, String value) {
        Label c = Ui.label(caption, "caption");
        Label val = Ui.label(value, "value");
        val.setWrapText(true);
        HBox h = new HBox(10, Icons.stroke(icon, 16, "-nf-muted"), new VBox(2, c, val));
        h.getStyleClass().add("info-tile");
        h.setPrefWidth(170);
        return h;
    }

    /** Silhouette générique de pick-up, dessinée pour NexFleet (aucun modèle de constructeur reproduit). */
    private static Node genericVehicle() {
        SVGPath body = new SVGPath();
        body.setContent("M10 70 L10 52 Q10 46 16 45 L58 42 L78 22 Q82 18 90 18 L150 18 Q158 18 162 26 L172 44 "
                + "L280 44 Q288 44 288 52 L288 70 Z M88 26 L150 26 L160 44 L74 44 Z "
                + "M60 70 A16 16 0 1 0 92 70 M210 70 A16 16 0 1 0 242 70");
        body.setStyle("-fx-fill: transparent; -fx-stroke: -nf-muted; -fx-stroke-width: 2; -fx-stroke-line-join: round;");
        SVGPath wheels = new SVGPath();
        wheels.setContent("M62 72 A14 14 0 1 0 90 72 A14 14 0 1 0 62 72 Z M212 72 A14 14 0 1 0 240 72 A14 14 0 1 0 212 72 Z");
        wheels.setStyle("-fx-fill: -nf-surface; -fx-stroke: -nf-muted; -fx-stroke-width: 2;");
        StackPane p = new StackPane(new Group(body, wheels));
        p.setPrefHeight(100);
        return p;
    }
}
