package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Prod;
import cm.camtel.nextfleet.domain.security.Permission;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Vidanges : où en est chaque véhicule (prochaine vidange au kilomètre ou à la date, la plus urgente d'abord), filtrable
 * par direction et par état, historique des vidanges, saisie, exports PDF et CSV produits par le serveur (journalisés).
 */
final class OilView extends ScrollPane {

    private final AppShell shell;
    private final VBox col;
    private String direction;
    private Prod.OilState state;
    private int tab;
    private List<String> directions = List.of();
    private List<Prod.OilStatus> status = List.of();
    private List<Prod.OilChange> history = List.of();

    OilView(AppShell shell) {
        this.shell = shell;
        col = Views.page(this);
        load();
    }

    private boolean can(Permission p) {
        return shell.session().can(p);
    }

    private void load() {
        var gw = shell.gateway();
        String dir = direction;
        Ui.load("oil:" + dir, Ui.all(() -> gw.oilStatus(dir), () -> safe(gw::oilChanges), () -> safe(gw::directions)), r -> {
            @SuppressWarnings("unchecked") List<Prod.OilStatus> s = (List<Prod.OilStatus>) r[0];
            @SuppressWarnings("unchecked") List<Prod.OilChange> h = (List<Prod.OilChange>) r[1];
            @SuppressWarnings("unchecked") List<String> d = (List<String>) r[2];
            status = s;
            history = h;
            if (!d.isEmpty()) directions = d;
            render();
        }, err -> col.getChildren().setAll(header(), Ui.errorBanner(err, this::load)),
                () -> col.getChildren().setAll(header(), Ui.skeleton(-1, 90), Ui.skeleton(-1, 360)));
    }

    private static <T> List<T> safe(java.util.function.Supplier<List<T>> s) {
        try {
            return s.get();
        } catch (GatewayException e) {
            return List.of();
        }
    }

    private Node header() {
        List<Node> actions = new ArrayList<>();
        if (can(Permission.REPORT_EXPORT)) {
            Button export = Views.tinted("Exporter", Icons.DOWNLOAD, null);
            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
            menu.getItems().addAll(
                    item("État des vidanges — PDF", () -> exportState(export, "pdf")),
                    item("État des vidanges — CSV (Excel)", () -> exportState(export, "csv")),
                    new javafx.scene.control.SeparatorMenuItem(),
                    item("Historique de l'année — PDF", () -> exportHistory(export, "pdf")),
                    item("Historique de l'année — CSV (Excel)", () -> exportHistory(export, "csv")));
            export.setOnAction(e -> menu.show(export, javafx.geometry.Side.BOTTOM, 0, 6));
            actions.add(export);
        }
        if (can(Permission.MAINTENANCE_WRITE)) actions.add(Views.filled("Saisir une vidange", Icons.PLUS, () -> sheet(null)));
        return Views.header("Vidanges", "Prochaine vidange de chaque véhicule, au kilomètre ou à la date — la plus urgente d'abord.",
                actions.toArray(Node[]::new));
    }

    private static javafx.scene.control.MenuItem item(String text, Runnable r) {
        javafx.scene.control.MenuItem m = new javafx.scene.control.MenuItem(text);
        m.setOnAction(e -> r.run());
        return m;
    }

    private void exportState(Button b, String format) {
        LocalDate t = Views.today();
        Exports.report(shell, this, b, "etat-vidanges", t, t, direction, null, direction, format);
    }

    private void exportHistory(Button b, String format) {
        LocalDate t = Views.today();
        Exports.report(shell, this, b, "vidanges", t.withDayOfYear(1), t, direction, null, direction, format);
    }

    // ------------------------------------------------------------------ rendu

    private void render() {
        Map<Prod.OilState, Integer> counts = new EnumMap<>(Prod.OilState.class);
        for (Prod.OilStatus s : status) counts.merge(s.state(), 1, Integer::sum);
        int none = counts.getOrDefault(Prod.OilState.AUCUNE, 0) + counts.getOrDefault(Prod.OilState.SANS_INTERVALLE, 0);
        HBox tiles = Views.tiles(
                stateTile(counts.getOrDefault(Prod.OilState.EN_RETARD, 0), "En retard", "summary-expire", Prod.OilState.EN_RETARD),
                stateTile(counts.getOrDefault(Prod.OilState.A_PREVOIR, 0), "À prévoir sous 1 000 km ou 30 j", "summary-soon", Prod.OilState.A_PREVOIR),
                stateTile(counts.getOrDefault(Prod.OilState.A_JOUR, 0), "À jour", "summary-ok", Prod.OilState.A_JOUR),
                stateTile(none, "À renseigner", "summary-ok", Prod.OilState.AUCUNE));

        List<String> tabs = List.of("État par véhicule", "Historique · " + filteredHistory().size());
        Controls.Segmented seg = new Controls.Segmented(tabs, tab, i -> {
            tab = i;
            render();
        });
        HBox bar = new HBox(12, seg, Ui.spacer(), directionChips());
        bar.setAlignment(Pos.CENTER_LEFT);
        Node body = tab == 0 ? stateList() : historyList();
        col.getChildren().setAll(header(), tiles, bar, body);
        Motion.stagger(col.getChildren());
    }

    private Node stateTile(int n, String caption, String cls, Prod.OilState s) {
        VBox t = Views.tile(String.valueOf(n), caption, cls);
        boolean on = state == s;
        t.getStyleClass().add("clickable-tile");
        if (on) t.getStyleClass().add("tile-selected");
        t.setOnMouseClicked(e -> {
            state = on ? null : s;
            tab = 0;
            render();
        });
        t.setAccessibleText(caption + " : " + n + (on ? ", filtre actif" : ""));
        javafx.scene.control.Tooltip.install(t, new javafx.scene.control.Tooltip(on ? "Afficher tous les véhicules" : "Afficher seulement : " + caption));
        return t;
    }

    /** Filtre par direction : « Toutes » puis une pastille par direction du périmètre. */
    private Node directionChips() {
        HBox chips = new HBox(6);
        chips.setAlignment(Pos.CENTER_RIGHT);
        if (directions.isEmpty()) return chips;
        ToggleGroup g = new ToggleGroup();
        List<String> all = new ArrayList<>();
        all.add(null);
        all.addAll(directions);
        for (String d : all) {
            ToggleButton b = new ToggleButton(d == null ? "Toutes les directions" : d,
                    d == null ? Icons.stroke(Icons.BUILDING, 13, "-nf-muted") : null);
            b.getStyleClass().add("chip");
            b.setToggleGroup(g);
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setSelected(java.util.Objects.equals(d, direction));
            b.setOnAction(e -> {
                if (!b.isSelected()) b.setSelected(true);
                if (java.util.Objects.equals(direction, d)) return;
                direction = d;
                load();
            });
            b.setAccessibleText("Direction : " + (d == null ? "toutes" : d));
            chips.getChildren().add(b);
        }
        ScrollPane sp = new ScrollPane(chips);
        sp.setFitToHeight(true);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color: transparent; -fx-padding: 2;");
        sp.setMaxWidth(720);
        HBox.setHgrow(sp, Priority.SOMETIMES);
        return sp;
    }

    private Node stateList() {
        List<Prod.OilStatus> shown = status.stream().filter(s -> state == null || s.state() == state
                || (state == Prod.OilState.AUCUNE && s.state() == Prod.OilState.SANS_INTERVALLE)).toList();
        if (shown.isEmpty()) {
            return Ui.statePanel(Icons.DROP, state == null ? "Aucun véhicule dans ce périmètre" : "Aucun véhicule dans cet état",
                    state == null ? "Les véhicules de votre entité apparaîtront ici." : "Touchez à nouveau la vignette pour tout afficher.");
        }
        List<Node> rows = new ArrayList<>();
        for (Prod.OilStatus s : shown) rows.add(stateRow(s));
        String title = (direction == null ? "Tous les véhicules" : "Direction " + direction) + " · " + shown.size();
        return Ui.insetGroup(title, rows, "À prévoir : moins de 1 000 km ou de 30 jours avant l'échéance. Le kilométrage est le dernier relevé connu "
                + "(plein, vidange, mission ou saisie).");
    }

    private Node stateRow(Prod.OilStatus s) {
        String tileClass = switch (s.state()) {
            case EN_RETARD -> "tile-red";
            case A_PREVOIR -> "tile-orange";
            case A_JOUR -> "tile-green";
            default -> "tile-gray";
        };
        Label title = Ui.label(s.registration() + "  ·  " + s.model(), "row-title");
        String place = join(" · ", s.direction(), s.siteLabel());
        String last = s.lastDoneOn() == null ? "Aucune vidange enregistrée"
                : "Dernière le " + Views.day(s.lastDoneOn()) + (s.lastKm() == null ? "" : " à " + Views.km(s.lastKm()))
                + (s.oilLabel() == null ? "" : " · " + s.oilLabel());
        Label sub = Ui.label(join("  ·  ", place, last), "footnote", "muted");
        title.setMinWidth(0);
        sub.setMinWidth(0);
        VBox text = new VBox(3, title, sub);
        Node bar = progress(s);
        if (bar != null) text.getChildren().add(bar);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label big = Ui.label(headline(s), "row-title");
        big.getStyleClass().add("tabular");
        Label when = Ui.label(nextLine(s), "footnote", "muted");
        VBox right = new VBox(3, big, when, Views.pill(s.state().label, s.state().tone));
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(14, Ui.symbolTile(Icons.DROP, tileClass), text, right);
        row.getStyleClass().addAll("list-row", "clickable-row");
        row.getProperties().put("hasIcon", true);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> openVehicle(s.vehicleId(), s.registration()));
        row.setAccessibleText(s.registration() + ", " + s.state().label + ", " + headline(s) + ", " + nextLine(s));
        return row;
    }

    /** Valeur principale : kilomètres restants ou dépassement ; à défaut le délai. */
    static String headline(Prod.OilStatus s) {
        if (s.remainingKm() != null) {
            return s.remainingKm().signum() < 0 ? "Dépassée de " + Views.km(s.remainingKm().negate()) : "Reste " + Views.km(s.remainingKm());
        }
        if (s.daysLeft() != null) return s.daysLeft() < 0 ? "Dépassée de " + (-s.daysLeft()) + " j" : "Dans " + s.daysLeft() + " j";
        return s.lastDoneOn() == null ? "—" : "Échéance inconnue";
    }

    static String nextLine(Prod.OilStatus s) {
        List<String> p = new ArrayList<>();
        if (s.nextDueKm() != null) p.add("à " + Views.km(s.nextDueKm()));
        if (s.nextDueOn() != null) p.add("le " + Views.day(s.nextDueOn()));
        if (p.isEmpty()) return s.lastDoneOn() == null ? "Saisissez la dernière vidange" : "Intervalle à compléter";
        return "Prochaine " + String.join(" ou ", p);
    }

    /** Barre d'usure de l'intervalle en kilomètres (de la dernière vidange à la prochaine). */
    private static Node progress(Prod.OilStatus s) {
        if (s.lastKm() == null || s.nextDueKm() == null || s.currentKm() == null) return null;
        BigDecimal span = s.nextDueKm().subtract(s.lastKm());
        if (span.signum() <= 0) return null;
        double ratio = Math.max(0, Math.min(1, s.currentKm().subtract(s.lastKm()).doubleValue() / span.doubleValue()));
        Region track = new Region();
        track.getStyleClass().add("oil-track");
        Region fill = new Region();
        fill.getStyleClass().addAll("oil-fill", "oil-" + s.state().tone);
        StackPane p = new StackPane(track, fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        p.setMaxWidth(260);
        p.setPrefWidth(260);
        p.setMinHeight(5);
        p.setMaxHeight(5);
        fill.maxWidthProperty().bind(p.widthProperty().multiply(ratio));
        fill.prefWidthProperty().bind(p.widthProperty().multiply(ratio));
        VBox.setMargin(p, new javafx.geometry.Insets(4, 0, 0, 0));
        p.setAccessibleText(Math.round(ratio * 100) + " % de l'intervalle parcouru");
        return p;
    }

    private List<Prod.OilChange> filteredHistory() {
        return history.stream().filter(o -> direction == null || direction.equals(o.direction()))
                .sorted(java.util.Comparator.comparing(Prod.OilChange::doneOn).reversed()).toList();
    }

    private Node historyList() {
        List<Prod.OilChange> list = filteredHistory();
        if (list.isEmpty()) {
            return Ui.statePanel(Icons.DROP, "Aucune vidange enregistrée", "Saisissez la dernière vidange de chaque véhicule pour suivre la prochaine échéance.");
        }
        List<Node> rows = new ArrayList<>();
        for (Prod.OilChange o : list) {
            String next = o.nextDueKm() != null ? "prochaine à " + Views.km(o.nextDueKm()) : "";
            if (o.nextDueOn() != null) next += (next.isEmpty() ? "prochaine le " : " ou le ") + Views.day(o.nextDueOn());
            String sub = join("  ·  ", o.direction(), Views.day(o.doneOn()) + " à " + Views.km(o.odometerKm()), o.oilLabel(),
                    o.filters().isEmpty() ? null : String.join(", ", o.filters()).toLowerCase(java.util.Locale.FRANCE), o.garageLabel());
            Label amount = Ui.label(o.costXaf() == null ? "—" : Views.xaf(o.costXaf()), "row-title");
            amount.getStyleClass().add("tabular");
            HBox r = Views.row(Ui.symbolTile(Icons.DROP, "tile-mint"), o.registration() + (next.isEmpty() ? "" : "  ·  " + next), sub, amount,
                    () -> openVehicle(o.vehicleId(), o.registration()));
            rows.add(r);
        }
        return Ui.insetGroup("Historique · " + list.size(), rows, "Le coût d'une vidange rattachée à une intervention est compté une seule fois.");
    }

    private void openVehicle(java.util.UUID id, String registration) {
        Ui.async(() -> shell.gateway().vehicle(id), shell::openVehicle, err -> shell.openVehicles(registration));
    }

    private static String join(String sep, String... parts) {
        List<String> p = new ArrayList<>();
        for (String s : parts) if (s != null && !s.isBlank()) p.add(s);
        return String.join(sep, p);
    }

    // ------------------------------------------------------------------ saisie

    private void sheet(Vehicle preselect) {
        Ui.async(() -> shell.gateway().vehicles(null, null, direction, 0, 200).items(),
                vs -> open(shell, vs, preselect, o -> load()), err -> shell.toasts().info("Véhicules indisponibles", err.getMessage()));
    }

    /** Feuille de saisie d'une vidange (partagée avec l'atelier et la fiche véhicule). */
    static void open(AppShell shell, List<Vehicle> vehicles, Vehicle preselect, Consumer<Prod.OilChange> after) {
        if (vehicles.isEmpty()) {
            shell.toasts().info("Aucun véhicule", "Aucun véhicule n'est disponible dans votre périmètre.");
            return;
        }
        ComboBox<Vehicle> vehicle = FormSheet.combo(vehicles);
        vehicle.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Vehicle v) {
                return v == null ? "" : v.registration() + " · " + v.label() + (v.direction() == null ? "" : " · " + v.direction());
            }

            @Override
            public Vehicle fromString(String s) {
                return null;
            }
        });
        if (preselect != null) vehicles.stream().filter(v -> v.id().equals(preselect.id())).findFirst().ifPresent(vehicle::setValue);
        var date = FormSheet.date(Views.today());
        TextField km = FormSheet.text("Ex. 12600");
        Vehicle sel = vehicle.getValue();
        if (sel != null && sel.odometerKm() != null) km.setPromptText("Dernier relevé : " + Views.km(sel.odometerKm()));
        vehicle.valueProperty().addListener((o, a, v) -> km.setPromptText(v != null && v.odometerKm() != null
                ? "Dernier relevé : " + Views.km(v.odometerKm()) : "Ex. 12600"));
        TextField oil = FormSheet.text("Ex. 15W40");
        TextField liters = FormSheet.text("Ex. 7");
        TextField everyKm = FormSheet.text("Ex. 5000");
        everyKm.setText("5000");
        TextField everyMonths = FormSheet.text("Ex. 6");
        everyMonths.setText("6");
        TextField cost = FormSheet.text("FCFA (facultatif)");
        TextField garage = FormSheet.text("Ex. Atelier central");
        var oilFilter = FormSheet.check("Filtre à huile", true);
        var airFilter = FormSheet.check("Filtre à air", false);
        var fuelFilter = FormSheet.check("Filtre à carburant", false);
        FormSheet f = new FormSheet("Vidange", "Enregistrer", shell::closeSheet)
                .intro("La prochaine échéance (kilométrage et date) est calculée et suivie dans l'écran Vidanges et les échéances.")
                .field("Véhicule *", vehicle).row("Date *", date, "Kilométrage *", km).row("Huile", oil, "Quantité (L)", liters)
                .node(new HBox(16, oilFilter, airFilter, fuelFilter))
                .row("Intervalle (km)", everyKm, "Intervalle (mois)", everyMonths).row("Coût (FCFA)", cost, "Garage", garage);
        f.onSubmit(() -> shell.gateway().addOilChange(new Prod.OilChangeDraft(FormSheet.pick(vehicle, "Véhicule").id(),
                FormSheet.day(date, "Date", true), FormSheet.dec(km, "Kilométrage", true), FormSheet.opt(oil),
                FormSheet.dec(liters, "Quantité", false), oilFilter.isSelected(), airFilter.isSelected(), fuelFilter.isSelected(),
                FormSheet.integer(everyKm, "Intervalle"), FormSheet.integer(everyMonths, "Intervalle"), FormSheet.dec(cost, "Coût", false),
                FormSheet.opt(garage))), (Prod.OilChange o) -> {
            shell.closeSheet();
            shell.toasts().success("Vidange enregistrée", o.registration() + (o.nextDueKm() != null ? " · prochaine à " + Views.km(o.nextDueKm()) : ""));
            after.accept(o);
        });
        shell.openSheet(f, 480);
    }

    /** Pour la recette automatisée. */
    void showHistoryForTest() {
        tab = 1;
        render();
    }
}
