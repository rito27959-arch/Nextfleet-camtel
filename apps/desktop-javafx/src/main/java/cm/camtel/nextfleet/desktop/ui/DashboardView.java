package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.Alert;
import cm.camtel.nextfleet.desktop.data.Models.DashboardData;
import cm.camtel.nextfleet.desktop.data.Models.MonthlyConsumption;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import cm.camtel.nextfleet.domain.security.Permission;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** Écran 02 — Tableau de bord (NF-20 ; maquette p. 17). */
public final class DashboardView extends ScrollPane {

    private static final Locale FR = Locale.FRANCE;
    private final FleetGateway gateway;
    private final Session session;
    private final AppShell shell;
    private final VBox root = new VBox(20);

    public DashboardView(FleetGateway gateway, Session session, AppShell shell) {
        this.gateway = gateway;
        this.session = session;
        this.shell = shell;
        setFitToWidth(true);
        root.getStyleClass().add("content");
        setContent(root);
        load();
    }

    private void load() {
        Ui.load("dashboard", Ui.all(gateway::dashboard,
                        () -> safe(() -> gateway.dueDates(30)),
                        () -> session.can(Permission.VEHICLE_WRITE) || session.can(Permission.MAINTENANCE_WRITE) ? safe(() -> gateway.workOrders(true)) : List.of(),
                        () -> session.can(Permission.FUEL_ORDER_APPROVE) ? safe(gateway::fuelOrders) : List.of(),
                        () -> session.can(Permission.MISSION_MANAGE) ? safe(gateway::missions) : List.of(),
                        () -> safe(() -> gateway.vehicles(null, null, 0, 500).items())),
                this::render, this::fail, () -> root.getChildren().setAll(header(null, -1, -1), skeletonGrid()));
    }

    /** Une source refusée (droits) ou indisponible ne bloque pas le tableau de bord : elle est simplement absente. */
    private static <T> List<T> safe(java.util.function.Supplier<List<T>> s) {
        try {
            return s.get();
        } catch (GatewayException e) {
            return List.of();
        }
    }

    private void fail(GatewayException e) {
        root.getChildren().setAll(header(null, -1, -1), Ui.errorBanner(e, this::load));
        if (e.kind() == GatewayException.Kind.SERVER) {
            root.getChildren().add(Ui.statePanel(Icons.CHART, "Indicateurs indisponibles",
                    "Aucune valeur n'est affichée tant que le serveur ne les fournit pas."));
        }
    }

    private Node header(DashboardData d, int pending, int available) {
        String first = session.displayName().replaceAll("\\s*\\(.*\\)", "").trim().split("\\s+")[0];
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Douala"))
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", FR)).toUpperCase(FR);
        Label overline = Ui.label(today, "overline");
        Label title = Ui.label((AppShell.greeting().equals("BONSOIR") ? "Bonsoir, " : "Bonjour, ") + first + ".", "large-title");
        String summary;
        if (pending < 0) summary = "Préparation de votre journée…";
        else if (pending == 0) summary = "Rien ne demande votre attention. " + availability(d, available);
        else summary = pending + (pending > 1 ? " éléments demandent" : " élément demande") + " votre attention · " + availability(d, available);
        Label sub = Ui.label(summary, "title3", "muted");
        sub.setStyle("-fx-font-weight: 500;");
        VBox left = new VBox(4, overline, title, sub);

        HBox actions = new HBox(10);
        if (shell.visible(AppShell.Section.REPORTS)) {
            Button export = new Button("Rapports", Icons.stroke(Icons.DOWNLOAD, 16, "-nf-text"));
            export.getStyleClass().add("btn-secondary");
            export.setOnAction(e -> shell.show(AppShell.Section.REPORTS));
            actions.getChildren().add(export);
        }
        Button search = new Button("Rechercher", Icons.stroke(Icons.SEARCH, 16, "white"));
        search.getStyleClass().add("btn-primary");
        search.setTooltip(new Tooltip("Recherche universelle (Ctrl+K)"));
        search.setOnAction(e -> shell.openSearch(""));
        actions.getChildren().add(search);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox h = new HBox(left, Ui.spacer(), actions);
        h.setAlignment(Pos.BOTTOM_LEFT);
        return h;
    }

