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
        col.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, Ui.TAB_CLEARANCE));
        TextView title = Ui.largeTitle(a, "Catalogue 3D");
        title.setPadding(Ui.dp(a, 4), 0, 0, 0);
        col.addView(title);
        col.addView(Ui.muted(a, "Rendu Google Filament. Seuls des modèles à licence redistribuable sont embarqués.", 14));
        // liste groupée à la manière de l'App Store : pastille, nom, catégorie, chevron vers la vue 3D
        LinearLayout group = Ui.group(a);
        int i = 0;
        int[] tiles = {0xFF00A592, 0xFF0088FF, 0xFF6155F5, 0xFFFF8D28, 0xFF34C759, 0xFF8E8E93};
        for (Catalogue.Entry e : NexFleetApp.get().catalogue().entries) {
            if (i > 0) group.addView(Ui.separator(a, 58));
            LinearLayout row = Ui.groupRow(a, cm.camtel.nextfleet.mobile.R.drawable.ic_tab_cube, tiles[i % tiles.length], e.label,
                    e.category, v -> ModelViewerActivity.open(a, e));
            row.setContentDescription(e.label + ", " + e.category + ", " + e.licence + ". Ouvrir en 3D.");
            group.addView(row);
            i++;
        }
        col.addView(group);
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
