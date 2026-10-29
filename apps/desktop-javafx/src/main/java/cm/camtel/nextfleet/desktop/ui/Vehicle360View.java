package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.DueItem;
import cm.camtel.nextfleet.desktop.data.Models.DueLevel;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Prod;
import cm.camtel.nextfleet.domain.security.Permission;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Fiche véhicule 360° pleine page : identité, santé expliquée, chiffres clés, échéances et HISTORIQUE UNIFIÉ
 * (interventions, vidanges, pleins) — tout ce qu'un gestionnaire cherche sur un véhicule, sans changer d'écran.
 */
final class Vehicle360View extends ScrollPane {

    /** Événement de l'historique, quelle que soit sa source. */
    record Event(Instant at, String icon, String tone, String title, String detail, String amount) { }

    private final AppShell shell;
    private final Vehicle v;
    private final VBox col;

    Vehicle360View(AppShell shell, Vehicle v) {
        this.shell = shell;
        this.v = v;
        col = Views.page(this);
        col.setMaxWidth(1180);
        load();
    }

    private boolean can(Permission p) {
        return shell.session().can(p);
    }

    private void load() {
        var gw = shell.gateway();
        Ui.load("v360:" + v.id(), Ui.all(
                () -> safe(() -> gw.vehicleDueDates(v.id())),
                () -> can(Permission.VEHICLE_WRITE) || can(Permission.MAINTENANCE_WRITE) ? safe(() -> gw.workOrders(false)) : List.of(),
                () -> can(Permission.VEHICLE_WRITE) || can(Permission.MAINTENANCE_WRITE) ? safe(gw::oilChanges) : List.of(),
                () -> can(Permission.FUEL_ORDER_CREATE) || can(Permission.FUEL_ORDER_APPROVE) || can(Permission.FUEL_FILL_CREATE) ? safe(gw::fuelFills) : List.of()),
                this::render, e -> col.getChildren().setAll(back(), Ui.errorBanner(e, this::load)),
                () -> col.getChildren().setAll(back(), Ui.skeleton(-1, 170), Ui.skeleton(-1, 320)));
    }

    private static <T> List<T> safe(Supplier<List<T>> s) {
        try {
            return s.get();
        } catch (GatewayException e) {
            return List.of();
        }
    }

    private Node back() {
        Button b = new Button("Véhicules", Icons.stroke("M15 6 L9 12 L15 18", 14, "-nf-accent-text"));
        b.getStyleClass().add("back-link");
        b.setOnAction(e -> shell.show(AppShell.Section.VEHICLES));
        return b;
    }

    @SuppressWarnings("unchecked")
    private void render(Object[] r) {
        List<DueItem> dues = (List<DueItem>) r[0];
        List<Prod.WorkOrder> works = ((List<Prod.WorkOrder>) r[1]).stream().filter(w -> v.id().equals(w.vehicleId())).toList();
        List<Prod.OilChange> oils = ((List<Prod.OilChange>) r[2]).stream().filter(o -> v.id().equals(o.vehicleId())).toList();
        List<Prod.FuelFill> fills = ((List<Prod.FuelFill>) r[3]).stream().filter(f -> v.id().equals(f.vehicleId())).toList();
        long open = works.stream().filter(w -> w.status() == Prod.WorkStatus.DEMANDE).count();
        Health.Score score = Health.of(v, withSubject(dues), open);

        Node hero = hero(score, dues, works, fills);
        Node health = healthCard(score);
        Node due = dueCard(dues);
        VBox side = new VBox(16, health, due);
        side.setPrefWidth(360);
        side.setMinWidth(340);
        Node history = historyCard(works, oils, fills);
        HBox body = new HBox(16, history, side);
        HBox.setHgrow(history, Priority.ALWAYS);
        col.getChildren().setAll(back(), hero, body);
        Motion.stagger(col.getChildren());
    }

    /** Les échéances du véhicule portent sa plaque comme sujet, pour le calcul de santé. */
    private List<DueItem> withSubject(List<DueItem> dues) {
        return dues.stream().map(d -> new DueItem(d.kind(), v.registration(), d.subjectDetail(), true, d.dueOn(), d.daysLeft(), d.level())).toList();
    }

    // ------------------------------------------------------------------ en-tête

