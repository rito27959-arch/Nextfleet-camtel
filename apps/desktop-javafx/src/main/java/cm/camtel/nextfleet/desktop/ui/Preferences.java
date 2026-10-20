package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.NotificationKind;

import java.util.EnumSet;
import java.util.Set;

/** Préférences locales de l'interface (session en cours). Aucune règle métier ni donnée sensible ici. */
public final class Preferences {

    private Preferences() { }

    private static final Set<NotificationKind> ENABLED = EnumSet.allOf(NotificationKind.class);

    public static boolean notificationsEnabled(NotificationKind k) {
        return ENABLED.contains(k);
    }

    public static void setNotificationsEnabled(NotificationKind k, boolean on) {
        if (on) ENABLED.add(k); else ENABLED.remove(k);
    }
}
