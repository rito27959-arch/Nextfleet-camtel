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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

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
        root.getChildren().setAll(header(null), skeletonGrid());
        Ui.async(gateway::dashboard, this::render, this::fail);
    }

    private void fail(GatewayException e) {
        root.getChildren().setAll(header(null), Ui.errorBanner(e, this::load));
        if (e.kind() == GatewayException.Kind.SERVER) {
            root.getChildren().add(Ui.statePanel(Icons.CHART, "Indicateurs indisponibles",
                    "Aucune valeur n'est affichée tant que le serveur ne les fournit pas."));
        }
    }

    private Node header(DashboardData d) {
        String who = session.displayName().replaceAll("\\s*\\(.*\\)", "").toUpperCase(FR);
        Label overline = Ui.label(AppShell.greeting() + ", " + who, "overline");
        Label title = Ui.label("Votre flotte, en un regard.", "h1");
        Label sub = Ui.label("Disponibilité, coûts et alertes. L'essentiel, au même endroit.", "muted");
        VBox left = new VBox(4, overline, title, sub);

        String period = d == null ? "…" : capitalize(d.period().getMonth().getDisplayName(TextStyle.FULL, FR)) + " " + d.period().getYear();
        Button month = new Button(period, Icons.stroke(Icons.CALENDAR, 16, "-nf-muted"));
        month.getStyleClass().add("btn-secondary");
        month.setTooltip(new Tooltip("Période des indicateurs (mois en cours, fuseau Africa/Douala)"));
        Button export = new Button("Exporter", Icons.stroke(Icons.DOWNLOAD, 16, "white"));
        export.getStyleClass().add("btn-primary");
        export.setDisable(true);   // NF-25 non livré : bouton visible mais inactif, avec explication
        HBox actions = new HBox(10, month, wrapTooltip(export, "Exports PDF/XLSX : prévus au lot 3 (NF-25)"));
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox h = new HBox(left, Ui.spacer(), actions);
        h.setAlignment(Pos.BOTTOM_LEFT);
        return h;
    }

    private Node skeletonGrid() {
        HBox kpis = new HBox(16);
        for (int i = 0; i < 4; i++) {
            VBox c = new VBox(10, Ui.skeleton(120, 14), Ui.skeleton(90, 30), Ui.skeleton(140, 12));
            c.getStyleClass().add("card");
            HBox.setHgrow(c, Priority.ALWAYS);
            c.setMaxWidth(Double.MAX_VALUE);
            kpis.getChildren().add(c);
        }
        VBox big = new VBox(Ui.skeleton(-1, 220));
        big.getStyleClass().add("card");
        return new VBox(20, kpis, big);
    }

    private void render(DashboardData d) {
        root.getChildren().setAll(header(d), kpiRow(d), middleRow(d), recentTable(d.recentVehicles()), footnote(d));
        Motion.stagger(root.getChildren());
    }

    // ------------------------------------------------------------------ indicateurs

    private Node kpiRow(DashboardData d) {
        double ratio = d.totalVehicles() == 0 ? 0 : (double) d.activeVehicles() / d.totalVehicles();
        int pct = (int) Math.round(ratio * 100);

        // Carte sombre : véhicules actifs
        Label t1 = new Label("Véhicules actifs", Icons.stroke(Icons.CAR, 16, "white"));
        t1.getStyleClass().add("t-12");
        Label v1 = Ui.label("", "kpi-value");
        Motion.countUp(v1, d.activeVehicles(), x -> String.valueOf(Math.round(x)));
        Label u1 = Ui.label(" / " + d.totalVehicles(), "kpi-unit");
        HBox val1 = new HBox(v1, u1);
        val1.setAlignment(Pos.BASELINE_LEFT);
        Label n1 = Ui.label("Hors entretien et hors service", "muted", "t-12");
        VBox left1 = new VBox(8, t1, val1, n1);
        HBox c1 = new HBox(left1, Ui.spacer(), Ui.ring(ratio, pct + " %", 26));
        c1.setAlignment(Pos.CENTER_LEFT);
        c1.getStyleClass().add("card-dark");
        Motion.hoverLift(c1);
        c1.setAccessibleText(d.activeVehicles() + " véhicules actifs sur " + d.totalVehicles() + ", soit " + pct + " pour cent");

        Node c2 = kpi(Icons.WRENCH, "Entretiens à venir", d.upcomingMaintenance(), x -> String.valueOf(Math.round(x)), "",
                Ui.trend(BigDecimal.valueOf(d.maintenanceDelta()), true, "sur le mois dernier", ""),
                Ui.bubble(Icons.CALENDAR, "green", "-nf-green-strong"), "Échéances à 30 jours");
        Node c3 = kpi(Icons.FUEL, "Consommation moyenne", d.avgConsumption() == null ? Double.NaN : d.avgConsumption().doubleValue(),
                x -> Double.isNaN(x) ? "—" : Ui.decimal(BigDecimal.valueOf(x), 1), " L / 100 km",
                Ui.trend(d.consumptionTrendPercent(), true, "sur le mois dernier"),
                Ui.sparkline(d.consumptionSeries().stream().filter(m -> m.litersPer100Km() != null)
                        .map(m -> m.litersPer100Km().doubleValue()).toList(), 64, 30, "sparkline"), d.consumptionNote());
        Node c4 = kpi(Icons.COINS, "Coûts du mois", d.monthCostXaf() == null ? Double.NaN : d.monthCostXaf().doubleValue(),
                x -> Double.isNaN(x) ? "—" : Ui.compactXaf(BigDecimal.valueOf(x)), " FCFA",
                Ui.trend(d.costTrendPercent(), true, "sur le mois dernier"),
                Ui.bubble(Icons.BARS_UP, "danger", "-nf-danger"), "Carburant et entretien validés (données fictives)");

        HBox row = new HBox(16, c1, c2, c3, c4);
        for (Node n : row.getChildren()) {
            HBox.setHgrow(n, Priority.ALWAYS);
            ((javafx.scene.layout.Region) n).setMaxWidth(Double.MAX_VALUE);
            ((javafx.scene.layout.Region) n).setPrefWidth(250);
        }
        return row;
    }

    private Node kpi(String icon, String title, double target, java.util.function.DoubleFunction<String> format,
                     String unit, Node trend, Node bubble, String help) {
        Label t = new Label(title, Icons.stroke(icon, 16, "-nf-text"));
        t.getStyleClass().addAll("t-12", "w-500");
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
        t.getStyleClass().addAll("t-12", "w-600");
        Label v = Ui.label(Ui.decimal(d.avgConsumption(), 1), "kpi-value");
        Label u = Ui.label(" L / 100 km", "kpi-unit");
        HBox val = new HBox(v, u, new Label("  "), Ui.trend(d.consumptionTrendPercent(), true, "sur le mois dernier"));
        val.setAlignment(Pos.BASELINE_LEFT);

        ToggleGroup g = new ToggleGroup();
        HBox seg = new HBox();
        seg.getStyleClass().add("segmented");
        StackPane chartHolder = new StackPane();
        Label note = Ui.label("", "muted", "t-12");
        for (String[] r : new String[][]{{"3M", "3"}, {"6M", "6"}, {"1A", "12"}}) {
            ToggleButton b = new ToggleButton(r[0]);
            b.getStyleClass().add("segment");
            b.setToggleGroup(g);
            int months = Integer.parseInt(r[1]);
            b.setOnAction(e -> {
                if (!b.isSelected()) b.setSelected(true);
                List<MonthlyConsumption> s = d.consumptionSeries();
                List<MonthlyConsumption> view = s.subList(Math.max(0, s.size() - months), s.size());
                chartHolder.getChildren().setAll(chart(view));
                note.setText(months > s.size() ? "Historique disponible : " + s.size() + " mois." : qualityNote(view));
            });
            b.setAccessibleText("Période " + r[0]);
            seg.getChildren().add(b);
        }
        HBox head = new HBox(t, Ui.spacer(), seg);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(6, head, val, chartHolder, note);
        card.getStyleClass().add("card");
        ((ToggleButton) seg.getChildren().get(1)).fire();   // 6M par défaut (données de démonstration : 6 mois)
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
        t.getStyleClass().addAll("t-12", "w-600");
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
            Node bubble = Ui.bubble(icon, tone, stroke);
            VBox text = new VBox(2, Ui.label(a.title(), "t-12", "w-600"), Ui.label(a.detail(), "muted", "t-12"));
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
        t.getStyleClass().addAll("t-12", "w-600");
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