    private Node hero(Health.Score score, List<DueItem> dues, List<Prod.WorkOrder> works, List<Prod.FuelFill> fills) {
        Label over = Ui.label((v.category() == null ? "Véhicule" : v.category()).toUpperCase(java.util.Locale.FRANCE)
                + (v.modelYear() == null ? "" : "  ·  " + v.modelYear()), "overline");
        Label plate = Ui.label(v.registration(), "v360-plate");
        Label model = Ui.label(v.label(), "title3", "muted");
        model.setStyle("-fx-font-weight: 500;");
        HBox pills = new HBox(8, Ui.statusPill(v.status()));
        VBox id = new VBox(4, over, plate, model, pills);
        VBox.setMargin(pills, new javafx.geometry.Insets(6, 0, 0, 0));

        StackPane ring = Health.ring(score, 44, 9, 26);
        VBox ringBox = new VBox(6, ring, Ui.label(score.label(), "footnote", "w-600"));
        ringBox.setAlignment(Pos.CENTER);

        LocalDate next = dues.stream().filter(d -> d.level() != DueLevel.A_JOUR || d.daysLeft() <= 60)
                .map(DueItem::dueOn).min(Comparator.naturalOrder()).orElse(v.nextMaintenance());
        BigDecimal spend = works.stream().filter(w -> w.status() == Prod.WorkStatus.CLOTUREE && w.costXaf() != null)
                .map(Prod.WorkOrder::costXaf).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(fills.stream().map(Prod.FuelFill::amountXaf).reduce(BigDecimal.ZERO, BigDecimal::add));
        HBox stats = new HBox(28,
                stat(v.odometerKm() == null ? "—" : Views.km(v.odometerKm()), "Kilométrage"),
                stat(next == null ? "—" : Views.day(next), "Prochaine échéance"),
                stat(v.driverName() == null ? "Non affecté" : v.driverName(), "Conducteur"),
                stat(v.lastKnownPlace() == null ? "—" : v.lastKnownPlace(), "Affectation"),
                stat(spend.signum() == 0 ? "—" : Ui.compactXaf(spend) + " FCFA", "Dépenses"));

        HBox actions = new HBox(10);
        Button detail = new Button("Documents et affectation", Icons.stroke(Icons.DOC, 15, "-nf-text"));
        detail.getStyleClass().add("btn-secondary");
        detail.setOnAction(e -> openSheet());
        actions.getChildren().add(detail);
        if (shell.visible(AppShell.Section.MAINTENANCE) || shell.visible(AppShell.Section.WORKSHOP)) {
            Button work = Views.filled("Demander une intervention", Icons.WRENCH, () ->
                    shell.show(shell.visible(AppShell.Section.MAINTENANCE) ? AppShell.Section.MAINTENANCE : AppShell.Section.WORKSHOP));
            actions.getChildren().add(work);
        }
        actions.setAlignment(Pos.TOP_RIGHT);
        HBox top = new HBox(24, id, Ui.spacer(), actions, ringBox);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(22, top, Ui.divider(), stats);
        card.getStyleClass().addAll("card", "v360-hero");
        return card;
    }

    private static Node stat(String value, String label) {
        Label v = Ui.label(value, "v360-stat-value");
        v.setMinWidth(0);
        VBox b = new VBox(2, v, Ui.label(label, "v360-stat-label"));
        b.getStyleClass().add("v360-stat");
        return b;
    }

    private void openSheet() {
        VehicleSheet sheet = new VehicleSheet(v, shell.gateway(), shell, shell::closeSheet, null);
        shell.openSheet(sheet, 440);
    }

    // ------------------------------------------------------------------ santé et échéances

    private Node healthCard(Health.Score score) {
        Label t = Ui.label("Santé", "title3");
        VBox list = new VBox(10);
        if (score.reasons().isEmpty()) {
            list.getChildren().add(line(Icons.CHECK, "-nf-green-text", "Aucun point d'attention", "Documents à jour, aucun entretien en attente."));
        }
        for (String why : score.reasons()) {
            boolean bad = why.contains("expiré") || why.contains("Hors service");
            list.getChildren().add(line(bad ? Icons.CLOSE : Icons.CALENDAR, bad ? "-nf-red-text" : "-nf-orange-text", why, null));
        }
        VBox card = new VBox(14, t, list);
        card.getStyleClass().add("card");
        card.setStyle("-fx-padding: 20 22;");
        return card;
    }

    private static Node line(String icon, String color, String title, String detail) {
        VBox text = new VBox(1, Ui.label(title, "body", "w-600"));
        if (detail != null) text.getChildren().add(Ui.label(detail, "footnote", "muted"));
        ((Label) text.getChildren().get(0)).setWrapText(true);
        HBox h = new HBox(12, Icons.stroke(icon, 16, color), text);
        h.setAlignment(Pos.TOP_LEFT);
        return h;
    }

