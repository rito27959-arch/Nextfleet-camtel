package cm.camtel.nextfleet.desktop.map;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Carte à tuiles native JavaFX (déplacement à la souris, molette centrée sur le pointeur, double-clic pour zoomer,
 * boutons +/−, échelle, attribution). JavaFX WebView n'ayant pas WebGL, MapLibre GL JS ne peut pas tourner dans le
 * client bureau : cette vue utilise la même projection (Web Mercator) et peut pointer sur le même serveur de tuiles
 * que l'application Android MapLibre.
 */
public final class TileMapView extends StackPane {

    /** Marqueur positionné par sa latitude/longitude ; le nœud est centré sur le point. */
    public static final class Marker {
        final Node node;
        final DoubleProperty lat = new SimpleDoubleProperty();
        final DoubleProperty lon = new SimpleDoubleProperty();

        Marker(Node node, double lat, double lon) {
            this.node = node;
            this.lat.set(lat);
            this.lon.set(lon);
        }

        public Node node() {
            return node;
        }
    }

    private final TileSource source;
    private final Pane fallback = new Pane();
    private final Pane tiles = new Pane();
    /** Repères affichés SOUS les tuiles : visibles seulement tant que le fond de carte n'est pas chargé (hors ligne). */
    private static final Object[][] PLACES = {
            {"Yaoundé", 3.8480, 11.5021}, {"Douala", 4.0511, 9.7679}, {"Kribi", 2.9406, 9.9100}, {"Mbalmayo", 3.5172, 11.5003},
            {"Bafoussam", 5.4778, 10.4176}, {"Bamenda", 5.9631, 10.1591}, {"Garoua", 9.3014, 13.3977}, {"Maroua", 10.5956, 14.3247},
            {"Ngaoundéré", 7.3277, 13.5847}, {"Bertoua", 4.5774, 13.6846}, {"Ebolowa", 2.9000, 11.1500}, {"Buéa", 4.1560, 9.2410},
            {"Limbé", 4.0167, 9.2000}, {"Edéa", 3.8000, 10.1333}, {"Obala", 4.1667, 11.5333}, {"Soa", 3.9833, 11.5958},
            {"Dschang", 5.4440, 10.0530}, {"Nkongsamba", 4.9547, 9.9404}, {"Campo", 2.3667, 9.8167}};
    private final Pane overlay = new Pane();
    private final Polyline track = new Polyline();
    private final Map<String, ImageView> tileViews = new HashMap<>();
    private final Map<String, Marker> markers = new LinkedHashMap<>();
    private final Label attribution;
    private final Label scaleLabel = new Label();
    private final Region scaleBar = new Region();
    private final Label offlineBadge = new Label("Hors ligne : fond de carte partiel");
    private List<double[]> trackPoints = List.of();
    private int zoom = 12;
    private double centerX;   // pixels monde au zoom courant
    private double centerY;
    private double dragX;
    private double dragY;
    private boolean dragged;

