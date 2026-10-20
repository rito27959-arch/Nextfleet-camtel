package cm.camtel.nextfleet.mobile.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import cm.camtel.nextfleet.domain.security.Permission;
import cm.camtel.nextfleet.domain.security.Role;
import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.data.Session;
import cm.camtel.nextfleet.mobile.tracking.TrackingState;

/** Profil : identité, droits (matrice des habilitations), appareil, environnement, déconnexion. */
final class ProfilePage implements Page {

    private final MainActivity a;
    private final ScrollView scroll;

    ProfilePage(MainActivity a) {
        this.a = a;
        Session s = NexFleetApp.get().session();
        Role r = s.primaryRole();
        LinearLayout col = Ui.column(a, 14);
        col.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, 24));
        col.addView(Ui.largeTitle(a, "Profil"));
        LinearLayout who = Ui.card(a);
        who.addView(Ui.title(a, s.displayName));
        who.addView(Ui.muted(a, (r == null ? "—" : r.label) + " · " + s.login, 14));
        if (r != null) who.addView(Ui.muted(a, r.summary, 13));
        col.addView(who);

        LinearLayout rights = Ui.card(a);
        rights.addView(Ui.overline(a, "Vos droits", Ui.MUTED));
        for (Permission p : s.permissions()) rights.addView(Ui.body(a, "✓  " + p.label));
        rights.addView(Ui.muted(a, "Le serveur vérifie chaque action, quel que soit l'affichage.", 12));
        col.addView(rights);

        LinearLayout device = Ui.card(a);
        device.addView(Ui.overline(a, "Appareil et environnement", Ui.MUTED));
        device.addView(Ui.body(a, "Émetteur GPS : " + TrackingState.of(a).deviceId()));
        device.addView(Ui.body(a, "Environnement : " + (s.demo ? "démonstration (données fictives)" : "serveur NexFleet")));
        device.addView(Ui.body(a, "Version : 0.3.0"));
        device.addView(Ui.muted(a, "Police Inter (SIL OFL 1.1) · rendu 3D Google Filament (Apache 2.0).", 12));
        col.addView(device);

        col.addView(Ui.button(a, "Se déconnecter", Ui.Style.GRAY, v -> Ui.alert(a, "Se déconnecter de NexFleet ?",
                TrackingState.of(a).active() ? "La mission en cours continue : la localisation reste active jusqu'à sa clôture."
                        : "Votre session sera fermée sur ce téléphone.", "Annuler", "Se déconnecter", a::logout)));
        scroll = new ScrollView(a);
        scroll.addView(col);
        Ui.applyInsets(scroll, true, false);
    }

    @Override
    public String title() {
        return "Profil";
    }

    @Override
    public int icon() {
        return cm.camtel.nextfleet.mobile.R.drawable.ic_tab_profile;
    }

    @Override
    public View view() {
        return scroll;
    }
}
