package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.domain.gps.GpsFix;
import cm.camtel.nextfleet.domain.gps.TrackAnalyzer;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracé d'une mission sur fond neutre quadrillé (projection équirectangulaire corrigée de la latitude).
 * Aucun fond cartographique n'est chargé : le serveur de tuiles (interne ou OpenStreetMap selon licence) est une
 * décision DSIR ; la carte l'indique au lieu d'afficher une fausse géographie.
 * Repères : départ (anneau vert), position en direct (point bleu qui respire) ou arrivée (point bleu nuit),
 * arrêts détectés (orange), échelle en kilomètres, nord.
 */
final class TrackMap extends StackPane {

    private final Pane canvas = new Pane();
    private final Label status = Ui.label("", "map-chip");
    private final Region scaleBar = new Region();
    private final Label scaleLabel = Ui.label("", "map-note");
    private List<GpsFix> fixes = List.of();
    private List<TrackAnalyzer.Stop> stops = List.of();
    private boolean live;
    private boolean animateNext = true;
    private Animation pulse;

    TrackMap() {
        getStyleClass().add("map-card");
        setMinHeight(300);
        Rectangle clip = new Rectangle();
        clip.setArcWidth(32);
        clip.setArcHeight(32);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        canvas.setManaged(false);   // le tracé suit la taille de la carte sans jamais l'agrandir

        scaleBar.getStyleClass().add("map-scale-bar");
        VBox scale = new VBox(4, scaleLabel, scaleBar);
        scale.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(scale, Pos.BOTTOM_LEFT);
        StackPane.setMargin(scale, new Insets(0, 0, 16, 18));

        Label north = Ui.label("N", "map-chip");
        StackPane.setAlignment(north, Pos.TOP_RIGHT);
        StackPane.setMargin(north, new Insets(14, 14, 0, 0));
        StackPane.setAlignment(status, Pos.TOP_LEFT);
        StackPane.setMargin(status, new Insets(14, 0, 0, 14));

        Label note = Ui.label("Fond de carte non chargé · serveur de tuiles à désigner par la DSIR", "map-note");
        StackPane.setAlignment(note, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(note, new Insets(0, 16, 14, 0));

        getChildren().addAll(canvas, scale, north, status, note);
        widthProperty().addListener((o, a, b) -> redraw(false));
        heightProperty().addListener((o, a, b) -> redraw(false));
        setAccessibleText("Carte du trajet de la mission");
    }

    void setTrack(List<GpsFix> fixes, List<TrackAnalyzer.Stop> stops, boolean live, String statusText) {
        boolean first = this.fixes.isEmpty() && animateNext;
        this.fixes = List.copyOf(fixes);
        this.stops = List.copyOf(stops);
        this.live = live;
        status.setText(statusText);
        status.setGraphic(live ? liveDot() : null);
        status.setGraphicTextGap(6);
        redraw(first && !fixes.isEmpty());
        if (!fixes.isEmpty()) animateNext = false;
    }

    private static Circle liveDot() {
        Circle c = new Circle(4);
        c.getStyleClass().add("live-dot");
        Motion.pulse(c, 0.35, Duration.millis(1200));
        return c;
    }

    private void redraw(boolean animate) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.resize(w, h);
        if (pulse != null) pulse.stop();
        canvas.getChildren().clear();
        for (double x = 0; x < w; x += 44) canvas.getChildren().add(gridLine(x, 0, x, h));
        for (double y = 0; y < h; y += 44) canvas.getChildren().add(gridLine(0, y, w, y));
        if (fixes.isEmpty()) {
            Label empty = Ui.label("Aucune position · la collecte ne commence qu'au démarrage de la mission", "map-note");
            empty.layoutXProperty().bind(widthProperty().subtract(empty.widthProperty()).divide(2));
            empty.layoutYProperty().bind(heightProperty().divide(2).subtract(8));
            canvas.getChildren().add(empty);
            scaleLabel.setText("");
            scaleBar.setPrefWidth(0);
            return;
        }
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (GpsFix f : fixes) {
            minLat = Math.min(minLat, f.latitude());
            maxLat = Math.max(maxLat, f.latitude());
            minLon = Math.min(minLon, f.longitude());
            maxLon = Math.max(maxLon, f.longitude());
        }
        double k = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        // emprise minimale ≈ 1 km : un trajet qui commence n'est pas zoomé à l'absurde
        double spanX = Math.max(0.009, (maxLon - minLon) * k);
        double spanY = Math.max(0.009, maxLat - minLat);
        minLon -= (spanX / k - (maxLon - minLon)) / 2;
        maxLat += (spanY - (maxLat - minLat)) / 2;
        double pad = 64;
        double scale = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
        double offX = (w - spanX * scale) / 2;
        double offY = (h - spanY * scale) / 2;
        final double fMinLon = minLon, fMaxLat = maxLat;
        java.util.function.BiFunction<Double, Double, double[]> project = (lat, lon) ->
                new double[]{offX + (lon - fMinLon) * k * scale, offY + (fMaxLat - lat) * scale};

        Polyline halo = new Polyline();
        Polyline line = new Polyline();
        halo.getStyleClass().add("track-halo");
        line.getStyleClass().add("track-line");
        List<Double> pts = new ArrayList<>();
        double length = 0;
        double[] prev = null;
        for (GpsFix f : fixes) {
            double[] p = project.apply(f.latitude(), f.longitude());
            pts.add(p[0]);
            pts.add(p[1]);
            if (prev != null) length += Math.hypot(p[0] - prev[0], p[1] - prev[1]);
            prev = p;
        }
        halo.getPoints().setAll(pts);
        line.getPoints().setAll(pts);
        canvas.getChildren().addAll(halo, line);
        if (animate && !Motion.isReduced() && length > 0) {
            for (Polyline pl : List.of(halo, line)) {
                pl.getStrokeDashArray().setAll(length, length);
                pl.setStrokeDashOffset(length);
                new Timeline(new KeyFrame(Duration.millis(1100), new KeyValue(pl.strokeDashOffsetProperty(), 0, Motion.EASE_OUT))).play();
            }
        }

        for (TrackAnalyzer.Stop s : stops) {
            double[] p = project.apply(s.latitude(), s.longitude());
            Circle c = new Circle(p[0], p[1], 6);
            c.getStyleClass().add("marker-stop");
            javafx.scene.control.Tooltip.install(c, new javafx.scene.control.Tooltip("Arrêt de " + s.duration().toMinutes() + " min"));
            canvas.getChildren().add(c);
        }
        double[] a = project.apply(fixes.get(0).latitude(), fixes.get(0).longitude());
        Circle start = new Circle(a[0], a[1], 7);
        start.getStyleClass().add("marker-start");
        canvas.getChildren().add(start);
        GpsFix lastFix = fixes.get(fixes.size() - 1);
        double[] b = project.apply(lastFix.latitude(), lastFix.longitude());
        if (live) {
            Circle haloDot = new Circle(b[0], b[1], 18);
            haloDot.getStyleClass().add("marker-live-halo");
            Circle dot = new Circle(b[0], b[1], 8);
            dot.getStyleClass().add("marker-live");
            canvas.getChildren().addAll(haloDot, dot);
            if (!Motion.isReduced()) {
                pulse = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(haloDot.radiusProperty(), 10), new KeyValue(haloDot.opacityProperty(), 0.9)),
                        new KeyFrame(Duration.millis(1600), new KeyValue(haloDot.radiusProperty(), 24, Motion.EASE_OUT),
                                new KeyValue(haloDot.opacityProperty(), 0)));
                pulse.setCycleCount(Animation.INDEFINITE);
                pulse.play();
            }
        } else {
            Circle end = new Circle(b[0], b[1], 7);
            end.getStyleClass().add("marker-end");
            canvas.getChildren().add(end);
        }

        // échelle : 1, 2, 5, 10, 20 km… autour de 110 px
        double metersPerPx = 1 / scale * 111_320;
        double target = 110 * metersPerPx;
        double[] nice = {200, 500, 1000, 2000, 5000, 10_000, 20_000, 50_000};
        double chosen = nice[0];
        for (double n : nice) if (n <= target) chosen = n;
        scaleBar.setPrefWidth(chosen / metersPerPx);
        scaleBar.setMinWidth(chosen / metersPerPx);
        scaleLabel.setText(chosen >= 1000 ? (int) (chosen / 1000) + " km" : (int) chosen + " m");
    }

    private static Line gridLine(double x1, double y1, double x2, double y2) {
        Line l = new Line(x1, y1, x2, y2);
        l.getStyleClass().add("map-grid");
        return l;
    }

    /** Ligne d'information sous la carte (utilisée par les vues Missions et Mes missions). */
    static HBox legend() {
        HBox h = new HBox(16, item("marker-start", "Départ"), item("marker-live", "Position en direct"),
                item("marker-end", "Arrivée"), item("marker-stop", "Arrêt ≥ 5 min"));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static HBox item(String cls, String text) {
        Circle c = new Circle(5);
        c.getStyleClass().add(cls);
        HBox h = new HBox(6, c, Ui.label(text, "footnote", "muted"));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }
}
