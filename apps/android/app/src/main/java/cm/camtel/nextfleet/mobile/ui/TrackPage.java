package cm.camtel.nextfleet.mobile.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cm.camtel.nextfleet.domain.gps.GpsFix;
import cm.camtel.nextfleet.domain.gps.TrackAnalyzer;
import cm.camtel.nextfleet.domain.gps.TrackPolicy;
import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.tracking.FixCodec;
import cm.camtel.nextfleet.mobile.tracking.FixStore;
import cm.camtel.nextfleet.mobile.tracking.TrackingBus;
import cm.camtel.nextfleet.mobile.tracking.TrackingState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Trajet de la mission en cours, relevé par ce téléphone ; statistiques qualifiées par le noyau métier. */
final class TrackPage implements Page, TrackingBus.Listener {

    private static final TrackAnalyzer ANALYZER = new TrackAnalyzer(TrackPolicy.demoDefaults());
    private final MainActivity a;
    private final ScrollView scroll;
    private final LinearLayout col;
    private final TrackView map;
    private final TextView status, stats;
    private FixStore store;

    TrackPage(MainActivity a) {
        this.a = a;
        col = Ui.column(a, 14);
        col.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, Ui.TAB_CLEARANCE));
        col.addView(Ui.largeTitle(a, "Trajet"));
        status = Ui.muted(a, "", 14);
        col.addView(status);
        map = new TrackView(a);
        map.setBackground(Ui.rounded(a, 0xFFEEF2F0, 26));
        map.setClipToOutline(true);
        col.addView(map, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 360)));
        stats = Ui.body(a, "");
        LinearLayout card = Ui.card(a);
        card.addView(stats);
        col.addView(card);
        TextView note = Ui.muted(a, "Fond de carte non chargé : le serveur de tuiles est à désigner par la DSIR. Les positions "
                + "imprécises et les sauts sont exclus des statistiques.", 12);
        col.addView(note);
        scroll = new ScrollView(a);
        scroll.addView(col);
        Ui.applyInsets(scroll, true, false);
    }

    @Override
    public String title() {
        return "Trajet";
    }

    @Override
    public int icon() {
        return cm.camtel.nextfleet.mobile.R.drawable.ic_tab_track;
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public void onShow() {
        if (store == null) store = new FixStore(a);
        TrackingBus.register(this);
    }

    @Override
    public void onHide() {
        TrackingBus.unregister(this);
    }

    @Override
    public void onTrackingChanged(TrackingBus.Status s) {
        TrackingState st = TrackingState.of(a);
        String mission = st.missionId();
        if (mission == null) {
            status.setText("Aucune mission en cours : la localisation est arrêtée.");
            map.setFixes(List.of());
            stats.setText("Démarrez une mission autorisée depuis l'onglet Missions.");
            return;
        }
        Ui.async(() -> store.track(mission), fixes -> {
            map.setFixes(fixes);
            List<GpsFix> g = new ArrayList<>();
            for (FixCodec.Fix f : fixes) g.add(new GpsFix(f.clientFixId, f.recordedAt, f.lat, f.lon, f.accuracyM, f.speedMps));
            TrackAnalyzer.TripStatistics t = ANALYZER.statistics(g);
            Duration d = st.startedAt() == null ? Duration.ZERO : Duration.between(st.startedAt(), Instant.now());
            status.setText("Mission " + st.reference() + (NexFleetApp.get().repository().isDemo() ? " · démonstration (positions conservées sur le téléphone)"
                    : s.pending > 0 ? " · " + s.pending + " position(s) en attente d'envoi" : " · positions envoyées à NexFleet"));
            stats.setText(String.format(Locale.FRANCE, "Distance : %.1f km\nDurée : %d min\nVitesse max. observée : %s\nArrêts ≥ 5 min : %d\n"
                            + "Fiabilité : %s (%d points valides)%s",
                    t.distanceKm(), d.toMinutes(), t.maxObservedSpeedKmh() == null ? "—" : Math.round(t.maxObservedSpeedKmh()) + " km/h",
                    t.stops().size(), t.reliability().name().toLowerCase(Locale.FRANCE), t.validPoints(),
                    s.lastError == null ? "" : "\n" + s.lastError));
        }, e -> status.setText(e.getMessage()));
    }
}
