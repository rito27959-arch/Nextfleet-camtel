package cm.camtel.nextfleet.mobile.ui;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cm.camtel.nextfleet.domain.security.Permission;
import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.data.Catalogue;
import cm.camtel.nextfleet.mobile.data.Models.Mission;
import cm.camtel.nextfleet.mobile.data.Models.MissionStatus;
import cm.camtel.nextfleet.mobile.data.Models.StartResult;
import cm.camtel.nextfleet.mobile.data.Session;
import cm.camtel.nextfleet.mobile.tracking.TrackingBus;
import cm.camtel.nextfleet.mobile.tracking.TrackingService;
import cm.camtel.nextfleet.mobile.tracking.TrackingState;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Missions : la mission du moment en carte héros (démarrer après information sur la localisation, terminer),
 * puis la liste. Le conducteur ne voit que ses missions ; le gestionnaire et le contrôleur consultent.
 */
final class MissionsPage implements Page, TrackingBus.Listener {

    static final String NOTICE = "NOTICE-GPS-v1";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.FRANCE)
            .withZone(ZoneId.of("Africa/Douala"));

    private final MainActivity a;
    private final ScrollView scroll;
    private final LinearLayout col;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView liveLine;
    private boolean visible;

    MissionsPage(MainActivity a) {
        this.a = a;
        col = Ui.column(a, 16);
        col.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, Ui.TAB_CLEARANCE));
        scroll = new ScrollView(a);
        scroll.addView(col);
        Ui.applyInsets(scroll, true, false);
    }

    @Override
    public String title() {
        return "Missions";
    }

    @Override
    public int icon() {
        return cm.camtel.nextfleet.mobile.R.drawable.ic_tab_missions;
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public void onShow() {
        visible = true;
        TrackingBus.register(this);
        load();
    }

    @Override
    public void onHide() {
        visible = false;
        TrackingBus.unregister(this);
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onTrackingChanged(TrackingBus.Status s) {
        if (liveLine == null) return;
        String base = "Localisation active · " + s.points + (s.points > 1 ? " positions" : " position");
        if (s.pending > 0) base += " · " + s.pending + " en attente d'envoi";
        if (s.lastError != null) base += "\n" + s.lastError;
        liveLine.setText(base);
    }

    private void load() {
        if (col.getChildCount() == 0) {
            col.addView(Ui.largeTitle(a, "Missions"));
            col.addView(Ui.muted(a, "Chargement…", 15));
        }
        Ui.async(() -> NexFleetApp.get().repository().missions(), this::render, e -> {
            col.removeAllViews();
            col.addView(Ui.largeTitle(a, "Missions"));
            col.addView(errorCard(e.getMessage()));
        });
    }

    private void render(List<Mission> missions) {
        Session s = NexFleetApp.get().session();
        col.removeAllViews();
        liveLine = null;
        TextView title = Ui.largeTitle(a, s.can(Permission.MISSION_DRIVE) && !s.can(Permission.MISSION_MANAGE) ? "Mes missions" : "Missions");
        title.setPadding(Ui.dp(a, 4), 0, 0, 0);
        col.addView(title);
        Mission active = null, next = null;
        List<Mission> others = new ArrayList<>();
        for (Mission m : missions) {
            if (m.status == MissionStatus.ACTIVE && active == null && s.can(Permission.MISSION_DRIVE)) active = m;
            else if (m.status == MissionStatus.AUTHORIZED && next == null && s.can(Permission.MISSION_DRIVE)) next = m;
            else others.add(m);
        }
        if (active != null) col.addView(hero(active, true));
        else if (next != null) col.addView(hero(next, false));
        else if (s.can(Permission.MISSION_DRIVE)) col.addView(empty("Aucune mission à venir", "Les missions autorisées par votre gestionnaire apparaîtront ici."));
        TextView note = Ui.muted(a, NexFleetApp.get().repository().isDemo()
                ? "Démonstration : la position est celle de ce téléphone et reste sur l'appareil. En service réel, elle part vers "
                + "NexFleet (puis Traccar) uniquement pendant la mission."
                : "La localisation fonctionne uniquement entre le démarrage et la clôture d'une mission autorisée.", 12);
        note.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        note.setBackground(Ui.rounded(a, 0x1F0088FF, 16));
        note.setTextColor(Ui.TEXT);
        col.addView(note);
        if (!others.isEmpty()) {
            TextView h = Ui.text(a, "TOUTES LES MISSIONS", 13, Ui.MUTED, NexFleetApp.get().regular());
            h.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), 0, 0);
            col.addView(h);
            for (Mission m : others) col.addView(card(m));
        }
        if (active != null) {
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> {
                if (visible) load();
            }, 15_000);
        }
    }

    private View hero(Mission m, boolean active) {
        LinearLayout h = Ui.column(a, 10);
        h.setPadding(Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22));
        // carte héroïque à la Wallet : dégradé, rayon 28, bouton capsule concentrique
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                active ? new int[]{0xFF007A6A, 0xFF00A08A} : new int[]{0xFF12323F, 0xFF0C4A45});
        bg.setCornerRadius(Ui.dp(a, 28));
        h.setBackground(bg);
        h.setElevation(Ui.dp(a, 8));
        h.addView(Ui.overline(a, active ? "Mission en cours" : "Prochaine mission", 0xC8FFFFFF));
        TextView t = Ui.title(a, m.purpose);
        t.setTextColor(0xFFFFFFFF);
        h.addView(t);
        TextView r = Ui.text(a, m.origin + " → " + m.destination, 17, 0xFFFFFFFF, NexFleetApp.get().regular());
        h.addView(r);
        h.addView(Ui.text(a, m.reference + " · " + m.registration + " · " + m.vehicleLabel, 13, 0xB8FFFFFF, NexFleetApp.get().regular()));
        if (active) {
            liveLine = Ui.text(a, "Localisation active", 14, 0xFFFFFFFF, NexFleetApp.get().semibold());
            h.addView(liveLine);
            onTrackingChanged(TrackingBus.last());
            Button end = Ui.button(a, "Terminer la mission", Ui.Style.ON_DARK, v -> confirmFinish(m));
            h.addView(end);
        } else {
            h.addView(Ui.text(a, "Prévue " + WHEN.format(m.plannedStart) + " · localisation inactive", 13, 0xB8FFFFFF, NexFleetApp.get().regular()));
            Button start = Ui.button(a, "Démarrer la mission", Ui.Style.ON_DARK, v -> confirmStart(m));
            h.addView(start);
        }
        LinearLayout row = Ui.row(a, 10);
        if (m.conversationId != null && NexFleetApp.get().session().can(Permission.CHAT_USE)) {
            row.addView(Ui.button(a, "Messages", Ui.Style.GLASS, v -> a.startActivity(new Intent(a, ThreadActivity.class)
                    .putExtra("id", m.conversationId).putExtra("title", m.reference))), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        Catalogue.Entry model = NexFleetApp.get().catalogue().forCategory(m.category);
        if (model != null) {
            row.addView(Ui.button(a, "Véhicule 3D", Ui.Style.GLASS, v -> ModelViewerActivity.open(a, model)),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (row.getChildCount() > 0) h.addView(row);
        return h;
    }

    private View card(Mission m) {
        LinearLayout c = Ui.card(a);
        LinearLayout top = Ui.row(a, 8);
        top.addView(Ui.text(a, m.reference, 12, Ui.MUTED, NexFleetApp.get().semibold()));
        top.addView(Ui.spacer(a));
        int tone = m.status == MissionStatus.ACTIVE ? Ui.GREEN : m.status == MissionStatus.AUTHORIZED ? Ui.BLUE : Ui.MUTED;
        top.addView(Ui.pill(a, m.status.label, tone));
        c.addView(top);
        c.addView(Ui.headline(a, m.purpose));
        c.addView(Ui.muted(a, m.origin + " → " + m.destination, 14));
        c.addView(Ui.muted(a, m.registration + " · " + m.driverName, 13));
        return c;
    }

    private View empty(String title, String detail) {
        LinearLayout c = Ui.card(a);
        c.addView(Ui.headline(a, title));
        c.addView(Ui.muted(a, detail, 14));
        return c;
    }

    private View errorCard(String message) {
        LinearLayout c = Ui.card(a);
        TextView t = Ui.text(a, message, 14, Ui.RED, NexFleetApp.get().regular());
        c.addView(t);
        c.addView(Ui.button(a, "Réessayer", Ui.Style.GRAY, v -> load()));
        return c;
    }

    // ------------------------------------------------------------------ démarrage et clôture

    /** Information préalable explicite (NF-70), puis autorisations Android, puis démarrage côté serveur. */
    private void confirmStart(Mission m) {
        Ui.alert(a, "Activer la localisation pour " + m.reference + " ?",
                "La position du téléphone sera enregistrée uniquement pendant cette mission, jusqu'à sa clôture. Une "
                        + "notification reste affichée tant que la localisation est active. Notice " + NOTICE + ".",
                "Annuler", "Démarrer", () -> {
                    List<String> perms = new ArrayList<>(List.of(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION));
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS);
                    a.requestRuntimePermissions(perms.toArray(new String[0]), granted -> {
                        if (granted) start(m);
                        else Ui.alert(a, "Localisation refusée", "La mission ne peut pas démarrer sans localisation. Vous pouvez "
                                + "l'autoriser dans les réglages Android de NexFleet.", null, "OK", null);
                    });
                });
    }

    private void start(Mission m) {
        TrackingState st = TrackingState.of(a);
        String device = st.deviceId();
        Ui.async(() -> NexFleetApp.get().repository().start(m.id, NOTICE, device), (StartResult r) -> {
            st.begin(r.mission.id, r.mission.reference, r.mission.startedAt, r.missionToken, r.gatewayUrl,
                    NexFleetApp.get().repository().isDemo());
            TrackingService.start(a);
            load();
        }, e -> Ui.alert(a, "Démarrage impossible", e.getMessage(), null, "OK", null));
    }

    private void confirmFinish(Mission m) {
        Ui.alert(a, "Terminer " + m.reference + " ?", "La localisation s'arrête immédiatement. Plus aucune position ne sera "
                + "acceptée pour cette mission.", "Annuler", "Terminer", () ->
                Ui.async(() -> NexFleetApp.get().repository().finish(m.id), done -> {
                    TrackingState.of(a).end();
                    TrackingService.stop(a);
                    TrackingService.drain(a);
                    load();
                }, e -> Ui.alert(a, "Clôture impossible", e.getMessage(), null, "OK", null)));
    }
}