    private static String availability(DashboardData d, int available) {
        if (d == null) return "";
        return (available >= 0 ? available : d.activeVehicles()) + " véhicules sur " + d.totalVehicles() + " disponibles.";
    }

    private Node skeletonGrid() {
        HBox top = new HBox(16);
        VBox queue = new VBox(12, Ui.skeleton(160, 18), Ui.skeleton(-1, 52), Ui.skeleton(-1, 52), Ui.skeleton(-1, 52));
        queue.getStyleClass().add("card");
        HBox.setHgrow(queue, Priority.ALWAYS);
        VBox side = new VBox(12, Ui.skeleton(140, 18), Ui.skeleton(100, 100), Ui.skeleton(180, 14));
        side.getStyleClass().add("card");
        side.setPrefWidth(340);
        top.getChildren().addAll(queue, side);
        return new VBox(20, top);
    }

    @SuppressWarnings("unchecked")
    private void render(Object[] r) {
        DashboardData d = (DashboardData) r[0];
        List<cm.camtel.nextfleet.desktop.data.Models.DueItem> dues = (List<cm.camtel.nextfleet.desktop.data.Models.DueItem>) r[1];
        List<cm.camtel.nextfleet.desktop.data.Prod.WorkOrder> works = (List<cm.camtel.nextfleet.desktop.data.Prod.WorkOrder>) r[2];
        List<cm.camtel.nextfleet.desktop.data.Prod.FuelOrder> fuels = (List<cm.camtel.nextfleet.desktop.data.Prod.FuelOrder>) r[3];
        List<cm.camtel.nextfleet.desktop.data.Ops.Mission> missions = (List<cm.camtel.nextfleet.desktop.data.Ops.Mission>) r[4];
        List<Vehicle> vehicles = (List<Vehicle>) r[5];
        List<Attention.Item> items = Attention.build(dues, works, fuels, missions, session.can(Permission.FUEL_ORDER_APPROVE),
                session.can(Permission.MISSION_MANAGE), java.time.Instant.now());
        java.util.Map<java.util.UUID, Long> open = new java.util.HashMap<>();
        for (var w : works) if (w.status() == cm.camtel.nextfleet.desktop.data.Prod.WorkStatus.DEMANDE) open.merge(w.vehicleId(), 1L, Long::sum);
        List<Health.Score> scores = vehicles.stream().map(v -> Health.of(v, dues, open.getOrDefault(v.id(), 0L))).toList();
        int available = vehicles.isEmpty() ? -1 : (int) vehicles.stream().filter(v -> v.status() == cm.camtel.nextfleet.desktop.data.Models.VehicleStatus.AVAILABLE
                || v.status() == cm.camtel.nextfleet.desktop.data.Models.VehicleStatus.IN_SERVICE
                || v.status() == cm.camtel.nextfleet.desktop.data.Models.VehicleStatus.ON_MISSION).count();

        Node queue = queueCard(items, vehicles);
        Node health = healthCard(vehicles, scores);
        HBox top = new HBox(16, queue, health);
        HBox.setHgrow(queue, Priority.ALWAYS);
        root.getChildren().setAll(header(d, items.size(), available), top, kpiRow(d), consumptionCard(d), recentTable(d.recentVehicles()), footnote(d));
        Motion.stagger(root.getChildren());
    }

    // ------------------------------------------------------------------ file « À traiter »