    private Node dueCard(List<DueItem> dues) {
        Label t = Ui.label("Échéances", "title3");
        VBox list = new VBox(10);
        List<DueItem> sorted = dues.stream().sorted(Comparator.comparing(DueItem::dueOn)).toList();
        for (DueItem d : sorted) {
            VBox what = new VBox(1, Ui.label(d.kind().label, "body", "w-600"), Ui.label(Views.day(d.dueOn()), "footnote", "muted"));
            HBox row = new HBox(10, what, Ui.spacer(), Ui.dueChip(d));
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
        if (sorted.isEmpty()) list.getChildren().add(Ui.label("Aucun document enregistré : ajoutez-les depuis « Documents et affectation ».", "footnote", "muted"));
        VBox card = new VBox(14, t, list);
        card.getStyleClass().add("card");
        card.setStyle("-fx-padding: 20 22;");
        return card;
    }

    // ------------------------------------------------------------------ historique unifié

    private Node historyCard(List<Prod.WorkOrder> works, List<Prod.OilChange> oils, List<Prod.FuelFill> fills) {
        List<Event> events = new ArrayList<>();
        for (Prod.WorkOrder w : works) {
            Instant at = w.immobilisedFrom() != null ? w.immobilisedFrom() : w.createdAt();
            events.add(new Event(at, Icons.WRENCH, w.status() == Prod.WorkStatus.CLOTUREE ? "-nf-green-text" : "-nf-orange-text",
                    w.categoryLabel() + " · " + w.title(), w.status().label + (w.garageLabel() == null ? "" : " · " + w.garageLabel()),
                    w.costXaf() == null ? null : Views.xaf(w.costXaf())));
        }
        for (Prod.OilChange o : oils) {
            events.add(new Event(o.doneOn().atTime(12, 0).atZone(Views.DOUALA).toInstant(), Icons.GAUGE, "-nf-blue-text",
                    "Vidange" + (o.oilLabel() == null ? "" : " · " + o.oilLabel()),
                    Views.km(o.odometerKm()) + (o.nextDueKm() == null ? "" : " · prochaine à " + Views.km(o.nextDueKm())),
                    o.costXaf() == null ? null : Views.xaf(o.costXaf())));
        }
        for (Prod.FuelFill f : fills) {
            events.add(new Event(f.filledAt(), Icons.FUEL, "-nf-text", "Plein · " + Views.liters(f.liters()),
                    f.supplierName() + (f.odometerKm() == null ? "" : " · " + Views.km(f.odometerKm())), Views.xaf(f.amountXaf())));
        }
        events.sort(Comparator.comparing(Event::at, Comparator.nullsLast(Comparator.reverseOrder())));
        Label t = Ui.label("Historique", "title3");
        Label count = Ui.label(events.isEmpty() ? "" : events.size() + " événement" + (events.size() > 1 ? "s" : ""), "footnote", "muted");
        HBox head = new HBox(10, t, Ui.spacer(), count);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox list = new VBox(0);
        int i = 0;
        for (Event e : events) {
            if (i == 25) break;
            list.getChildren().add(eventRow(e, i == 0, i == Math.min(events.size(), 25) - 1));
            i++;
        }
        if (events.isEmpty()) {
            list.getChildren().add(Ui.statePanel(Icons.LIST, "Aucun événement",
                    "Les interventions, vidanges et pleins de ce véhicule s'afficheront ici, du plus récent au plus ancien."));
        }
        VBox card = new VBox(14, head, list);
        card.getStyleClass().add("card");
        card.setStyle("-fx-padding: 20 22;");
        return card;
    }

    private static Node eventRow(Event e, boolean first, boolean last) {
        StackPane dot = new StackPane(Icons.stroke(e.icon(), 15, e.tone()));
        dot.getStyleClass().add("timeline-dot");
        dot.setStyle("-fx-background-color: -nf-select;");
        Region lineTop = new Region();
        lineTop.getStyleClass().add("timeline-line");
        lineTop.setMinHeight(10);
        lineTop.setVisible(!first);
        Region lineBottom = new Region();
        lineBottom.getStyleClass().add("timeline-line");
        VBox.setVgrow(lineBottom, Priority.ALWAYS);
        lineBottom.setVisible(!last);
        VBox rail = new VBox(lineTop, dot, lineBottom);
        rail.setAlignment(Pos.TOP_CENTER);
        rail.setMinWidth(30);
        Label title = Ui.label(e.title(), "row-title");
        title.setMinWidth(0);
        Label detail = Ui.label((e.at() == null ? "" : Views.day(e.at().atZone(Views.DOUALA).toLocalDate()) + " · ") + e.detail(), "footnote", "muted");
        detail.setMinWidth(0);
        VBox text = new VBox(2, title, detail);
        text.setMinWidth(0);
        text.setStyle("-fx-padding: 12 0 14 0;");
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(14, rail, text);
        if (e.amount() != null) {
            Label amount = Ui.label(e.amount(), "body", "w-600");
            amount.setMinWidth(Region.USE_PREF_SIZE);
            amount.setStyle("-fx-padding: 12 0 0 0;");
            row.getChildren().add(amount);
        }
        return row;
    }
}
