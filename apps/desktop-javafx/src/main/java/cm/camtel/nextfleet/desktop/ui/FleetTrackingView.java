package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Ops;
import cm.camtel.nextfleet.desktop.map.TileMapView;
import cm.camtel.nextfleet.desktop.map.TileSource;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Suivi GPS de la flotte : liste des véhicules en mission, carte en direct, panneau de mission. Les positions
 * arrivent par le canal temps réel du serveur (déjà filtré par droits) ; l'état (en mouvement, à l'arrêt, signal
 * ancien, à vérifier) est celui calculé par le serveur et se dégrade seul quand le signal vieillit. La couleur est
 * toujours doublée d'un libellé ; une perte de signal n'est jamais présentée comme une panne ou une faute.
 */
public final class FleetTrackingView extends HBox {

    private static volatile TileSource tiles;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE).withZone(ZoneId.of("Africa/Douala"));

    private final AppShell shell;
    private final TileMapView map;
    private final VBox list = new VBox(4);
    private final VBox panel = new VBox(14);
    private final Label connection = Ui.label("Connexion…", "live-chip");
    private final TextField search = new TextField();
    private final Map<String, Ops.VehicleLive> vehicles = new LinkedHashMap<>();
    private String selected;
    private boolean fitted;
    private AutoCloseable subscription;
    private javafx.animation.Timeline refresh;

    /** Source de tuiles partagée (paramètre --tiles= ou NF_TILE_URL). */
    public static void configureTiles(String override) {
        tiles = TileSource.fromEnvironment(override);
    }

    static TileSource tiles() {
        if (tiles == null) configureTiles(null);
        return tiles;
    }

    public FleetTrackingView(AppShell shell) {
        this.shell = shell;
        getStyleClass().addAll("content", "tracking");
        setSpacing(16);
        map = new TileMapView(tiles());

        search.setPromptText("Immatriculation, conducteur, mission");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((o, a, b) -> renderList());
        VBox titles = new VBox(4, Ui.label("Suivi GPS", "large-title"), connection);
        ScrollPane listScroll = new ScrollPane(list);
        listScroll.setFitToWidth(true);
        listScroll.getStyleClass().add("split-list");
        VBox.setVgrow(listScroll, Priority.ALWAYS);
        VBox left = new VBox(14, titles, search, listScroll);
        left.setPrefWidth(300);
        left.setMinWidth(260);

        StackPane center = new StackPane(map, legend());
        HBox.setHgrow(center, Priority.ALWAYS);
        center.setMinWidth(360);

        panel.getStyleClass().add("tracking-panel");
        panel.setPrefWidth(320);
        panel.setMinWidth(280);
        ScrollPane panelScroll = new ScrollPane(panel);
        panelScroll.setFitToWidth(true);
        panelScroll.getStyleClass().add("split-list");

        getChildren().addAll(left, center, panelScroll);
        showEmptyPanel();
        if (shell.gateway().isDemo()) {
            connection.setText("Démonstration · positions simulées");
            setChip("live-chip-demo");
        }
        load();

        sceneProperty().addListener((o, a, s) -> {
            if (s == null) stop();
        });
    }

    private Node legend() {
        HBox row = new HBox(8);
        for (Ops.LiveState st : Ops.LiveState.values()) {
            Circle c = new Circle(5);
            c.getStyleClass().addAll("live-dot-shape", st.styleClass);
            HBox chip = new HBox(6, c, Ui.label(st.label, "caption1"));
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.getStyleClass().add("map-chip");
            row.getChildren().add(chip);
        }
        row.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        row.setMouseTransparent(true);
        StackPane.setAlignment(row, Pos.BOTTOM_CENTER);
        StackPane.setMargin(row, new Insets(0, 0, 40, 0));
        return row;
    }

    // ------------------------------------------------------------------ données

    private void load() {
        Ui.async(() -> shell.gateway().fleetLive(), this::apply, err -> {
            list.getChildren().setAll(Ui.errorBanner(err, this::load));
            connection.setText("Indisponible");
            setChip("live-chip-off");
        });
    }

    private void apply(List<Ops.VehicleLive> data) {
        Set<String> keep = new HashSet<>();
        for (Ops.VehicleLive v : data) {
            vehicles.put(v.vehicleId(), v);
            keep.add(v.vehicleId());
        }
        vehicles.keySet().retainAll(keep);
        renderList();
        renderMarkers(false);
        if (!fitted && !vehicles.isEmpty()) {
            fitted = true;
            map.fit(vehicles.values().stream().map(v -> new double[]{v.lat(), v.lon()}).toList(), 80);
        }
        if (selected == null && !vehicles.isEmpty()) select(vehicles.keySet().iterator().next(), false);
        else if (selected != null && vehicles.containsKey(selected)) renderPanel(vehicles.get(selected));
        if (subscription == null) startLive();
    }

    private void startLive() {
        subscription = shell.gateway().subscribeLive(e -> Platform.runLater(() -> onEvent(e)), up -> Platform.runLater(() -> {
            if (shell.gateway().isDemo()) {
                connection.setText(up ? "Démonstration · positions simulées" : "Démonstration interrompue");
                setChip(up ? "live-chip-demo" : "live-chip-off");
            } else {
                connection.setText(up ? "En direct" : "Reconnexion au serveur…");
                setChip(up ? "live-chip-on" : "live-chip-off");
            }
        }));
        // les états (signal ancien, mission terminée) évoluent même sans nouvelle position
        refresh = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(30), ev -> load()));
        refresh.setCycleCount(javafx.animation.Animation.INDEFINITE);
        refresh.play();
    }

    private void setChip(String cls) {
        connection.getStyleClass().removeAll("live-chip-on", "live-chip-off", "live-chip-demo");
        connection.getStyleClass().add(cls);
    }

    private void onEvent(Ops.LiveEvent e) {
        Ops.VehicleLive v = vehicles.get(e.vehicleId());
        if (v == null) {   // nouveau véhicule en mission : la carte complète arrive par l'API
            load();
            return;
        }
        long age = e.recordedAt() == null ? 0 : Math.max(0, Duration.between(e.recordedAt(), Instant.now()).getSeconds());
        Ops.LiveState st = v.openAnomalies() > 0 ? Ops.LiveState.A_VERIFIER
                : age > 120 ? Ops.LiveState.SIGNAL_ANCIEN
                : e.speedKmh() != null && e.speedKmh() >= 5.4 ? Ops.LiveState.EN_MOUVEMENT : Ops.LiveState.ARRET;
        Ops.VehicleLive nv = new Ops.VehicleLive(v.vehicleId(), v.registration(), v.vehicleLabel(), v.category(), v.missionId(),
                v.missionRef(), v.missionStatus(), v.driverName(), v.origin(), v.destination(), e.lat(), e.lon(), v.accuracyM(),
                e.speedKmh(), v.bearingDeg(), e.recordedAt(), age, st, st.label, v.startedAt(), v.distanceKm(), v.openAnomalies());
        vehicles.put(v.vehicleId(), nv);
        map.putMarker(nv.vehicleId(), nv.lat(), nv.lon(), marker(nv), true);
        renderList();
        if (nv.vehicleId().equals(selected)) {
            renderPanel(nv);
            trackCache.computeIfPresent(nv.vehicleId(), (k, pts) -> {
                List<double[]> copy = new ArrayList<>(pts);
                copy.add(new double[]{nv.lat(), nv.lon()});
                map.setTrack(copy);
                return copy;
            });
        }
    }

    private void stop() {
        if (refresh != null) refresh.stop();
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // déjà fermé
            }
            subscription = null;
        }
    }

    // ------------------------------------------------------------------ rendu

    private void renderList() {
        String q = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        list.getChildren().clear();
        if (vehicles.isEmpty()) {
            list.getChildren().add(Ui.statePanel(Icons.LOCATION, "Aucun véhicule en mission",
                    "Les positions n'existent que pendant une mission démarrée par son conducteur."));
            return;
        }
        for (Ops.VehicleLive v : vehicles.values()) {
            String hay = (v.registration() + " " + v.driverName() + " " + v.missionRef() + " " + v.destination()).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            Circle dot = new Circle(5);
            dot.getStyleClass().addAll("live-dot-shape", v.state().styleClass);
            Label reg = Ui.label(v.registration(), "headline");
            Label sub = Ui.label(v.driverName() + " · " + v.missionRef(), "footnote", "muted");
            Label state = Ui.label(v.stateLabel() + (v.speedKmh() != null && v.state() == Ops.LiveState.EN_MOUVEMENT
                    ? " · " + Math.round(v.speedKmh()) + " km/h" : ""), "caption2", v.state().styleClass + "-text");
            VBox text = new VBox(2, reg, sub, state);
            HBox row = new HBox(12, dot, text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("tracking-row");
            if (v.vehicleId().equals(selected)) row.getStyleClass().add("tracking-row-selected");
            row.setOnMouseClicked(ev -> select(v.vehicleId(), true));
            row.setAccessibleText(v.registration() + ", " + v.stateLabel());
            list.getChildren().add(row);
        }
    }

    private void renderMarkers(boolean animate) {
        for (Ops.VehicleLive v : vehicles.values()) map.putMarker(v.vehicleId(), v.lat(), v.lon(), marker(v), animate);
        map.removeMarkersExcept(vehicles.keySet());
    }

    private Node marker(Ops.VehicleLive v) {
        Circle halo = new Circle(14);
        halo.getStyleClass().addAll("live-halo", v.state().styleClass);
        Circle dot = new Circle(7);
        dot.getStyleClass().addAll("live-marker", v.state().styleClass);
        StackPane pin = new StackPane(halo, dot);
        if (v.state() == Ops.LiveState.EN_MOUVEMENT && v.bearingDeg() != null) {
            // flèche de cap hors du point, tournée autour du centre du marqueur
            SVGPath arrow = new SVGPath();
            arrow.setContent("M0 -21 L5 -14 L-5 -14 Z");
            arrow.getStyleClass().addAll("live-arrow", v.state().styleClass);
            Circle bounds = new Circle(21);
            bounds.setFill(javafx.scene.paint.Color.TRANSPARENT);
            javafx.scene.Group heading = new javafx.scene.Group(bounds, arrow);
            heading.setRotate(v.bearingDeg());
            pin.getChildren().add(heading);
        }
        Label tag = Ui.label(v.registration(), "live-tag");
        VBox box = new VBox(2, pin, tag);
        box.setAlignment(Pos.CENTER);
        box.setPickOnBounds(false);
        if (v.vehicleId().equals(selected)) box.getStyleClass().add("live-selected");
        box.setOnMouseClicked(e -> select(v.vehicleId(), false));
        box.setAccessibleText(v.registration() + " : " + v.stateLabel());
        return box;
    }

    private final Map<String, List<double[]>> trackCache = new LinkedHashMap<>();

    private void select(String id, boolean center) {
        selected = id;
        Ops.VehicleLive v = vehicles.get(id);
        if (v == null) return;
        renderList();
        renderMarkers(false);
        renderPanel(v);
        if (center) map.setCenter(v.lat(), v.lon());
        try {
            java.util.UUID mission = java.util.UUID.fromString(v.missionId());
            Ui.async(() -> shell.gateway().missionTrack(mission), fixes -> {
                if (!id.equals(selected)) return;
                List<double[]> pts = fixes.stream().map(f -> new double[]{f.latitude(), f.longitude()}).toList();
                trackCache.put(id, pts);
                map.setTrack(pts);
            }, err -> map.setTrack(List.of()));
        } catch (IllegalArgumentException e) {
            map.setTrack(List.of());
        }
    }

    private void showEmptyPanel() {
        panel.getChildren().setAll(Ui.statePanel(Icons.ROUTE, "Sélectionnez un véhicule", "Sa mission, sa vitesse et son dernier signal s'affichent ici."));
    }

    private void renderPanel(Ops.VehicleLive v) {
        Label pill = Ui.label(v.stateLabel(), "pill", v.state().styleClass + "-pill");
        VBox head = new VBox(6, Ui.label(v.registration(), "title2"), Ui.label(v.vehicleLabel() + (v.category().isBlank() ? "" : " · " + v.category()),
                "body", "muted"), pill);
        List<Node> rows = new ArrayList<>();
        rows.add(Ui.listRow(Ui.symbolTile(Icons.ROUTE, "tile-navy"), "Mission", v.missionRef(), null, null));
        rows.add(Ui.listRow(Ui.symbolTile(Icons.USER, "tile-blue"), "Conducteur", v.driverName(), null, null));
        rows.add(Ui.listRow(Ui.symbolTile(Icons.MAP_PIN, "tile-mint"), "Destination", v.destination(), null, null));
        rows.add(Ui.listRow(Ui.symbolTile(Icons.GAUGE, "tile-indigo"), "Vitesse",
                v.speedKmh() == null ? "non mesurée" : Math.round(v.speedKmh()) + " km/h", null, null));
        rows.add(Ui.listRow(Ui.symbolTile(Icons.CHART, "tile-green"), "Distance (trace GPS)",
                v.distanceKm() == null ? "—" : String.format(Locale.FRANCE, "%.1f km", v.distanceKm()), null, null));
        if (v.startedAt() != null) {
            long min = Duration.between(v.startedAt(), "FINISHED".equals(v.missionStatus()) && v.recordedAt() != null ? v.recordedAt() : Instant.now()).toMinutes();
            rows.add(Ui.listRow(Ui.symbolTile(Icons.CLOCK, "tile-orange"), "Durée", (min / 60) + " h " + String.format("%02d", min % 60)
                    + " · départ " + HM.format(v.startedAt()), null, null));
        }
        rows.add(Ui.listRow(Ui.symbolTile(Icons.LOCATION, "tile-gray"), "Dernier signal",
                (v.recordedAt() == null ? "—" : Ui.relativeTime(v.recordedAt(), Instant.now()) + " · ± " + Math.round(v.accuracyM()) + " m"), null, null));
        if (v.openAnomalies() > 0) {
            rows.add(Ui.listRow(Ui.symbolTile(Icons.SHIELD, "tile-orange"), "À vérifier",
                    v.openAnomalies() + (v.openAnomalies() > 1 ? " constats ouverts" : " constat ouvert"), null, null));
        }
        VBox group = Ui.insetGroup(null, rows, v.state() == Ops.LiveState.SIGNAL_ANCIEN && !"FINISHED".equals(v.missionStatus())
                ? "Signal interrompu : zone sans réseau, téléphone éteint ou application fermée. Ce n'est pas, en soi, une anomalie."
                : "Positions collectées uniquement pendant la mission, avec l'accord du conducteur.");
        Button mission = new Button("Voir la mission");
        mission.getStyleClass().addAll("btn-secondary", "btn-small");
        mission.setOnAction(e -> shell.show(AppShell.Section.MISSIONS));
        Button recenter = new Button("Centrer");
        recenter.getStyleClass().addAll("btn-secondary", "btn-small");
        recenter.setOnAction(e -> map.setCenter(v.lat(), v.lon()));
        HBox actions = new HBox(8, recenter, mission);
        panel.getChildren().setAll(head, group, actions);
    }
}