    public TileMapView(TileSource source) {
        this.source = source;
        getStyleClass().add("tile-map");
        setMinSize(200, 200);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        setClip(clip);
        tiles.setManaged(false);
        fallback.setManaged(false);
        fallback.setMouseTransparent(true);
        overlay.setManaged(false);
        track.getStyleClass().add("map-track");
        track.setMouseTransparent(true);
        overlay.getChildren().add(track);

        attribution = new Label(source.attribution());
        attribution.getStyleClass().add("map-attribution");
        StackPane.setAlignment(attribution, Pos.BOTTOM_RIGHT);

        scaleBar.getStyleClass().add("map-scale-bar");
        scaleLabel.getStyleClass().add("map-note");
        VBox scale = new VBox(3, scaleLabel, scaleBar);
        scale.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        scale.setMouseTransparent(true);
        StackPane.setAlignment(scale, Pos.BOTTOM_LEFT);
        StackPane.setMargin(scale, new Insets(0, 0, 14, 16));

        Button plus = zoomButton("+", "Zoomer", 1);
        Button minus = zoomButton("−", "Dézoomer", -1);
        VBox zoomBox = new VBox(plus, minus);
        zoomBox.getStyleClass().add("map-zoom");
        zoomBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(zoomBox, Pos.TOP_RIGHT);
        StackPane.setMargin(zoomBox, new Insets(14));

        offlineBadge.getStyleClass().add("map-chip");
        offlineBadge.setVisible(false);
        StackPane.setAlignment(offlineBadge, Pos.TOP_LEFT);
        StackPane.setMargin(offlineBadge, new Insets(14));

        getChildren().addAll(fallback, tiles, overlay, scale, attribution, zoomBox, offlineBadge);
        setCenter(3.8667, 11.5167);   // Yaoundé, en attendant les données

        setOnMousePressed(e -> {
            dragX = e.getX();
            dragY = e.getY();
            dragged = false;
        });
        setOnMouseDragged(e -> {
            centerX -= e.getX() - dragX;
            centerY -= e.getY() - dragY;
            dragX = e.getX();
            dragY = e.getY();
            dragged = true;
            relayout();
        });
        setOnScroll(e -> {
            if (e.getDeltaY() == 0) return;
            zoomAround(e.getDeltaY() > 0 ? 1 : -1, e.getX(), e.getY());
        });
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !dragged) zoomAround(1, e.getX(), e.getY());
        });
        widthProperty().addListener((o, a, b) -> relayout());
        heightProperty().addListener((o, a, b) -> relayout());
        setFocusTraversable(true);
        setAccessibleText("Carte : glisser pour déplacer, molette ou double-clic pour zoomer");
    }

    private Button zoomButton(String text, String help, int delta) {
        Button b = new Button(text);
        b.getStyleClass().add("map-zoom-button");
        b.setAccessibleText(help);
        b.setOnAction(e -> zoomAround(delta, getWidth() / 2, getHeight() / 2));
        return b;
    }

    // ------------------------------------------------------------------ vue

    public int zoom() {
        return zoom;
    }

    public double centerLat() {
        return WebMercator.yToLat(centerY, zoom);
    }

    public double centerLon() {
        return WebMercator.xToLon(centerX, zoom);
    }

    public void setCenter(double lat, double lon) {
        centerX = WebMercator.lonToX(lon, zoom);
        centerY = WebMercator.latToY(lat, zoom);
        relayout();
    }

    public void setZoom(int z) {
        zoomAround(z - zoom, getWidth() / 2, getHeight() / 2);
    }

    void zoomAround(int delta, double px, double py) {
        int nz = Math.max(2, Math.min(source.maxZoom(), zoom + delta));
        if (nz == zoom) return;
        double w = getWidth() > 0 ? getWidth() : 800;
        double h = getHeight() > 0 ? getHeight() : 600;
        double wx = centerX - w / 2 + px;   // point sous le pointeur, en pixels monde
        double wy = centerY - h / 2 + py;
        double k = Math.pow(2, nz - zoom);
        centerX = wx * k - px + w / 2;
        centerY = wy * k - py + h / 2;
        zoom = nz;
        relayout();
    }

    /** Cadre la vue sur des points (latitude, longitude) avec une marge ; un seul point → zoom de rue. */
    public void fit(List<double[]> points, double padding) {
        if (points.isEmpty()) return;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (double[] p : points) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        double w = getWidth() > 0 ? getWidth() : 900;
        double h = getHeight() > 0 ? getHeight() : 600;
        zoom = points.size() == 1 || (maxLat - minLat < 1e-4 && maxLon - minLon < 1e-4) ? 15
                : Math.min(16, WebMercator.fitZoom(minLat, minLon, maxLat, maxLon, w, h, padding, source.maxZoom()));
        setCenter((minLat + maxLat) / 2, (minLon + maxLon) / 2);
    }

    // ------------------------------------------------------------------ marqueurs et trace

    public Marker putMarker(String id, double lat, double lon, Node node, boolean animate) {
        Marker m = markers.get(id);
        if (m == null) {
            m = new Marker(node, lat, lon);
            markers.put(id, m);
            overlay.getChildren().add(node);
            Marker fm = m;
            m.lat.addListener((o, a, b) -> place(fm));
            m.lon.addListener((o, a, b) -> place(fm));
            place(m);
            return m;
        }
        if (m.node != node) {
            overlay.getChildren().remove(m.node);
            Marker nm = new Marker(node, m.lat.get(), m.lon.get());
            markers.put(id, nm);
            overlay.getChildren().add(node);
            nm.lat.addListener((o, a, b) -> place(nm));
            nm.lon.addListener((o, a, b) -> place(nm));
            m = nm;
        }
        if (animate && !cm.camtel.nextfleet.desktop.ui.Motion.isReduced()) {
            new Timeline(new KeyFrame(Duration.millis(900),
                    new KeyValue(m.lat, lat, Interpolator.EASE_BOTH), new KeyValue(m.lon, lon, Interpolator.EASE_BOTH))).play();
        } else {
            m.lat.set(lat);
            m.lon.set(lon);
        }
        place(m);
        return m;
    }

    public void removeMarkersExcept(Set<String> keep) {
        for (var it = markers.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!keep.contains(e.getKey())) {
                overlay.getChildren().remove(e.getValue().node);
                it.remove();
            }
        }
    }

    public void setTrack(List<double[]> points) {
        trackPoints = List.copyOf(points);
        relayout();
    }

    // ------------------------------------------------------------------ rendu

    private double originX() {
        return centerX - getWidth() / 2;
    }

    private double originY() {
        return centerY - getHeight() / 2;
    }

    private void place(Marker m) {
        double x = WebMercator.lonToX(m.lon.get(), zoom) - originX();
        double y = WebMercator.latToY(m.lat.get(), zoom) - originY();
        m.node.autosize();
        m.node.relocate(x - m.node.prefWidth(-1) / 2, y - m.node.prefHeight(-1) / 2);
    }

    void relayout() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;
        double world = WebMercator.worldSize(zoom);
        centerY = Math.max(h / 2, Math.min(world - h / 2, centerY));   // pas de vide au-delà des pôles
        double ox = originX();
        double oy = originY();
        int n = 1 << zoom;
        int x0 = (int) Math.floor(ox / WebMercator.TILE);
        int y0 = (int) Math.floor(oy / WebMercator.TILE);
        int x1 = (int) Math.floor((ox + w) / WebMercator.TILE);
        int y1 = (int) Math.floor((oy + h) / WebMercator.TILE);
        Set<String> wanted = new HashSet<>();
        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = Math.max(0, y0); ty <= Math.min(n - 1, y1); ty++) {
                int wx = Math.floorMod(tx, n);   // la longitude boucle
                String key = zoom + "/" + tx + "/" + ty;
                wanted.add(key);
                ImageView iv = tileViews.get(key);
                if (iv == null) {
                    iv = new ImageView();
                    iv.setFitWidth(WebMercator.TILE);
                    iv.setFitHeight(WebMercator.TILE);
                    iv.getStyleClass().add("map-tile");
                    tileViews.put(key, iv);
                    tiles.getChildren().add(iv);
                    Image cached = source.cached(zoom, wx, ty);
                    if (cached != null) {
                        iv.setImage(cached);
                    } else {
                        ImageView target = iv;
                        int z = zoom;
                        source.load(z, wx, ty).whenComplete((img, err) -> Platform.runLater(() -> {
                            if (img != null) target.setImage(img);
                            offlineBadge.setVisible(source.offline());
                        }));
                    }
                }
                iv.relocate(tx * WebMercator.TILE - ox, ty * WebMercator.TILE - oy);
            }
        }
        tileViews.entrySet().removeIf(e -> {
            if (wanted.contains(e.getKey())) return false;
            tiles.getChildren().remove(e.getValue());
            return true;
        });
        Double[] pts = new Double[trackPoints.size() * 2];
        for (int i = 0; i < trackPoints.size(); i++) {
            pts[2 * i] = WebMercator.lonToX(trackPoints.get(i)[1], zoom) - ox;
            pts[2 * i + 1] = WebMercator.latToY(trackPoints.get(i)[0], zoom) - oy;
        }
        track.getPoints().setAll(pts);
        markers.values().forEach(this::place);
        drawFallback(ox, oy, w, h);
        updateScale();
    }

    /** Quadrillage géographique et villes de référence, sous les tuiles (contexte quand le fond est absent). */
    private void drawFallback(double ox, double oy, double w, double h) {
        fallback.getChildren().clear();
        double step = zoom >= 13 ? 0.01 : zoom >= 11 ? 0.05 : zoom >= 9 ? 0.25 : zoom >= 7 ? 1 : 5;
        double lonL = WebMercator.xToLon(ox, zoom), lonR = WebMercator.xToLon(ox + w, zoom);
        double latT = WebMercator.yToLat(oy, zoom), latB = WebMercator.yToLat(oy + h, zoom);
        for (double lo = Math.floor(lonL / step) * step; lo <= lonR; lo += step) {
            double x = WebMercator.lonToX(lo, zoom) - ox;
            javafx.scene.shape.Line l = new javafx.scene.shape.Line(x, 0, x, h);
            l.getStyleClass().add("map-graticule");
            fallback.getChildren().add(l);
        }
        for (double la = Math.floor(latB / step) * step; la <= latT; la += step) {
            double y = WebMercator.latToY(la, zoom) - oy;
            javafx.scene.shape.Line l = new javafx.scene.shape.Line(0, y, w, y);
            l.getStyleClass().add("map-graticule");
            fallback.getChildren().add(l);
        }
        for (Object[] p : PLACES) {
            double x = WebMercator.lonToX((double) p[2], zoom) - ox;
            double y = WebMercator.latToY((double) p[1], zoom) - oy;
            if (x < -60 || y < -20 || x > w + 60 || y > h + 20) continue;
            javafx.scene.shape.Circle c = new javafx.scene.shape.Circle(x, y, 3);
            c.getStyleClass().add("map-place-dot");
            Label t = new Label((String) p[0]);
            t.getStyleClass().add("map-place");
            t.relocate(x + 6, y - 9);
            fallback.getChildren().addAll(c, t);
        }
    }

    private void updateScale() {
        double mpp = WebMercator.metersPerPixel(centerLat(), zoom);
        double target = mpp * 100;   // longueur visée : ~100 px
        double[] steps = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000};
        double best = steps[0];
        for (double s : steps) if (s <= target) best = s;
        scaleBar.setPrefWidth(best / mpp);
        scaleBar.setMaxWidth(best / mpp);
        scaleLabel.setText(best >= 1000 ? String.format(Locale.FRANCE, "%.0f km", best / 1000) : String.format(Locale.FRANCE, "%.0f m", best));
    }

    /** Pour les tests : nombre de tuiles affichées. */
    int tileCount() {
        return tileViews.size();
    }
}
