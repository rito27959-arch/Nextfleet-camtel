package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import cm.camtel.nextfleet.domain.security.Permission;
import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.R;
import cm.camtel.nextfleet.mobile.data.Session;
import cm.camtel.nextfleet.mobile.tracking.TrackingService;
import cm.camtel.nextfleet.mobile.tracking.TrackingState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Écran principal : onglets selon le profil (Missions et Trajet pour le terrain, Messages, Catalogue 3D, Profil).
 * La visibilité suit la matrice des habilitations ; le serveur revérifie chaque appel.
 */
public final class MainActivity extends Activity {

    private final List<Page> pages = new ArrayList<>();
    private final List<LinearLayout> tabs = new ArrayList<>();
    private FrameLayout content;
    private int current = -1;
    private Consumer<Boolean> permissionCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Session s = NexFleetApp.get().session();
        if (s == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        TrackingService.createChannel(this);
        content = new FrameLayout(this);
        boolean field = s.can(Permission.MISSION_DRIVE) || s.can(Permission.MISSION_MANAGE) || s.can(Permission.GPS_READ);
        if (field) pages.add(new MissionsPage(this));
        if (s.can(Permission.MISSION_MANAGE) || s.can(Permission.GPS_READ)) pages.add(new MapPage(this));
        if (s.can(Permission.MISSION_DRIVE)) pages.add(new TrackPage(this));
        if (s.can(Permission.CHAT_USE)) pages.add(new MessagesPage(this));
        pages.add(new CataloguePage(this));
        pages.add(new ProfilePage(this));

        // iOS 26 : contenu plein écran sur fond doux, barre d'onglets en verre teinté qui flotte au-dessus
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.backdrop());
        root.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(Ui.glass(this, 32));
        bar.setElevation(Ui.dp(this, 10));
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        for (int i = 0; i < pages.size(); i++) {
            final int idx = i;
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            ImageView ic = new ImageView(this);
            ic.setImageResource(pages.get(i).icon());
            t.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
            TextView label = Ui.text(this, pages.get(i).title(), 10.5f, Ui.TEXT, NexFleetApp.get().semibold());
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(this, 2), 0, 0);
            t.addView(label);
            t.setOnClickListener(v -> show(idx));
            t.setContentDescription(pages.get(i).title());
            t.setMinimumHeight(Ui.dp(this, 52));
            tabs.add(t);
            bar.addView(t, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        }
        FrameLayout barHost = new FrameLayout(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        bp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 10));
        barHost.addView(bar, bp);
        root.addView(barHost, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        Ui.applyInsets(barHost, false, true);
        setContentView(root);
        boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        int tab = debuggable ? getIntent().getIntExtra("captureTab", 0) : 0;
        show(Math.max(0, Math.min(pages.size() - 1, tab)));
        if (debuggable && state == null) {
            // Recette visuelle (APK de débogage uniquement) : écrans secondaires non exportés, ouverts depuis l'app.
            String thread = getIntent().getStringExtra("captureThread");
            String model = getIntent().getStringExtra("captureModel");
            if (thread != null) startActivity(new Intent(this, ThreadActivity.class).putExtra("id", thread)
                    .putExtra("title", thread.replace("conv-", "MIS-2026-")));
            else if (model != null) startActivity(new Intent(this, ModelViewerActivity.class).putExtra("id", model));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TrackingService.drain(this);   // points de la mission précédente encore en tampon
        if (TrackingState.of(this).active() && NexFleetApp.get().session() != null) TrackingService.start(this);
        if (current >= 0) pages.get(current).onShow();
    }

    @Override
    protected void onDestroy() {
        for (Page p : pages) p.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        for (Page p : pages) p.onLowMemory();
    }

    @Override
    protected void onPause() {
        if (current >= 0) pages.get(current).onHide();
        super.onPause();
    }

    void show(int i) {
        if (current == i) return;
        if (current >= 0) pages.get(current).onHide();
        current = i;
        content.removeAllViews();
        View v = pages.get(i).view();
        content.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(this, 8));
        v.animate().alpha(1f).translationY(0).setDuration(220).start();
        for (int k = 0; k < tabs.size(); k++) {
            int color = k == i ? Ui.GLASS_ACCENT : Ui.TEXT;
            LinearLayout t = tabs.get(k);
            ((ImageView) t.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
            ((TextView) t.getChildAt(1)).setTextColor(color);
            // onglet actif : capsule teintée concentrique à la barre (32 − 6 = 26)
            t.setBackground(k == i ? Ui.rounded(this, Ui.SELECT, 26) : null);
            t.setSelected(k == i);
        }
        pages.get(i).onShow();
    }

    void showPage(Class<? extends Page> type) {
        for (int i = 0; i < pages.size(); i++) if (type.isInstance(pages.get(i))) show(i);
    }

    /** Demande d'autorisations Android (localisation, notifications) ; le rappel reçoit « au moins la localisation ». */
    void requestRuntimePermissions(String[] permissions, Consumer<Boolean> callback) {
        permissionCallback = callback;
        requestPermissions(permissions, 42);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 42 || permissionCallback == null) return;
        boolean location = false;
        for (int i = 0; i < permissions.length; i++) {
            if (permissions[i].contains("LOCATION") && results[i] == PackageManager.PERMISSION_GRANTED) location = true;
        }
        Consumer<Boolean> cb = permissionCallback;
        permissionCallback = null;
        cb.accept(location);
    }

    void logout() {
        NexFleetApp.get().logout();
        startActivity(new Intent(this, LoginActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    static int iconMissions() {
        return R.drawable.ic_tab_missions;
    }
}
