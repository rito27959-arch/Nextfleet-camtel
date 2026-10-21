package cm.camtel.nextfleet.mobile.ui;

import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import cm.camtel.nextfleet.domain.security.Permission;
import cm.camtel.nextfleet.domain.security.Role;
import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.R;
import cm.camtel.nextfleet.mobile.data.Session;
import cm.camtel.nextfleet.mobile.tracking.TrackingState;

/**
 * Profil, à la manière de Réglages sur iOS : en-tête d'identité, groupes arrondis (droits, apparence, appareil),
 * déconnexion seule dans son groupe, en rouge.
 */
final class ProfilePage implements Page {

    private final MainActivity a;
    private final ScrollView scroll;

    ProfilePage(MainActivity a) {
        this.a = a;
        Session s = NexFleetApp.get().session();
        Role r = s.primaryRole();
        LinearLayout col = Ui.column(a, 10);
        col.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, Ui.TAB_CLEARANCE));
        TextView title = Ui.largeTitle(a, "Profil");
        title.setPadding(Ui.dp(a, 4), 0, 0, Ui.dp(a, 6));
        col.addView(title);

        // identité
        LinearLayout who = Ui.group(a);
        LinearLayout head = Ui.row(a, 14);
        head.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
        TextView avatar = Ui.text(a, MessagesPage.initials(s.displayName), 22, 0xFFFFFFFF, NexFleetApp.get().semibold());
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.rounded(a, Ui.NAVY, 999));
        head.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(a, 60), Ui.dp(a, 60)));
        LinearLayout names = Ui.column(a, 3);
        names.addView(Ui.title3(a, s.displayName));
        names.addView(Ui.muted(a, (r == null ? "—" : r.label) + " · " + s.login, 14));
        head.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        who.addView(head);
        col.addView(who);
        if (r != null) col.addView(footer(r.summary));

        // droits
        col.addView(header("Vos droits"));
        LinearLayout rights = Ui.group(a);
        boolean first = true;
        for (Permission p : s.permissions()) {
            if (!first) rights.addView(Ui.separator(a, 16));
            first = false;
            LinearLayout row = Ui.row(a, 10);
            row.setMinimumHeight(Ui.dp(a, 48));
            row.setPadding(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 6));
            row.addView(Ui.body(a, p.label), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.text(a, "✓", 16, Ui.ACCENT_TEXT, NexFleetApp.get().semibold()));
            rights.addView(row);
        }
        col.addView(rights);
        col.addView(footer("Le serveur vérifie chaque action, quel que soit l'affichage."));

        // apparence
        col.addView(header("Apparence"));
        LinearLayout look = Ui.group(a);
        LinearLayout opaque = Ui.groupRow(a, R.drawable.ic_tab_cube, 0xFF00A592, "Réduire la transparence", null, null);
        Switch sw = new Switch(a);
        sw.setChecked(Ui.opaque());
        sw.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{0xFF34C759, 0x33767680}));
        sw.setContentDescription("Réduire la transparence");
        sw.setOnCheckedChangeListener((b, on) -> {
            Ui.setOpaque(a, on);
            a.recreate();   // barres et panneaux redessinés en opaque
        });
        opaque.addView(sw);
        look.addView(opaque);
        col.addView(look);
        col.addView(footer("Barre d'onglets et panneaux de la carte opaques : meilleure lisibilité en plein soleil."));

        // appareil
        col.addView(header("Appareil et environnement"));
        LinearLayout device = Ui.group(a);
        device.addView(Ui.groupRow(a, R.drawable.ic_tab_track, 0xFF0088FF, "Émetteur GPS", TrackingState.of(a).deviceId(), null));
        device.addView(Ui.separator(a, 58));
        device.addView(Ui.groupRow(a, R.drawable.ic_tab_map, 0xFF6155F5, "Environnement", s.demo ? "Démonstration"
                : android.net.Uri.parse(cm.camtel.nextfleet.mobile.data.ServerConfig.url(a)).getHost(), null));
        device.addView(Ui.separator(a, 58));
        device.addView(Ui.groupRow(a, R.drawable.ic_tab_profile, 0xFF8E8E93, "Version", "0.6.0", null));
        col.addView(device);
        col.addView(footer("Police Inter (SIL OFL 1.1) · 3D Google Filament (Apache 2.0) · cartes MapLibre (BSD) et OpenStreetMap."));

        if (!s.demo) {
            LinearLayout security = Ui.group(a);
            security.addView(Ui.groupRow(a, R.drawable.ic_tab_profile, 0xFF8E8E93, "Changer mon mot de passe", null,
                    v -> a.startActivity(new android.content.Intent(a, ChangePasswordActivity.class))));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = Ui.dp(a, 18);
            col.addView(security, sp);
        }

        // déconnexion, seule dans son groupe
        LinearLayout out = Ui.group(a);
        TextView logout = Ui.text(a, "Se déconnecter", 17, Ui.RED, NexFleetApp.get().regular());
        logout.setGravity(Gravity.CENTER);
        logout.setMinHeight(Ui.dp(a, 52));
        logout.setOnClickListener(v -> Ui.alert(a, "Se déconnecter de NexFleet ?",
                TrackingState.of(a).active() ? "La mission en cours continue : la localisation reste active jusqu'à sa clôture."
                        : "Votre session sera fermée sur ce téléphone.", "Annuler", "Se déconnecter", true, a::logout));
        out.addView(logout, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 52)));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Ui.dp(a, 18);
        col.addView(out, olp);

        scroll = new ScrollView(a);
        scroll.setClipToPadding(false);
        scroll.addView(col);
        Ui.applyInsets(scroll, true, false);
    }

    private TextView header(String s) {
        TextView t = Ui.text(a, s.toUpperCase(java.util.Locale.FRANCE), 13, Ui.MUTED, NexFleetApp.get().regular());
        t.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), 0, 0);
        return t;
    }

    private TextView footer(String s) {
        TextView t = Ui.muted(a, s, 13);
        t.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        return t;
    }

    @Override
    public String title() {
        return "Profil";
    }

    @Override
    public int icon() {
        return R.drawable.ic_tab_profile;
    }

    @Override
    public View view() {
        return scroll;
    }
}
