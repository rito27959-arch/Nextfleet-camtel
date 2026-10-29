package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.data.Models;

/**
 * Papiers du véhicule, à montrer lors d'un contrôle routier : chaque document en grand, avec son état d'un coup d'œil
 * (valide, à renouveler, expiré) et sa date de fin. Les originaux restent obligatoires : l'écran le rappelle.
 */
public final class PapersActivity extends Activity {

    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE);
    private LinearLayout col;

    static Intent open(Activity a, String registration) {
        return new Intent(a, PapersActivity.class).putExtra("registration", registration);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Motion.detailScreen(this);
        if (NexFleetApp.get().session() == null) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.backdrop());
        LinearLayout bar = Ui.row(this, 8);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView back = Ui.text(this, "‹ Retour", 17, Ui.ACCENT_TEXT, NexFleetApp.get().regular());
        back.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        root.addView(bar);
        Ui.applyInsets(bar, true, false);
        col = Ui.column(this, 12);
        col.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 32));
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        Ui.applyInsets(sv, false, true);
        setContentView(root);

        col.addView(Ui.largeTitle(this, "Papiers"));
        col.addView(Ui.skeleton(this, 96, 22));
        col.addView(Ui.skeleton(this, 96, 22));
        col.addView(Ui.skeleton(this, 96, 22));
        String wanted = getIntent().getStringExtra("registration");
        Ui.async(() -> {
            List<Models.VehicleRef> vehicles = NexFleetApp.get().repository().vehicles();
            Models.VehicleRef pick = null;
            for (Models.VehicleRef v : vehicles) {
                if (wanted != null && norm(v.registration).equals(norm(wanted))) pick = v;
            }
            if (pick == null && !vehicles.isEmpty()) pick = vehicles.get(0);
            if (pick == null) throw new IllegalStateException("Aucun véhicule dans votre périmètre.");
            return new Object[]{pick, NexFleetApp.get().repository().vehicleDueDates(pick.id)};
        }, r -> render((Models.VehicleRef) r[0], castDues(r[1])), e -> {
            col.removeAllViews();
            col.addView(Ui.largeTitle(this, "Papiers"));
            col.addView(Ui.text(this, e.getMessage(), 15, Ui.RED, NexFleetApp.get().regular()));
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Models.DueRef> castDues(Object o) {
        return (List<Models.DueRef>) o;
    }

    private void render(Models.VehicleRef v, List<Models.DueRef> dues) {
        col.removeAllViews();
        col.addView(Ui.largeTitle(this, "Papiers"));
        TextView plate = Ui.text(this, v.registration, 28, Ui.TEXT, NexFleetApp.get().display());
        Ui.tabular(plate);
        col.addView(plate);
        col.addView(Ui.muted(this, v.label, 15));
        int docs = 0;
        for (Models.DueRef d : dues) {
            if ("VIDANGE".equals(d.kind)) continue;
            col.addView(card(d));
            docs++;
        }
        if (docs == 0) {
            col.addView(Ui.emptyState(this, cm.camtel.nextfleet.mobile.R.drawable.ic_tab_profile, "Aucun document enregistré",
                    "Votre gestionnaire n'a pas encore saisi l'assurance, la vignette ou le contrôle technique de ce véhicule."));
        }
        TextView note = Ui.muted(this, "Cet écran résume les dates connues de NexFleet. Lors d'un contrôle, les documents originaux restent obligatoires.", 13);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), 0);
        col.addView(note);
        Motion.stagger(col);
    }

    private View card(Models.DueRef d) {
        boolean expired = "EXPIRE".equals(d.level);
        boolean soon = "A_ECHEANCE".equals(d.level);
        int tone = expired ? Ui.RED : soon ? Ui.ORANGE : Ui.GREEN;
        LinearLayout c = Ui.card(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = Ui.text(this, expired ? "✕" : soon ? "!" : "✓", 22, Color.WHITE, NexFleetApp.get().semibold());
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.rounded(this, tone, 999));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        mp.rightMargin = Ui.dp(this, 16);
        c.addView(mark, mp);
        LinearLayout text = Ui.column(this, 3);
        text.addView(Ui.text(this, d.label, 20, Ui.TEXT, NexFleetApp.get().semibold()));
        String when = expired ? "Expiré le " + LONG.format(d.dueOn) : "Valable jusqu'au " + LONG.format(d.dueOn);
        text.addView(Ui.muted(this, when, 15));
        String state = expired ? "Expiré depuis " + (-d.daysLeft) + " j" : d.daysLeft == 0 ? "Dernier jour" : "Encore " + d.daysLeft + " j";
        TextView st = Ui.text(this, state, 13, tone, NexFleetApp.get().semibold());
        Ui.tabular(st);
        text.addView(st);
        c.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        c.setContentDescription(d.label + ", " + when + ", " + state);
        return c;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    @Override
    public void finish() {
        super.finish();
        Motion.detailClosing(this);
    }
}
