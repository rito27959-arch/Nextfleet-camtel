package cm.camtel.nextfleet.mobile.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.data.Catalogue;

/** Catalogue 3D embarqué : silhouettes de la flotte par catégorie et modèle de démonstration, avec leurs licences. */
final class CataloguePage implements Page {

    private final MainActivity a;
    private final ScrollView scroll;

    CataloguePage(MainActivity a) {
        this.a = a;
        LinearLayout col = Ui.column(a, 12);
        col.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, 24));
        col.addView(Ui.largeTitle(a, "Catalogue 3D"));
        col.addView(Ui.muted(a, "Rendu Google Filament. Seuls des modèles à licence redistribuable sont embarqués.", 14));
        for (Catalogue.Entry e : NexFleetApp.get().catalogue().entries) {
            LinearLayout c = Ui.card(a);
            LinearLayout top = Ui.row(a, 8);
            top.addView(Ui.headline(a, e.label), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(Ui.pill(a, e.category, Ui.ACCENT_TEXT));
            c.addView(top);
            TextView lic = Ui.muted(a, e.licence + " · " + e.author, 13);
            c.addView(lic);
            c.addView(Ui.button(a, "Voir en 3D", Ui.Style.TINTED, v -> ModelViewerActivity.open(a, e)));
            col.addView(c);
        }
        TextView foot = Ui.muted(a, "Registre des licences : shared/assets3d/LICENCES.md. Aucun modèle de marque protégée n'est reproduit.", 12);
        col.addView(foot);
        scroll = new ScrollView(a);
        scroll.addView(col);
        Ui.applyInsets(scroll, true, false);
    }

    @Override
    public String title() {
        return "3D";
    }

    @Override
    public int icon() {
        return cm.camtel.nextfleet.mobile.R.drawable.ic_tab_cube;
    }

    @Override
    public View view() {
        return scroll;
    }
}
