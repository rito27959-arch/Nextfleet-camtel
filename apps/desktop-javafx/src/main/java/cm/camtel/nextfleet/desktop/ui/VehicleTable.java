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
        TableView<Vehicle> t = new TableView<>();
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        t.setPlaceholder(Ui.label("Aucun véhicule à afficher.", "muted"));
        t.getColumns().add(column("Véhicule", 1.4, VehicleTable::vehicleCell));
        t.getColumns().add(column("Conducteur", 1.0, v -> v.driverName() == null
                ? Ui.label("Non affecté", "muted", "t-12") : Ui.label(v.driverName(), "t-12")));
        t.getColumns().add(column("Statut", 1.0, v -> Ui.statusPill(v.status())));
        t.getColumns().add(column("Carburant", 1.2, v -> Ui.fuelBar(v.fuelLevelPercent())));
        t.getColumns().add(column("Dernière position (déclarée)", 1.3, v -> {
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

    private static Node vehicleCell(Vehicle v) {
        StackPane icon = new StackPane(Icons.stroke(Icons.CAR, 18, "-nf-muted"));
        icon.setStyle("-fx-background-color: -nf-surface-2; -fx-background-radius: 10; -fx-min-width: 40; -fx-min-height: 30;");
        Label reg = Ui.label(v.registration(), "t-12", "w-700");
        reg.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox text = new VBox(0, reg, Ui.label(v.label(), "muted", "t-12"));
        HBox box = new HBox(10, icon, text);
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
