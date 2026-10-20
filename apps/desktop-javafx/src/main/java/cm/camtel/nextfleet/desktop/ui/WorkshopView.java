package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.DueItem;
import cm.camtel.nextfleet.desktop.data.Models.DueKind;
import cm.camtel.nextfleet.desktop.data.Models.DueLevel;
import cm.camtel.nextfleet.desktop.data.Models.Page;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Espace maintenance « Atelier » : véhicules à l'atelier, entretiens sous 30 jours, contrôles techniques à échéance.
 * La saisie des interventions (NF-13 à NF-16) arrive au lot 3 : le bouton est visible, inactif et l'indique.
 */
final class WorkshopView extends ScrollPane {

    private final AppShell shell;
    private final VBox col = new VBox(22);

    WorkshopView(AppShell shell) {
        this.shell = shell;
        setFitToWidth(true);
        col.getStyleClass().add("content");
        col.setMaxWidth(1100);
        StackPane center = new StackPane(col);
        center.setAlignment(Pos.TOP_CENTER);
        setContent(center);
        col.getChildren().setAll(header(), Ui.skeleton(-1, 110), Ui.skeleton(-1, 240));
        Ui.async(() -> new Object[]{shell.gateway().vehicles(null, null, 0, 200), shell.gateway().dueDates(30)}, r -> {
            @SuppressWarnings("unchecked") Page<Vehicle> page = (Page<Vehicle>) r[0];
            @SuppressWarnings("unchecked") List<DueItem> dues = (List<DueItem>) r[1];
            render(page.items(), dues);
        }, err -> col.getChildren().set(1, Ui.errorBanner(err, () -> shell.show(AppShell.Section.WORKSHOP))));
    }

    private Node header() {
        Button add = new Button("Saisir une intervention", Icons.stroke(Icons.PLUS, 15, "white"));
        add.getStyleClass().add("btn-filled");
        add.setDisable(true);
        StackPane wrap = new StackPane(add);
        Tooltip.install(wrap, new Tooltip("Saisie des interventions, contrôles 12 points, vidanges et pneus : lot 3 (tables déjà prêtes en base)."));
        Label sub = Ui.label("Entretiens, contrôles techniques et échanges avec les gestionnaires.", "body", "muted");
        HBox h = new HBox(new VBox(6, Ui.label("Atelier", "large-title"), sub), Ui.spacer(), wrap);
        h.setAlignment(Pos.TOP_LEFT);
        return h;
    }

    private void render(List<Vehicle> vehicles, List<DueItem> dues) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Douala"));
        List<Vehicle> atShop = vehicles.stream().filter(v -> v.status() == VehicleStatus.MAINTENANCE || v.status() == VehicleStatus.OUT_OF_SERVICE).toList();
        List<Vehicle> soon = vehicles.stream().filter(v -> v.nextMaintenance() != null && !v.nextMaintenance().isAfter(today.plusDays(30)))
                .sorted(java.util.Comparator.comparing(Vehicle::nextMaintenance)).toList();
        List<DueItem> ct = dues.stream().filter(d -> d.kind() == DueKind.CONTROLE_TECHNIQUE && d.level() != DueLevel.A_JOUR).toList();

        HBox tiles = new HBox(12, tile(String.valueOf(atShop.size()), "À l'atelier", "summary-expire"),
                tile(String.valueOf(soon.size()), "Entretiens sous 30 jours", "summary-soon"),
                tile(String.valueOf(ct.size()), "Contrôles techniques à prévoir", "summary-ok"));
        tiles.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        List<Node> shopRows = new ArrayList<>();
        for (Vehicle v : atShop) {
            shopRows.add(Ui.listRow(Ui.symbolTile(Icons.WRENCH, v.status() == VehicleStatus.MAINTENANCE ? "tile-orange" : "tile-gray"),
                    v.registration() + " · " + v.label(), v.status().label, null, () -> shell.openVehicles(v.registration())));
        }
        List<Node> soonRows = new ArrayList<>();
        for (Vehicle v : soon) {
            long d = ChronoUnit.DAYS.between(today, v.nextMaintenance());
            soonRows.add(Ui.listRow(Ui.symbolTile(Icons.CALENDAR, d <= 7 ? "tile-red" : "tile-orange"), v.registration() + " · " + v.label(),
                    d <= 0 ? "Aujourd'hui" : "Dans " + d + " j", null, () -> shell.openVehicles(v.registration())));
        }
        List<Node> ctRows = new ArrayList<>();
        for (DueItem d : ct) {
            HBox row = Ui.listRow(Ui.symbolTile(Icons.SHIELD, "tile-indigo"), d.subject() + " · " + d.subjectDetail(), null, Ui.dueChip(d), null);
            ctRows.add(row);
        }
        List<Node> nodes = new ArrayList<>(List.of(header(), tiles));
        if (!shopRows.isEmpty()) nodes.add(Ui.insetGroup("Véhicules à l'atelier", shopRows, null));
        if (!soonRows.isEmpty()) nodes.add(Ui.insetGroup("Entretiens à prévoir", soonRows, "Date d'entretien déclarée sur la fiche véhicule."));
        if (!ctRows.isEmpty()) nodes.add(Ui.insetGroup("Contrôles techniques", ctRows, null));
        col.getChildren().setAll(nodes);
        Motion.stagger(col.getChildren());
    }

    private static Node tile(String value, String caption, String cls) {
        VBox t = new VBox(2, Ui.label(value, "summary-value"), Ui.label(caption, "category-label"));
        t.getStyleClass().addAll("summary-tile", cls);
        t.setMaxWidth(Double.MAX_VALUE);
        return t;
    }
}