    private Node queueCard(List<Attention.Item> items, List<Vehicle> vehicles) {
        Label title = Ui.label("À traiter", "title2");
        Label count = Ui.label(String.valueOf(items.size()), "count-chip");
        count.setVisible(!items.isEmpty());
        Label hint = Ui.label("Classé par urgence", "footnote", "muted");
        HBox head = new HBox(10, title, count, Ui.spacer(), hint);
        head.getStyleClass().add("queue-head");
        VBox list = new VBox(2);
        if (items.isEmpty()) {
            StackPane ok = Ui.symbolTile(Icons.CHECK, "tile-green");
            VBox empty = new VBox(8, ok, Ui.label("Tout est à jour", "title3"),
                    Ui.label("Aucun document expiré, aucune demande en attente, aucune validation à faire.", "footnote", "muted"));
            empty.getStyleClass().add("queue-empty");
            list.getChildren().add(empty);
        }
        int shown = 0;
        for (Attention.Item it : items) {
            if (shown++ == 7) break;
            list.getChildren().add(queueRow(it, vehicles));
        }
        VBox card = new VBox(head, list);
        if (items.size() > 7) {
            Button more = new Button((items.size() - 7) + " autre" + (items.size() - 7 > 1 ? "s" : "") + " élément" + (items.size() - 7 > 1 ? "s" : ""),
                    Icons.stroke(Icons.CHEVRON_RIGHT, 12, "-nf-accent-text"));
            more.getStyleClass().add("btn-ghost");
            more.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            more.setOnAction(e -> shell.show(AppShell.Section.DUE));
            HBox f = new HBox(more);
            f.setStyle("-fx-padding: 4 10 0 10;");
            card.getChildren().add(f);
        }
        card.getStyleClass().addAll("card", "queue-card");
        card.setMinWidth(520);
        return card;
    }

    private Node queueRow(Attention.Item it, List<Vehicle> vehicles) {
        javafx.scene.layout.Region rank = new javafx.scene.layout.Region();
        rank.getStyleClass().addAll("queue-rank", switch (it.rank()) {
            case CRITICAL -> "rank-critical";
            case HIGH -> "rank-high";
            default -> "rank-normal";
        });
        StackPane icon = new StackPane(Icons.stroke(it.icon(), 17, "-nf-text"));
        icon.getStyleClass().add("timeline-dot");
        icon.setStyle("-fx-background-color: -nf-select;");
        Label t = Ui.label(it.title(), "queue-title");
        Label why = Ui.label(it.why(), "queue-why");
        t.setMinWidth(0);
        why.setMinWidth(0);
        VBox text = new VBox(2, t, why);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label when = Ui.label(it.when(), "queue-when");
        when.setStyle("-fx-text-fill: " + switch (it.rank()) {
            case CRITICAL -> "-nf-red-text";
            case HIGH -> "-nf-orange-text";
            default -> "-nf-muted";
        } + ";");
        when.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Button act = new Button(it.action());
        act.getStyleClass().add("queue-action");
        act.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Runnable go = () -> open(it, vehicles);
        act.setOnAction(e -> go.run());
        Motion.pressable(act);
        HBox row = new HBox(14, rank, icon, text, when, act);
        row.getStyleClass().add("queue-row");
        row.setOnMouseClicked(e -> go.run());
        row.setAccessibleText(it.title() + ". " + it.why() + ". " + it.when() + ". Action : " + it.action());
        return row;
    }

    private void open(Attention.Item it, List<Vehicle> vehicles) {
        switch (it.target()) {
            case VEHICLE -> vehicles.stream().filter(v -> v.registration().replaceAll("[^A-Za-z0-9]", "")
                            .equalsIgnoreCase(it.ref().replaceAll("[^A-Za-z0-9]", ""))).findFirst()
                    .ifPresentOrElse(shell::openVehicle, () -> shell.openVehicles(it.ref()));
            case DUE -> shell.show(AppShell.Section.DUE);
            case MAINTENANCE -> shell.show(shell.visible(AppShell.Section.MAINTENANCE) ? AppShell.Section.MAINTENANCE : AppShell.Section.WORKSHOP);
            case FUEL -> shell.show(AppShell.Section.FUEL);
            case MISSION -> shell.openMission(java.util.UUID.fromString(it.ref()));
        }
    }

