package cm.camtel.nextfleet.desktop.ui;

import javafx.scene.Parent;

/**
 * Réglage « Réduire la transparence » (équivalent du réglage d'accessibilité d'Apple) : le verre teinté des barres et
 * panneaux devient opaque. Aussi activable au lancement par {@code --opaque}.
 */
public final class Appearance {

    private static volatile boolean opaque;

    private Appearance() { }

    public static boolean isOpaque() {
        return opaque;
    }

    public static void setOpaque(boolean value) {
        opaque = value;
    }

    /** Applique la classe {@code opaque} à la racine de la scène. */
    public static void apply(Parent root) {
        if (root == null) return;
        root.getStyleClass().remove("opaque");
        if (opaque) root.getStyleClass().add("opaque");
    }
}
