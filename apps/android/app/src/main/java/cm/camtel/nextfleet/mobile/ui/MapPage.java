package cm.camtel.nextfleet.mobile.ui;

import android.graphics.PointF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.data.Models;

/**
 * Carte de flotte en direct (gestionnaire, contrôleur) avec MapLibre. Fond vectoriel OpenFreeMap (gratuit, sans clé,
 * attribution OpenStreetMap affichée par MapLibre) jusqu'à la mise à disposition d'un serveur de tuiles CAMTEL.
 * Positions poussées par le canal temps réel du serveur, déjà filtrées par droits. La couleur est toujours doublée
 * d'un libellé ; une perte de signal n'est jamais présentée comme une panne.
 */
final class MapPage implements Page {

    static final String STYLE = "https://tiles.openfreemap.org/styles/liberty";
    private static final String SRC = "nf-vehicles", SRC_SEL = "nf-selected";

    private final MainActivity a;
    private final FrameLayout root;
    private final MapView mapView;
    private final TextView chip;
    private final LinearLayout strip;
    private final LinearLayout sheet;
    private final Map<String, Models.VehicleLive> vehicles = new LinkedHashMap<>();
    private MapLibreMap map;
    private GeoJsonSource source, selectedSource;
    private String selected;
    private boolean fitted, started;
    private AutoCloseable live;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            load();
            ticker.postDelayed(this, 30_000);   // les états vieillissent même sans nouvelle position
        }
    };

    MapPage(MainActivity a) {
        this.a = a;
        MapLibre.getInstance(a);
        root = new FrameLayout(a);
        mapView = new MapView(a);
        mapView.onCreate(null);
        root.addView(mapView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout top = Ui.row(a, 10);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
        top.setBackground(Ui.rounded(a, 0xF2FFFFFF, 16));
        top.setElevation(Ui.dp(a, 3));
        top.addView(Ui.title(a, "Carte"));
        chip = Ui.pill(a, "Connexion…", Ui.MUTED);
        top.addView(chip);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        tp.setMargins(Ui.dp(a, 12), Ui.dp(a, 12), 0, 0);
        root.addView(top, tp);

        LinearLayout bottom = Ui.column(a, 8);
        bottom.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 12));
        sheet = Ui.card(a);
        sheet.setVisibility(View.GONE);
        sheet.setElevation(Ui.dp(a, 4));
        bottom.addView(sheet);
        strip = Ui.row(a, 8);
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(strip);
        bottom.addView(hs);
        root.addView(bottom, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        Ui.applyInsets(root, true, false);

        mapView.getMapAsync(m -> {
            map = m;
            m.getUiSettings().setRotateGesturesEnabled(false);
            m.getUiSettings().setTiltGesturesEnabled(false);
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(3.8667, 11.5167), 7));
            m.setStyle(new Style.Builder().fromUri(STYLE), this::onStyle);
            m.addOnMapClickListener(point -> {
                PointF px = m.getProjection().toScreenLocation(point);
                List<Feature> hit = m.queryRenderedFeatures(px, "nf-vehicle-dot", "nf-vehicle-label");
                if (hit.isEmpty()) return false;
                select(hit.get(0).getStringProperty("id"), false);
                return true;
            });
        });
    }

    private void onStyle(Style style) {
        source = new GeoJsonSource(SRC, FeatureCollection.fromFeatures(new ArrayList<>()));
        selectedSource = new GeoJsonSource(SRC_SEL, FeatureCollection.fromFeatures(new ArrayList<>()));
        style.addSource(source);
        style.addSource(selectedSource);
        style.addLayer(new CircleLayer("nf-selected-ring", SRC_SEL).withProperties(
                PropertyFactory.circleRadius(19f), PropertyFactory.circleColor("#008575"), PropertyFactory.circleOpacity(0.25f)));
        style.addLayer(new CircleLayer("nf-vehicle-halo", SRC).withProperties(
                PropertyFactory.circleRadius(14f), PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleOpacity(0.22f)));
        style.addLayer(new CircleLayer("nf-vehicle-dot", SRC).withProperties(
                PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)));
        style.addLayer(new SymbolLayer("nf-vehicle-label", SRC).withProperties(
                PropertyFactory.textField(Expression.get("label")), PropertyFactory.textFont(new String[]{"Noto Sans Bold"}),
                PropertyFactory.textSize(12f), PropertyFactory.textOffset(new Float[]{0f, 1.6f}), PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textColor("#102631"), PropertyFactory.textHaloColor("#FFFFFF"), PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textAllowOverlap(true)));
        render();
    }

    // ------------------------------------------------------------------ données

    private void load() {
        Ui.async(() -> NexFleetApp.get().repository().fleetLive(), list -> {
            vehicles.clear();
            for (Models.VehicleLive v : list) vehicles.put(v.vehicleId, v);
            if (selected == null && !list.isEmpty()) selected = list.get(0).vehicleId;
            render();
            if (!fitted && !list.isEmpty()) fit();
        }, err -> setChip("Indisponible : " + err.getMessage(), Ui.RED));
    }

    private void onEvent(Models.LiveEvent e) {
        Models.VehicleLive v = vehicles.get(e.vehicleId);
        if (v == null) {
            load();
            return;
        }
        vehicles.put(e.vehicleId, v.moved(e.lat, e.lon, e.speedKmh, e.recordedAt));
        render();
    }

    private void setChip(String text, int color) {
        chip.setText(text);
        chip.setTextColor(color);
    }

    // ------------------------------------------------------------------ rendu

    static String color(String state) {
        switch (state) {
            case "EN_MOUVEMENT": return "#34C759";
            case "ARRET": return "#0088FF";
            case "A_VERIFIER": return "#FF8D28";
            default: return "#8E8E93";
        }
    }

    private void render() {
        renderStrip();
        renderSheet();
        if (source == null) return;
        List<Feature> fs = new ArrayList<>();
        List<Feature> sel = new ArrayList<>();
        for (Models.VehicleLive v : vehicles.values()) {
            Feature f = Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat));
            f.addStringProperty("id", v.vehicleId);
            f.addStringProperty("label", v.registration);
            f.addStringProperty("color", color(v.state));
            fs.add(f);
            if (v.vehicleId.equals(selected)) sel.add(Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat)));
        }
        source.setGeoJson(FeatureCollection.fromFeatures(fs));
        selectedSource.setGeoJson(FeatureCollection.fromFeatures(sel));
    }

    private void fit() {
        if (map == null) return;
        fitted = true;
        if (vehicles.size() == 1) {
            Models.VehicleLive v = vehicles.values().iterator().next();
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(v.lat, v.lon), 13));
            return;
        }
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (Models.VehicleLive v : vehicles.values()) b.include(new LatLng(v.lat, v.lon));
        try {
            map.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), Ui.dp(a, 72)));
        } catch (RuntimeException samePoint) {
            Models.VehicleLive v = vehicles.values().iterator().next();
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(v.lat, v.lon), 13));
        }
    }

    private void select(String id, boolean center) {
        selected = id;
        render();
        Models.VehicleLive v = vehicles.get(id);
        if (center && v != null && map != null) map.easeCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(v.lat, v.lon), 14));
    }

    private void renderStrip() {
        strip.removeAllViews();
        for (Models.VehicleLive v : vehicles.values()) {
            LinearLayout c = Ui.row(a, 6);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8));
            boolean sel = v.vehicleId.equals(selected);
            c.setBackground(Ui.rounded(a, sel ? Ui.NAVY : 0xF2FFFFFF, 999));
            c.setElevation(Ui.dp(a, 2));
            View dot = new View(a);
            dot.setBackground(Ui.rounded(a, android.graphics.Color.parseColor(color(v.state)), 999));
            c.addView(dot, new LinearLayout.LayoutParams(Ui.dp(a, 10), Ui.dp(a, 10)));
            TextView t = Ui.text(a, v.registration, 13, sel ? 0xFFFFFFFF : Ui.TEXT, NexFleetApp.get().semibold());
            c.addView(t);
            c.setContentDescription(v.registration + ", " + v.stateLabel);
            c.setOnClickListener(x -> select(v.vehicleId, true));
            strip.addView(c);
        }
    }

    private void renderSheet() {
        Models.VehicleLive v = selected == null ? null : vehicles.get(selected);
        sheet.removeAllViews();
        if (v == null) {
            sheet.setVisibility(vehicles.isEmpty() ? View.VISIBLE : View.GONE);
            if (vehicles.isEmpty()) {
                sheet.addView(Ui.headline(a, "Aucun véhicule en mission"));
                sheet.addView(Ui.muted(a, "Les positions n'existent que pendant une mission démarrée par son conducteur.", 13));
            }
            return;
        }
        sheet.setVisibility(View.VISIBLE);
        LinearLayout head = Ui.row(a, 8);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.title(a, v.registration));
        head.addView(Ui.pill(a, v.stateLabel, android.graphics.Color.parseColor(color(v.state))));
        sheet.addView(head);
        sheet.addView(Ui.muted(a, v.vehicleLabel + " · " + v.missionRef + " · " + v.driverName, 13));
        String speed = v.speedKmh == null ? "vitesse non mesurée" : Math.round(v.speedKmh) + " km/h";
        String dist = v.distanceKm == null ? "" : String.format(Locale.FRANCE, " · %.1f km parcourus", v.distanceKm);
        sheet.addView(Ui.body(a, "→ " + v.destination + "\n" + speed + dist));
        sheet.addView(Ui.muted(a, "Dernier signal " + ago(v.recordedAt), 12));
    }

    static String ago(Instant at) {
        if (at == null) return "inconnu";
        long s = Math.max(0, Duration.between(at, Instant.now()).getSeconds());
        if (s < 60) return "à l'instant";
        if (s < 3600) return "il y a " + (s / 60) + " min";
        return "il y a " + (s / 3600) + " h";
    }

    // ------------------------------------------------------------------ cycle de vie

    @Override
    public String title() {
        return "Carte";
    }

    @Override
    public int icon() {
        return cm.camtel.nextfleet.mobile.R.drawable.ic_tab_map;
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        if (!started) {
            mapView.onStart();
            started = true;
        }
        mapView.onResume();
        ticker.post(refresh);
        boolean demo = NexFleetApp.get().repository().isDemo();
        if (live == null) {
            live = NexFleetApp.get().repository().live(e -> ticker.post(() -> onEvent(e)), up -> ticker.post(() ->
                    setChip(demo ? "Démonstration" : up ? "En direct" : "Reconnexion…", demo ? Ui.ORANGE : up ? Ui.GREEN : Ui.RED)));
        }
    }

    @Override
    public void onHide() {
        ticker.removeCallbacks(refresh);
        if (live != null) {
            try {
                live.close();
            } catch (Exception ignored) {
                // déjà fermé
            }
            live = null;
        }
        mapView.onPause();
        if (started) {
            mapView.onStop();
            started = false;
        }
    }

    @Override
    public void onDestroy() {
        mapView.onDestroy();
    }

    @Override
    public void onLowMemory() {
        mapView.onLowMemory();
    }
}
