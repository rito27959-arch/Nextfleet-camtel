package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.DueItem;
import cm.camtel.nextfleet.desktop.data.Models.DueLevel;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Échéances (NF-11) — reprise et unification des alertes de l'ancien NexFleet (assurance, vignette, contrôle technique,
 * permis, CNI). Une même échéance a la même date et le même niveau ici, sur le tableau de bord et dans la fiche.
 */
public final class DueDatesView extends ScrollPane {

    private static final int THRESHOLD_DAYS = 30;   // seuil de l'ancien produit, à approuver (NF-11)
    private final FleetGateway gateway;
    private final AppShell shell;
    private final VBox root = new VBox(20);
    private List<DueItem> all = List.of();
    private Predicate<DueItem> filter = d -> true;

    public DueDatesView(FleetGateway gateway, AppShell shell) {
        this.gateway = gateway;
        this.shell = shell;
        setFitToWidth(true);
        root.getStyleClass().add("content");
        setContent(root);
        load();
    }

    private Node header() {
        VBox titles = new VBox(4, Ui.label("Échéances", "large-title"),
                Ui.label("Documents des véhicules et des conducteurs. Seuil d'alerte : " + THRESHOLD_DAYS + " jours.", "body", "muted"));
        return new HBox(titles);
    }

    private void load() {
        HBox sk = new HBox(16);
        for (int i = 0; i < 3; i++) {
            VBox t = new VBox(10, Ui.skeleton(80, 14), Ui.skeleton(60, 34));
            t.getStyleClass().add("card");
            HBox.setHgrow(t, Priority.ALWAYS);
            t.setMaxWidth(Double.MAX_VALUE);
            sk.getChildren().add(t);
        }
        root.getChildren().setAll(header(), sk);
        Ui.async(() -> gateway.dueDates(THRESHOLD_DAYS), this::render, this::fail);
    }

    private void fail(GatewayException e) {
        root.getChildren().setAll(header(), Ui.errorBanner(e, this::load));
    }

    private void render(List<DueItem> items) {
        all = items;
        long expired = items.stream().filter(d -> d.level() == DueLevel.EXPIRE).count();
        long soon = items.stream().filter(d -> d.level() == DueLevel.A_ECHEANCE).count();
        long ok = items.stream().filter(d -> d.level() == DueLevel.A_JOUR).count();
        HBox tiles = new HBox(16, tile("Expirées", expired, "summary-expire", Icons.CLOSE, "tile-red"),
                tile("Sous " + THRESHOLD_DAYS + " jours", soon, "summary-soon", Icons.CALENDAR, "tile-orange"),
                tile("À jour", ok, "summary-ok", Icons.CHECK, "tile-green"));

        ToggleGroup g = new ToggleGroup();
        HBox chips = new HBox(8);
        VBox list = new VBox();
        addChip(chips, g, "Tout", d -> true, list).setSelected(true);
        addChip(chips, g, "À traiter", d -> d.level() != DueLevel.A_JOUR, list);
        addChip(chips, g, "Véhicules", DueItem::vehicle, list);
        addChip(chips, g, "Conducteurs", d -> !d.vehicle(), list);
        VBox card = new VBox(8, chips, list);
        card.getStyleClass().add("card");
        fill(list);
        root.getChildren().setAll(header(), tiles, card);
        Motion.stagger(root.getChildren());
    }

    private ToggleButton addChip(HBox box, ToggleGroup g, String label, Predicate<DueItem> p, VBox list) {
        ToggleButton b = new ToggleButton(label);
        b.getStyleClass().add("chip");
        b.setToggleGroup(g);
        b.setOnAction(e -> {
            if (!b.isSelected()) b.setSelected(true);
            filter = p;
            fill(list);
        });
        box.getChildren().add(b);
        return b;
    }

    private void fill(VBox list) {
        list.getChildren().clear();
        String[][] groups = {{"EXPIRE", "EXPIRÉES"}, {"A_ECHEANCE", "SOUS " + THRESHOLD_DAYS + " JOURS"}, {"A_JOUR", "À JOUR"}};
        List<Node> rows = new ArrayList<>();
        for (String[] grp : groups) {
            List<DueItem> items = all.stream().filter(filter).filter(d -> d.level().name().equals(grp[0])).toList();
            if (items.isEmpty()) continue;
            list.getChildren().add(Ui.label(grp[1] + " · " + items.size(), "group-title"));
            for (DueItem d : items.subList(0, Math.min(items.size(), grp[0].equals("A_JOUR") ? 8 : 50))) {
                Node r = row(d);
                rows.add(r);
                list.getChildren().add(r);
            }
            if (grp[0].equals("A_JOUR") && items.size() > 8) {
                list.getChildren().add(Ui.label("… et " + (items.size() - 8) + " autres échéances à jour.", "muted", "t-12"));
            }
        }
        if (list.getChildren().isEmpty()) list.getChildren().add(Ui.label("Aucune échéance pour ce filtre.", "muted"));
        Motion.stagger(rows);
    }

    private Node row(DueItem d) {
        DateTimeFormatter day = DateTimeFormatter.ofPattern("dd", Locale.FRANCE);
        DateTimeFormatter mon = DateTimeFormatter.ofPattern("MMM yy", Locale.FRANCE);
        VBox date = new VBox(0, Ui.label(d.dueOn().format(day), "day"),
                Ui.label(d.dueOn().format(mon).toUpperCase(Locale.FRANCE), "month"));
        date.getStyleClass().add("date-block");
        String icon = d.vehicle() ? Icons.CAR : Icons.USER;
        VBox text = new VBox(2, Ui.label(d.kind().label, "callout", "w-600"),
                new Label(d.subject() + " · " + d.subjectDetail(), Icons.stroke(icon, 14, "-nf-muted")));
        ((Label) text.getChildren().get(1)).getStyleClass().addAll("muted", "t-12");
        HBox row = new HBox(14, date, text, Ui.spacer(), Ui.dueChip(d));
        row.getStyleClass().add("timeline-row");
        row.setAccessibleText(d.kind().label + ", " + d.subject() + ", échéance le " + d.dueOn());
        if (d.vehicle()) {
            row.setOnMouseClicked(e -> shell.openVehicles(d.subject()));
            row.setStyle("-fx-cursor: hand;");
        }
        return row;
    }

    /** Tuile de synthèse façon « listes intelligentes » de Rappels : pastille colorée, compteur, libellé. */
    private Node tile(String title, long value, String cls, String icon, String tileClass) {
        Label v = Ui.label("", "summary-value");
        Motion.countUp(v, value, x -> String.valueOf(Math.round(x)));
        StackPane dot = Ui.symbolTile(icon, tileClass);
        dot.setStyle("-fx-background-radius: 999;");
        HBox top = new HBox(dot, Ui.spacer(), v);
        top.setAlignment(Pos.CENTER_LEFT);
        Label name = Ui.label(title, "headline", "muted");
        VBox t = new VBox(8, top, name);
        t.getStyleClass().addAll("summary-tile", cls);
        HBox.setHgrow(t, Priority.ALWAYS);
        t.setMaxWidth(Double.MAX_VALUE);
        t.setAccessibleText(title + " : " + value);
        Motion.hoverLift(t);
        return t;
    }
}
