package cm.camtel.nextfleet.mobile.ui;

import android.view.View;

/** Onglet de l'écran principal (vue construite une fois, notifiée à l'affichage et au masquage). */
interface Page {
    String title();

    int icon();

    View view();

    default void onShow() { }

    default void onHide() { }

    default void onDestroy() { }

    default void onLowMemory() { }
}
