package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.domain.security.Role;
import javafx.scene.layout.StackPane;

/** Présentation des six profils : pictogramme, couleur de tuile, libellé d'espace. */
final class RoleUi {

    private RoleUi() { }

    static String icon(Role r) {
        return switch (r) {
            case FLEET_MANAGER -> Icons.GAUGE;
            case DRIVER -> Icons.ROUTE;
            case MAINTENANCE -> Icons.WRENCH;
            case CONTROLLER -> Icons.SHIELD;
            case ADMIN -> Icons.KEY;
            case AUDITOR -> Icons.EYE;
        };
    }

    static String tile(Role r) {
        return switch (r) {
            case FLEET_MANAGER -> "tile-mint";
            case DRIVER -> "tile-blue";
            case MAINTENANCE -> "tile-orange";
            case CONTROLLER -> "tile-indigo";
            case ADMIN -> "tile-gray";
            case AUDITOR -> "tile-navy";
        };
    }

    static StackPane symbol(Role r) {
        return Ui.symbolTile(icon(r), tile(r));
    }

    /** Ce que l'espace permet, en une ligne (écran de connexion, Réglages). */
    static String space(Role r) {
        return switch (r) {
            case FLEET_MANAGER -> "Parc, missions, messages et assistant";
            case DRIVER -> "Mes missions, localisation et messages";
            case MAINTENANCE -> "Atelier, entretiens et messages";
            case CONTROLLER -> "Contrôle, trajets, messages et assistant";
            case ADMIN -> "Comptes, rôles et paramètres — sans données métier";
            case AUDITOR -> "Lecture seule et journal d'audit";
        };
    }
}