    // ------------------------------------------------------------------ santé de la flotte

    private Node healthCard(List<Vehicle> vehicles, List<Health.Score> scores) {
        int fleet = Health.fleet(scores);
        Health.Score s = new Health.Score(fleet, List.of());
        // les véhicules les plus fragiles, avec leur premier motif : la santé n'est jamais une boîte noire
        VBox fragile = new VBox(2);
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < scores.size(); i++) if (scores.get(i).value() < 100) order.add(i);
        order.sort(java.util.Comparator.comparingInt(i -> scores.get(i).value()));
        for (int i : order.subList(0, Math.min(3, order.size()))) {
            Vehicle v = vehicles.get(i);
            Health.Score vs = scores.get(i);
            Label reg = Ui.label(v.registration(), "row-title");
            Label why = Ui.label(vs.reasons().isEmpty() ? vs.label() : vs.reasons().get(0), "footnote", "muted");
            why.setMinWidth(0);
            VBox tx = new VBox(1, reg, why);
            tx.setMinWidth(0);
            HBox.setHgrow(tx, Priority.ALWAYS);
            HBox row = new HBox(12, Health.ring(vs, 15, 4, 11), tx);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("queue-row");
            row.setStyle("-fx-padding: 8 8;");
            row.setOnMouseClicked(e -> shell.openVehicle(v));
            fragile.getChildren().add(row);
        }
        Label title = Ui.label("Santé de la flotte", "title3");
        StackPane ring = Health.ring(s, 52, 10, 30);
        long critical = scores.stream().filter(x -> x.value() < 50).count();
        long watch = scores.stream().filter(x -> x.value() >= 50 && x.value() < 80).count();
        long good = scores.size() - critical - watch;
        VBox legend = new VBox(8,
                legendRow("health-good", good + " en bon état"),
                legendRow("health-warn", watch + " à surveiller"),
                legendRow("health-bad", critical + " critique" + (critical > 1 ? "s" : "")));
        HBox mid = new HBox(22, ring, legend);
        mid.setAlignment(Pos.CENTER_LEFT);
        Label note = Ui.label(vehicles.isEmpty() ? "Aucun véhicule dans votre périmètre."
                : "Moyenne sur " + vehicles.size() + " véhicule" + (vehicles.size() > 1 ? "s" : "") + " : documents, entretiens et statut.", "footnote", "muted");
        note.setWrapText(true);
        Button all = new Button("Voir les véhicules", Icons.stroke(Icons.CHEVRON_RIGHT, 12, "-nf-accent-text"));
        all.getStyleClass().add("btn-ghost");
        all.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        all.setOnAction(e -> shell.show(AppShell.Section.VEHICLES));
        VBox card = new VBox(16, title, mid, note);
        if (!fragile.getChildren().isEmpty()) {
            card.getChildren().addAll(Ui.label("LES PLUS FRAGILES", "section-header"), fragile);
            VBox.setMargin(fragile, new javafx.geometry.Insets(-10, -8, 0, -8));
        }
        card.getChildren().add(all);
        card.getStyleClass().add("card");
        card.setStyle("-fx-padding: 20 22;");
        card.setPrefWidth(340);
        card.setMinWidth(320);
        return card;
    }

    private static Node legendRow(String tone, String text) {
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(5);
        dot.getStyleClass().add(tone);
        dot.setStyle("-fx-stroke-width: 0; -fx-fill: " + switch (tone) {
            case "health-good" -> "-nf-green";
            case "health-warn" -> "-nf-orange";
            default -> "-nf-red";
        } + ";");
        HBox h = new HBox(10, dot, Ui.label(text, "body", "w-600"));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    // ------------------------------------------------------------------ indicateurs

    private Node kpiRow(DashboardData d) {
        Node c2 = kpi(Icons.WRENCH, "Entretiens à venir", d.upcomingMaintenance(), x -> String.valueOf(Math.round(x)), "",
                Ui.trend(BigDecimal.valueOf(d.maintenanceDelta()), true, "sur le mois dernier", ""),
                Ui.symbolTile(Icons.CALENDAR, "tile-orange"), "Échéances à 30 jours");
        Node c3 = kpi(Icons.FUEL, "Consommation moyenne", d.avgConsumption() == null ? Double.NaN : d.avgConsumption().doubleValue(),
                x -> Double.isNaN(x) ? "—" : Ui.decimal(BigDecimal.valueOf(x), 1), " L / 100 km",
                Ui.trend(d.consumptionTrendPercent(), true, "sur le mois dernier"),
                Ui.sparkline(d.consumptionSeries().stream().filter(m -> m.litersPer100Km() != null)
                        .map(m -> m.litersPer100Km().doubleValue()).toList(), 64, 30, "sparkline"), d.consumptionNote());
        Node c4 = kpi(Icons.COINS, "Coûts du mois", d.monthCostXaf() == null ? Double.NaN : d.monthCostXaf().doubleValue(),
                x -> Double.isNaN(x) ? "—" : Ui.compactXaf(BigDecimal.valueOf(x)), " FCFA",
                Ui.trend(d.costTrendPercent(), true, "sur le mois dernier"),
                Ui.symbolTile(Icons.COINS, "tile-indigo"), "Carburant et entretien validés (données fictives)");

        HBox row = new HBox(16, c2, c3, c4);
        for (Node n : row.getChildren()) {
            HBox.setHgrow(n, Priority.ALWAYS);
            ((javafx.scene.layout.Region) n).setMaxWidth(Double.MAX_VALUE);
            ((javafx.scene.layout.Region) n).setPrefWidth(250);
        }
        return row;
    }

    private Node kpi(String icon, String title, double target, java.util.function.DoubleFunction<String> format,
                     String unit, Node trend, Node bubble, String help) {
        Label t = new Label(title, Icons.stroke(icon, 15, "-nf-muted"));
        t.getStyleClass().addAll("subhead", "w-600", "muted");
        Label v = Ui.label("", "kpi-value");
        if (Double.isNaN(target)) v.setText("—"); else Motion.countUp(v, target, format);
        String value = v.getAccessibleText() == null ? "—" : v.getAccessibleText();
        Label u = Ui.label(unit, "kpi-unit");
        HBox val = new HBox(v, u);
        val.setAlignment(Pos.BASELINE_LEFT);
        boolean spark = bubble.getStyleClass().isEmpty() && !(bubble instanceof javafx.scene.layout.StackPane);
        HBox card;
        if (spark) {   // micro-courbe : alignée avec la tendance, sous la valeur, pour ne jamais chevaucher le titre
            HBox bottom = new HBox(8, trend, Ui.spacer(), bubble);
            bottom.setAlignment(Pos.BOTTOM_LEFT);
            VBox left = new VBox(8, t, val, bottom);
            HBox.setHgrow(left, Priority.ALWAYS);
            card = new HBox(left);
        } else {
            VBox left = new VBox(8, t, val, trend);
            card = new HBox(left, Ui.spacer(), bubble);
        }
        card.getStyleClass().add("card");
        Motion.hoverLift(card);
        Tooltip.install(card, new Tooltip(help));
        card.setAccessibleText(title + " : " + value + unit);
        return card;
    }

    // ------------------------------------------------------------------ graphique et alertes

    private Node middleRow(DashboardData d) {
        Node chart = consumptionCard(d);
        Node alerts = alertsCard(d.alerts());
        HBox row = new HBox(16, chart, alerts);
        HBox.setHgrow(chart, Priority.ALWAYS);
        ((javafx.scene.layout.Region) alerts).setPrefWidth(400);
        ((javafx.scene.layout.Region) alerts).setMinWidth(340);
        return row;
    }

    private Node consumptionCard(DashboardData d) {
        Label t = new Label("Consommation moyenne", Icons.stroke(Icons.FUEL, 16, "-nf-text"));
        t.getStyleClass().addAll("headline");
        Label v = Ui.label(Ui.decimal(d.avgConsumption(), 1), "kpi-value");
        Label u = Ui.label(" L / 100 km", "kpi-unit");
        HBox val = new HBox(v, u, new Label("  "), Ui.trend(d.consumptionTrendPercent(), true, "sur le mois dernier"));
        val.setAlignment(Pos.BASELINE_LEFT);

        StackPane chartHolder = new StackPane();
        Label note = Ui.label("", "footnote", "muted");
        int[] months = {3, 6, 12};
        java.util.function.IntConsumer apply = idx -> {
            List<MonthlyConsumption> s = d.consumptionSeries();
            List<MonthlyConsumption> view = s.subList(Math.max(0, s.size() - months[idx]), s.size());
            chartHolder.getChildren().setAll(chart(view));
            note.setText(months[idx] > s.size() ? "Historique disponible : " + s.size() + " mois." : qualityNote(view));
        };
        Controls.Segmented seg = new Controls.Segmented(List.of("3 M", "6 M", "1 A"), 1, apply);
        apply.accept(1);   // 6 mois par défaut (données de démonstration : 6 mois)
        HBox head = new HBox(t, Ui.spacer(), seg);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(6, head, val, chartHolder, note);
        card.getStyleClass().add("card");
        return card;
    }

    private static String qualityNote(List<MonthlyConsumption> view) {
        return view.stream().filter(m -> !"Fiable".equals(m.reliability()))
                .map(m -> capitalize(m.month().getMonth().getDisplayName(TextStyle.SHORT, FR)) + " : " + m.reliability().toLowerCase(FR))
                .reduce((a, b) -> a + " · " + b)
                .map(s -> "Qualité des données — " + s)
                .orElse("Méthode plein-à-plein ; périodes à relevé fiable uniquement.");
    }

    private Node chart(List<MonthlyConsumption> series) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis(4, 12, 2);
        y.setAutoRanging(false);
        y.setMinorTickVisible(false);
        AreaChart<String, Number> chart = new AreaChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCreateSymbols(true);
        chart.setPrefHeight(210);
        chart.setMinHeight(190);
        chart.setHorizontalZeroLineVisible(false);
        chart.setVerticalGridLinesVisible(false);
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM yyyy", FR);
        for (MonthlyConsumption m : series) {
            if (m.litersPer100Km() != null) {   // un mois sans donnée fiable n'est pas tracé à zéro
                s.getData().add(new XYChart.Data<>(capitalize(m.month().format(f)), m.litersPer100Km()));
            }
        }
        chart.getData().add(s);
        // Tracé de gauche à droite (720 ms) : la courbe « se dessine », les axes restent fixes.
        javafx.application.Platform.runLater(() -> {
            Node plot = chart.lookup(".plot-content");
            if (plot == null || Motion.isReduced()) return;
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(-10, -10, 0, 2000);
            plot.setClip(clip);
            javafx.animation.Timeline tl = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                    javafx.util.Duration.millis(720), new javafx.animation.KeyValue(clip.widthProperty(), plot.getBoundsInLocal().getMaxX() + 20, Motion.EASE_OUT)));
            tl.setDelay(javafx.util.Duration.millis(180));
            tl.setOnFinished(e -> plot.setClip(null));
            tl.play();
        });
        chart.setAccessibleText("Graphique de consommation moyenne mensuelle en litres aux 100 km");
        return chart;
    }

    private Node alertsCard(List<Alert> alerts) {
        Label t = new Label("Votre attention", Icons.stroke(Icons.BELL, 16, "-nf-text"));
        t.getStyleClass().addAll("headline");
        Label count = Ui.label(String.valueOf(alerts.size()), "count-chip");
        Button all = new Button("Tout voir", Icons.stroke(Icons.CHEVRON_RIGHT, 12, "-nf-green-strong"));
        all.getStyleClass().add("btn-ghost");
        all.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        all.setStyle("-fx-font-size: 11px;");
        all.setDisable(true);
        HBox head = new HBox(8, t, count, Ui.spacer(), wrapTooltip(all, "Centre de notifications : prévu au lot 3 (NF-24)"));
        head.setAlignment(Pos.CENTER_LEFT);
        VBox list = new VBox(2);
        for (Alert a : alerts) {
            String tone = a.severity();
            String icon = switch (a.kind()) {
                case MAINTENANCE -> Icons.WRENCH;
                case FUEL_LOW -> Icons.FUEL;
                case INSURANCE, LICENSE -> Icons.DOC;
                case ANOMALY -> Icons.BELL;
            };
            String stroke = switch (tone) {
                case "danger" -> "-nf-danger";
                case "warning" -> "-nf-warning";
                default -> "-nf-info";
            };
            Node bubble = Ui.symbolTile(icon, switch (tone) {
                case "danger" -> "tile-red";
                case "warning" -> "tile-orange";
                default -> "tile-blue";
            });
            VBox text = new VBox(2, Ui.label(a.title(), "subhead", "w-600"), Ui.label(a.detail(), "footnote", "muted"));
            Label due = Ui.label(a.due(), "due-" + tone);
            due.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            HBox row = new HBox(12, bubble, text, Ui.spacer(), due, Icons.stroke(Icons.CHEVRON_RIGHT, 14, "-nf-muted"));
            row.getStyleClass().add("alert-row");
            row.setAccessibleText(a.title() + ", " + a.detail() + ", " + a.due());
            list.getChildren().add(row);
        }
        if (alerts.isEmpty()) list.getChildren().add(Ui.label("Aucune alerte sur le périmètre.", "muted"));
        VBox card = new VBox(10, head, list);
        card.getStyleClass().add("card");
        return card;
    }

    // ------------------------------------------------------------------ derniers véhicules

    private Node recentTable(List<Vehicle> vehicles) {
        Label t = new Label("Derniers véhicules", Icons.stroke(Icons.CAR, 16, "-nf-text"));
        t.getStyleClass().addAll("headline");
        Button all = new Button("Voir tout", Icons.stroke(Icons.CHEVRON_RIGHT, 12, "-nf-green-strong"));
        all.getStyleClass().add("btn-ghost");
        all.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        all.setStyle("-fx-font-size: 11px;");
        all.setOnAction(e -> shell.show(AppShell.Section.VEHICLES));
        HBox head = new HBox(t, Ui.spacer(), all);
        head.setAlignment(Pos.CENTER_LEFT);
        TableView<Vehicle> table = VehicleTable.create(true);
        table.setItems(FXCollections.observableArrayList(vehicles));
        table.setFixedCellSize(48);
        table.setPrefHeight(48 * vehicles.size() + 40);
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<Vehicle> r = new javafx.scene.control.TableRow<>();
            r.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && r.getItem() != null) shell.openVehicles(r.getItem().registration());
            });
            return r;
        });
        VBox card = new VBox(8, head, table);
        card.getStyleClass().add("card");
        return card;
    }

    private Node footnote(DashboardData d) {
        String when = d.refreshedAt().atZone(java.time.ZoneId.of("Africa/Douala"))
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", FR));
        Label l = Ui.label((gateway.isDemo() ? "Maquette interactive · Données fictives · " : "")
                + "Actualisé le " + when + " (heure de Douala)", "muted", "t-12");
        HBox h = new HBox(Ui.spacer(), l);
        return h;
    }

    static Node wrapTooltip(Node disabledControl, String text) {
        // Un contrôle désactivé ne reçoit pas les survols : l'infobulle est portée par un conteneur.
        StackPane p = new StackPane(disabledControl);
        Tooltip.install(p, new Tooltip(text));
        return p;
    }

    static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
