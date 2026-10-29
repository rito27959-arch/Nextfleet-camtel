package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

/** Table des véhicules partagée par le tableau de bord et la liste (colonnes de la maquette p. 17). */
final class VehicleTable {

    private VehicleTable() { }

    static TableView<Vehicle> create(boolean compact) {
        return create(compact, v -> null);
    }

    /** health : score de santé du véhicule (null tant qu'il n'est pas connu : l'anneau est alors remplacé par un repère neutre). */
    static TableView<Vehicle> create(boolean compact, Function<Vehicle, Health.Score> health) {
        TableView<Vehicle> t = new TableView<>();
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        t.setPlaceholder(Ui.label("Aucun véhicule à afficher.", "muted"));
        t.getColumns().add(column("Véhicule", 1.4, v -> vehicleCell(v, health.apply(v))));
        t.getColumns().add(column("Conducteur", 1.0, v -> v.driverName() == null
                ? Ui.label("Non affecté", "muted", "t-12") : Ui.label(v.driverName(), "t-12")));
        t.getColumns().add(column("Statut", 1.0, v -> Ui.statusPill(v.status())));
        t.getColumns().add(column("Carburant", 1.2, v -> Ui.fuelBar(v.fuelLevelPercent())));
        t.getColumns().add(column("Affectation", 1.3, v -> {
            if (v.lastKnownPlace() == null) return Ui.label("Non renseignée", "muted", "t-12");
            Label l = new Label(v.lastKnownPlace(), Icons.stroke(Icons.MAP_PIN, 14, "-nf-muted"));
            l.getStyleClass().add("t-12");
            l.setGraphicTextGap(8);
            return l;
        }));
        if (!compact) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);
            t.getColumns().add(column("Prochain entretien", 1.0, v -> v.nextMaintenance() == null
                    ? Ui.label("Non planifié", "muted", "t-12") : Ui.label(v.nextMaintenance().format(f), "t-12")));
        }
        t.getColumns().forEach(c -> c.setSortable(false));
        return t;
    }

    /** Plaque en gras, modèle dessous, et l'anneau de santé à gauche (information utile plutôt qu'un pictogramme répété). */
    private static Node vehicleCell(Vehicle v, Health.Score score) {
        Node lead;
        if (score != null) {
            Node[] ring = new Node[1];
            Motion.quietly(() -> ring[0] = Health.ring(score, 13, 3.5, 10));   // dans une liste : anneau figé, pas d'animation au défilement
            lead = ring[0];
            javafx.scene.control.Tooltip.install(lead, new javafx.scene.control.Tooltip("Santé " + score.value() + " / 100 — " + score.label()
                    + (score.reasons().isEmpty() ? "" : "\n" + String.join("\n", score.reasons()))));
        } else {
            StackPane icon = new StackPane(Icons.stroke(Icons.CAR, 16, "-nf-muted"));
            icon.setStyle("-fx-background-color: -nf-select; -fx-background-radius: 999; -fx-min-width: 30; -fx-min-height: 30; -fx-max-width: 30; -fx-max-height: 30;");
            lead = icon;
        }
        Label reg = Ui.label(v.registration(), "row-title");
        reg.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox text = new VBox(1, reg, Ui.label(v.label(), "muted", "footnote"));
        HBox box = new HBox(12, lead, text);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static TableColumn<Vehicle, Vehicle> column(String title, double weight, Function<Vehicle, Node> render) {
        TableColumn<Vehicle, Vehicle> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Vehicle v, boolean empty) {
                super.updateItem(v, empty);
                setText(null);
                setGraphic(empty || v == null ? null : render.apply(v));
            }
        });
        c.setPrefWidth(weight * 140);
        c.setMinWidth(weight >= 1.4 ? 180 : weight >= 1.2 ? 120 : 96);
        c.setReorderable(false);
        return c;
    }
}
