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
import cm.camtel.nextfleet.mobile.R;
import cm.camtel.nextfleet.mobile.data.Catalogue;
import cm.camtel.nextfleet.mobile.data.Models.Mission;
import cm.camtel.nextfleet.mobile.data.Models.MissionStatus;
import cm.camtel.nextfleet.mobile.data.Models.StartResult;
import cm.camtel.nextfleet.mobile.data.Session;
import cm.camtel.nextfleet.mobile.tracking.TrackingBus;
import cm.camtel.nextfleet.mobile.tracking.TrackingService;
import cm.camtel.nextfleet.mobile.tracking.TrackingState;

import java.time.Instant;
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
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d", Locale.FRANCE).withZone(ZoneId.of("Africa/Douala"));
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE).withZone(ZoneId.of("Africa/Douala"));
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.FRANCE)
            .withZone(ZoneId.of("Africa/Douala"));

    private final MainActivity a;
    private final ScrollView scroll;
    private final PullToRefresh ptr;
    private boolean rendered;
    private boolean fresh;
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
        ptr = new PullToRefresh(a, scroll);
        ptr.setOnRefresh(this::load);
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
        return ptr;
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
        if (pointsView != null) pointsView.setText(String.valueOf(s.points));
        if (pendingView != null) pendingView.setText(String.valueOf(s.pending));
        if (liveLine == null) return;
        // les chiffres sont dans les compteurs : cette ligne ne dit que l'état (ou le problème)
        liveLine.setText(s.lastError != null ? s.lastError
                : s.pending > 0 ? "Réseau faible : les positions partiront dès le retour du signal." : "Localisation active · envoi automatique");
    }

    private void load() {
        load(null);
    }

    /**
     * Affichage immédiat des dernières missions connues (stockage local), puis version du serveur.
     * Premier affichage sans données locales : squelettes à la forme des cartes, jamais un texte d'attente.
     */
    private void load(Runnable done) {
        cm.camtel.nextfleet.mobile.data.Repository repo = NexFleetApp.get().repository();
        if (!rendered) {
            if (col.getChildCount() == 0) {
                col.addView(Ui.largeTitle(a, "Missions"));
                col.addView(Ui.skeleton(a, 196, 26));
                col.addView(Ui.skeleton(a, 64, 18));
                col.addView(Ui.skeleton(a, 88, 18));
            }
            Ui.async(() -> repo.cached(repo::missions), list -> {
                if (list != null && !fresh) render(list);
            }, e -> { });
        }
        Ui.async(repo::missions, list -> {
            fresh = true;
            render(list);
            if (done != null) done.run();
        }, e -> {
            if (!rendered) {
                col.removeAllViews();
                col.addView(Ui.largeTitle(a, "Missions"));
                col.addView(errorCard(e.getMessage()));
            } else {
                Motion.reject(col);
                android.widget.Toast.makeText(a, e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
            }
            if (done != null) done.run();
        });
    }

    private void render(List<Mission> missions) {
        Session s = NexFleetApp.get().session();
        col.removeAllViews();
        liveLine = null;
        heroMission = null;
        phaseView = elapsedView = pointsView = pendingView = null;
        TextView title = Ui.largeTitle(a, s.can(Permission.MISSION_DRIVE) && !s.can(Permission.MISSION_MANAGE) ? "Mes missions" : "Missions");
        title.setPadding(Ui.dp(a, 4), 0, 0, 0);
        col.addView(title);
        Long offline = NexFleetApp.get().repository().offlineSince();
        if (offline != null && fresh) col.addView(Ui.offlineBanner(a, offline));
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
        col.addView(fieldActions(s));
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
        if (heroMission != null && visible) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
        if (!rendered) Motion.stagger(col);
        rendered = true;
    }

    // ------------------------------------------------------------------ carte « Aujourd'hui » (états selon la phase, à la Flighty)

    private TextView phaseView, elapsedView, pointsView, pendingView;
    private Mission heroMission;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            if (visible && heroMission != null) handler.postDelayed(this, 1000);
        }
    };

    private View hero(Mission m, boolean active) {
        heroMission = m;
        LinearLayout h = Ui.column(a, 14);
        h.setPadding(Ui.dp(a, 22), Ui.dp(a, 20), Ui.dp(a, 22), Ui.dp(a, 22));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                active ? new int[]{0xFF00715F, 0xFF009B87} : new int[]{0xFF0E2430, 0xFF143A40});
        bg.setCornerRadius(Ui.dp(a, 28));
        h.setBackground(bg);
        h.setElevation(Ui.dp(a, 10));

        // phase : pastille + horloge vivante (compte à rebours avant départ, temps écoulé pendant la mission)
        LinearLayout top = Ui.row(a, 8);
        TextView phase = Ui.text(a, active ? "● EN COURS" : "À VENIR", 12, active ? 0xFFB9FFE9 : 0xFFBFD3DC, NexFleetApp.get().semibold());
        phase.setLetterSpacing(0.06f);
        phase.setPadding(Ui.dp(a, 10), Ui.dp(a, 5), Ui.dp(a, 10), Ui.dp(a, 5));
        phase.setBackground(Ui.rounded(a, 0x26FFFFFF, 999));
        top.addView(phase);
        top.addView(Ui.spacer(a));
        phaseView = Ui.text(a, "", 13, 0xD9FFFFFF, NexFleetApp.get().semibold());
        Ui.tabular(phaseView);
        top.addView(phaseView);
        h.addView(top);

        TextView t = Ui.text(a, m.purpose, 24, 0xFFFFFFFF, NexFleetApp.get().display());
        t.setLineSpacing(0, 1.05f);
        h.addView(t);
        h.addView(route(m.origin, m.destination, active));

        // véhicule de la mission, lisible d'un coup d'œil
        LinearLayout veh = Ui.row(a, 10);
        TextView plate = Ui.text(a, m.registration, 15, 0xFF0A1620, NexFleetApp.get().semibold());
        plate.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4));
        plate.setBackground(Ui.rounded(a, 0xF2FFFFFF, 8));
        veh.addView(plate);
        veh.addView(Ui.text(a, m.vehicleLabel + " · " + m.reference, 13, 0xBFFFFFFF, NexFleetApp.get().regular()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        h.addView(veh);

        if (active) {
            // compteurs en chiffres à chasse fixe : ils ne « sautent » pas en s'incrémentant
            LinearLayout stats = Ui.row(a, 0);
            elapsedView = stat(stats, "Temps écoulé");
            pointsView = stat(stats, "Positions envoyées");
            pendingView = stat(stats, "En attente");
            stats.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 2));
            h.addView(stats);
            liveLine = Ui.text(a, "", 12, 0xCCFFFFFF, NexFleetApp.get().regular());
            h.addView(liveLine);
            onTrackingChanged(TrackingBus.last());
            h.addView(Ui.button(a, "Terminer la mission", Ui.Style.ON_DARK, v -> confirmFinish(m)));
        } else {
            elapsedView = pointsView = pendingView = null;
            h.addView(Ui.text(a, "La localisation s'active seulement quand vous démarrez, et s'arrête à la clôture.", 12,
                    0xB8FFFFFF, NexFleetApp.get().regular()));
            h.addView(Ui.button(a, "Démarrer la mission", Ui.Style.ON_DARK, v -> confirmStart(m)));
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
        updateClock();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return h;
    }

    private TextView stat(LinearLayout parent, String label) {
        LinearLayout box = Ui.column(a, 2);
        TextView v = Ui.text(a, "—", 22, 0xFFFFFFFF, NexFleetApp.get().display());
        Ui.tabular(v);
        box.addView(v);
        box.addView(Ui.text(a, label, 11.5f, 0xBFFFFFFF, NexFleetApp.get().regular()));
        parent.addView(box, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    /** Trajet : départ ● ——— ● arrivée ; pendant la mission, une lueur parcourt la ligne (mouvement qui informe). */
    private View route(String from, String to, boolean active) {
        LinearLayout col = Ui.column(a, 6);
        android.widget.FrameLayout track = new android.widget.FrameLayout(a);
        View line = new View(a);
        line.setBackground(Ui.rounded(a, 0x40FFFFFF, 2));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 3), android.view.Gravity.CENTER_VERTICAL);
        lp.leftMargin = Ui.dp(a, 6);
        lp.rightMargin = Ui.dp(a, 6);
        track.addView(line, lp);
        View d1 = new View(a);
        d1.setBackground(Ui.rounded(a, 0xFFFFFFFF, 999));
        track.addView(d1, new android.widget.FrameLayout.LayoutParams(Ui.dp(a, 12), Ui.dp(a, 12), android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL));
        View d2 = new View(a);
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setStroke(Ui.dp(a, 3), 0xFFFFFFFF);
        d2.setBackground(ring);
        track.addView(d2, new android.widget.FrameLayout.LayoutParams(Ui.dp(a, 12), Ui.dp(a, 12), android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL));
        if (active && Motion.enabled()) {
            View glow = new View(a);
            glow.setBackground(Ui.rounded(a, 0xFFB9FFE9, 999));
            track.addView(glow, new android.widget.FrameLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 3), android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL));
            track.post(() -> {
                android.animation.ObjectAnimator an = android.animation.ObjectAnimator.ofFloat(glow, View.TRANSLATION_X,
                        Ui.dp(a, 6), track.getWidth() - Ui.dp(a, 40));
                an.setDuration(1800);
                an.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                an.setInterpolator(Motion.EASE_OUT);
                an.start();
                track.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { }
                    @Override public void onViewDetachedFromWindow(View v) { an.cancel(); }
                });
            });
        }
        col.addView(track, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 14)));
        LinearLayout labels = Ui.row(a, 8);
        TextView f = Ui.text(a, from, 15, 0xFFFFFFFF, NexFleetApp.get().semibold());
        TextView d = Ui.text(a, to, 15, 0xFFFFFFFF, NexFleetApp.get().semibold());
        d.setGravity(android.view.Gravity.END);
        labels.addView(f, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(d, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(labels);
        return col;
    }

    private void updateClock() {
        Mission m = heroMission;
        if (m == null || phaseView == null) return;
        Instant now = Instant.now();
        if (m.status == MissionStatus.ACTIVE && m.startedAt != null) {
            long sec = Math.max(0, java.time.Duration.between(m.startedAt, now).getSeconds());
            String clock = String.format(java.util.Locale.FRANCE, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
            phaseView.setText("depuis " + HM.format(m.startedAt));
            if (elapsedView != null) elapsedView.setText(clock);
        } else if (m.plannedStart != null) {
            long min = java.time.Duration.between(now, m.plannedStart).toMinutes();
            phaseView.setText(min > 0 ? "départ dans " + (min >= 60 ? (min / 60) + " h " + String.format("%02d", min % 60) : min + " min")
                    : min > -60 ? "départ prévu il y a " + (-min) + " min" : "prévue " + WHEN.format(m.plannedStart));
        }
    }

    /** Ligne « tableau des départs » : l'heure à gauche en chiffres fixes, l'objet et le trajet, le statut à droite. */
    private View card(Mission m) {
        LinearLayout c = Ui.card(a);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout time = Ui.column(a, 0);
        time.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        Instant at = m.startedAt != null ? m.startedAt : m.plannedStart;
        TextView day = Ui.text(a, at == null ? "" : DAY.format(at).toUpperCase(Locale.FRANCE), 11, Ui.MUTED, NexFleetApp.get().semibold());
        TextView hour = Ui.text(a, at == null ? "—" : HM.format(at), 19, Ui.TEXT, NexFleetApp.get().display());
        Ui.tabular(hour);
        time.addView(day);
        time.addView(hour);
        c.addView(time, new LinearLayout.LayoutParams(Ui.dp(a, 64), LinearLayout.LayoutParams.WRAP_CONTENT));
        View sep = new View(a);
        sep.setBackgroundColor(Ui.SEPARATOR);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.dp(a, 1), Ui.dp(a, 44));
        sl.setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 14), 0);
        c.addView(sep, sl);
        LinearLayout mid = Ui.column(a, 3);
        TextView purpose = Ui.headline(a, m.purpose);
        purpose.setMaxLines(2);
        mid.addView(purpose);
        mid.addView(Ui.muted(a, m.origin + " → " + m.destination, 14));
        mid.addView(Ui.text(a, m.registration + (m.driverName == null ? "" : " · " + m.driverName) + " · " + m.reference, 12, Ui.TERTIARY,
                NexFleetApp.get().regular()));
        c.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        int tone = m.status == MissionStatus.ACTIVE ? Ui.GREEN : m.status == MissionStatus.AUTHORIZED ? Ui.BLUE : Ui.MUTED;
        c.addView(Ui.pill(a, m.status.label, tone));
        c.setContentDescription(m.purpose + ", " + m.origin + " vers " + m.destination + ", " + m.status.label);
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

    /** Actions terrain selon les droits : carburant, plein avec reçu, panne, contrôle avant départ. */
    /**
     * Actions terrain en grandes tuiles (cibles larges, icône + libellé court, lisibles d'un coup d'œil),
     * dont les papiers du véhicule à présenter lors d'un contrôle.
     */
    private View fieldActions(Session s) {
        java.util.List<View> tiles = new java.util.ArrayList<>();
        String reg = heroMission != null ? heroMission.registration : null;
        if (s.can(Permission.FUEL_FILL_CREATE)) {
            tiles.add(tile(R.drawable.ic_fuel, 0xFF34C759, "Saisir un plein", "avec photo du reçu",
                    v -> a.startActivity(FieldActivity.open(a, FieldActivity.FILL))));
        }
        if (s.can(Permission.FUEL_ORDER_CREATE)) {
            tiles.add(tile(R.drawable.ic_fuel, 0xFF0088FF, "Bon carburant", "demander un bon",
                    v -> a.startActivity(FieldActivity.open(a, FieldActivity.FUEL))));
        }
        if (s.can(Permission.MISSION_DRIVE) || s.can(Permission.MAINTENANCE_WRITE)) {
            tiles.add(tile(R.drawable.ic_check_list, 0xFF6155F5, "Contrôle", "avant départ",
                    v -> a.startActivity(FieldActivity.open(a, FieldActivity.CHECK))));
        }
        if (s.can(Permission.MISSION_DRIVE) || s.can(Permission.MAINTENANCE_WRITE) || s.can(Permission.VEHICLE_WRITE)) {
            tiles.add(tile(R.drawable.ic_wrench, 0xFFFF8D28, "Signaler", "une panne",
                    v -> a.startActivity(FieldActivity.open(a, FieldActivity.WORK))));
        }
        if (s.can(Permission.VEHICLE_READ)) {
            tiles.add(tile(R.drawable.ic_doc, 0xFF102631, "Papiers", "du véhicule",
                    v -> a.startActivity(PapersActivity.open(a, reg))));
        }
        if (tiles.isEmpty()) return new View(a);
        LinearLayout box = Ui.column(a, 10);
        TextView h = Ui.text(a, "MON VÉHICULE", 13, Ui.MUTED, NexFleetApp.get().semibold());
        h.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), 0, 0);
        box.addView(h);
        for (int k = 0; k < tiles.size(); k += 2) {
            LinearLayout row = Ui.row(a, 10);
            row.addView(tiles.get(k), new LinearLayout.LayoutParams(0, Ui.dp(a, 112), 1f));
            if (k + 1 < tiles.size()) row.addView(tiles.get(k + 1), new LinearLayout.LayoutParams(0, Ui.dp(a, 112), 1f));
            else row.addView(new View(a), new LinearLayout.LayoutParams(0, Ui.dp(a, 112), 1f));
            box.addView(row);
        }
        return box;
    }

    private View tile(int icon, int color, String title, String sub, View.OnClickListener click) {
        LinearLayout t = Ui.card(a);
        t.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
        android.widget.ImageView ic = new android.widget.ImageView(a);
        ic.setImageResource(icon);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        ic.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
        ic.setBackground(Ui.rounded(a, color, 12));
        t.addView(ic, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
        View gap = new View(a);
        t.addView(gap, new LinearLayout.LayoutParams(1, 0, 1f));
        t.addView(Ui.text(a, title, 16, Ui.TEXT, NexFleetApp.get().semibold()));
        t.addView(Ui.text(a, sub, 13, Ui.MUTED, NexFleetApp.get().regular()));
        t.setOnClickListener(v -> {
            Motion.tick(v);
            click.onClick(v);
        });
        t.setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14000000), null,
                Ui.rounded(a, 0xFF000000, 22)));
        Motion.pressable(t);
        t.setContentDescription(title + " " + sub);
        return t;
    }
}
