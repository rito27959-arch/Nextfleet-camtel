package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.Models.NotificationKind;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Réglages — listes groupées inset à la manière de Réglages iOS : tuiles colorées, interrupteurs, contrôle segmenté,
 * valeurs secondaires alignées à droite, en-têtes et pieds de groupe explicatifs.
 * Seules des préférences d'interface sont modifiables ici ; les paramètres métier (NF-03) arrivent au lot 2.
 */
public final class SettingsView extends ScrollPane {

    public SettingsView(FleetGateway gateway, Session session, AppShell shell) {
        setFitToWidth(true);
        VBox col = new VBox(26);
        col.setMaxWidth(640);

        // Compte
        cm.camtel.nextfleet.domain.security.Role primary = session.primaryRole();
        String role = primary == null ? "—" : primary.label;
        StackPane avatar = Ui.avatar(initials(session.displayName()), 0);
        VBox who = new VBox(2, Ui.label(session.displayName(), "title3"), Ui.label(role + " · " + session.login(), "subhead", "muted"));
        HBox profile = new HBox(14, avatar, who);
        profile.setAlignment(Pos.CENTER_LEFT);
        profile.getStyleClass().add("list-row");
        profile.setStyle("-fx-min-height: 72;");
        Node signOut = Ui.listRow(null, "Se déconnecter", null, new javafx.scene.layout.Region(), shell::confirmLogout);
        ((Label) ((HBox) signOut).getChildren().get(0)).getStyleClass().add("destructive");
        List<Node> account = new java.util.ArrayList<>(List.of(profile));
        if (!gateway.isDemo()) {
            account.add(Ui.listRow(Ui.symbolTile(Icons.KEY, "tile-gray"), "Changer mon mot de passe", null, null, () -> passwordSheet(shell)));
        }
        account.add(signOut);
        col.getChildren().add(Ui.insetGroup(null, account, null));

        // Profil et droits (matrice des habilitations)
        if (primary != null) {
            List<Node> rights = new java.util.ArrayList<>();
            rights.add(Ui.listRow(RoleUi.symbol(primary), primary.label, null, null, null));
            for (cm.camtel.nextfleet.domain.security.Permission p : session.permissions()) {
                rights.add(Ui.listRow(null, p.label, null, Icons.stroke(Icons.CHECK, 14, "-nf-accent-text"), null));
            }
            col.getChildren().add(Ui.insetGroup("Vos droits", rights, primary.summary
                    + " Le serveur vérifie chaque action, quel que soit l'affichage."));
        }

        // Apparence
        Controls.Segmented theme = new Controls.Segmented(List.of("Clair", "Sombre"), shell.isDark() ? 1 : 0,
                i -> shell.setDark(i == 1));
        Controls.Switch motion = new Controls.Switch(Motion.isReduced(), "Réduire les animations", Motion::setReduced);
        Controls.Switch opaque = new Controls.Switch(Appearance.isOpaque(), "Réduire la transparence", on -> {
            Appearance.setOpaque(on);
            if (getScene() != null) Appearance.apply(getScene().getRoot());
        });
        col.getChildren().add(Ui.insetGroup("Apparence", List.of(
                Ui.listRow(Ui.symbolTile(Icons.MOON, "tile-indigo"), "Thème", null, theme, null),
                Ui.listRow(Ui.symbolTile(Icons.LAYERS, "tile-mint"), "Réduire la transparence", null, opaque, null),
                Ui.listRow(Ui.symbolTile(Icons.MOTION, "tile-blue"), "Réduire les animations", null, motion, null)),
                "Réduire la transparence rend opaques la barre latérale, la barre d'outils et les panneaux (lisibilité en plein soleil). "
                        + "Avec la réduction de mouvement, chaque écran s'affiche directement dans son état final."));

        // Notifications
        col.getChildren().add(Ui.insetGroup("Notifications", List.of(
                notifRow(NotificationKind.ECHEANCE, "Échéances", Icons.CALENDAR, "tile-orange"),
                notifRow(NotificationKind.ENTRETIEN, "Entretiens", Icons.WRENCH, "tile-red"),
                notifRow(NotificationKind.CARBURANT, "Carburant", Icons.FUEL, "tile-blue"),
                Ui.listRow(Ui.symbolTile(Icons.BELL, "tile-gray"), "Seuil d'alerte des échéances", "30 jours", null, null)),
                "Le seuil de 30 jours reprend l'ancien NexFleet ; il reste à approuver par le métier (NF-11). "
                        + "Les notifications ne contiennent aucune donnée personnelle sensible."));

        // Données
        col.getChildren().add(Ui.insetGroup("Données", List.of(
                Ui.listRow(Ui.symbolTile(Icons.SHIELD, gateway.isDemo() ? "tile-orange" : "tile-green"), "Environnement",
                        gateway.isDemo() ? "Démonstration" : "Serveur NexFleet", null, null),
                Ui.listRow(Ui.symbolTile(Icons.LOCK, "tile-navy"), "Serveur",
                        gateway instanceof cm.camtel.nextfleet.desktop.data.HttpFleetGateway h ? h.base().getHost() : "Aucun (données locales)", null, null)),
                gateway.isDemo() ? "Données fictives, refusées en production (NF-04)." : null));

        // À propos
        col.getChildren().add(Ui.insetGroup("À propos", List.of(
                Ui.listRow(Ui.symbolTile(Icons.DOC, "tile-navy"), "Version", "0.6.0", null, null),
                Ui.listRow(Ui.symbolTile(Icons.REPORT, "tile-mint"), "Référence", "Cahier des charges V4.0", null, null),
                Ui.listRow(Ui.symbolTile(Icons.ID_CARD, "tile-gray"), "Police", "Inter · SIL OFL 1.1", null, null)),
                "Design inspiré des Human Interface Guidelines d'Apple, sans reprise d'éléments propriétaires."));

        VBox content = new VBox(18, Ui.label("Réglages", "large-title"), col);
        content.getStyleClass().add("content");
        StackPane center = new StackPane(content);
        center.setAlignment(Pos.TOP_CENTER);
        content.setMaxWidth(704);
        setContent(center);
        Motion.stagger(col.getChildren());
    }

    /** Changement de mot de passe en libre-service ; les autres sessions de l'utilisateur sont fermées par le serveur. */
    private static void passwordSheet(AppShell shell) {
        javafx.scene.control.PasswordField current = new javafx.scene.control.PasswordField();
        javafx.scene.control.PasswordField next = new javafx.scene.control.PasswordField();
        javafx.scene.control.PasswordField confirm = new javafx.scene.control.PasswordField();
        FormSheet f = new FormSheet("Mot de passe", "Enregistrer", shell::closeSheet)
                .intro("12 caractères au moins, lettres et chiffres ou symboles. Vos autres sessions ouvertes seront fermées.")
                .field("Mot de passe actuel *", current).field("Nouveau mot de passe *", next).field("Confirmation *", confirm);
        f.onSubmit(() -> {
            if (!next.getText().equals(confirm.getText())) throw new IllegalArgumentException("Les deux saisies du nouveau mot de passe diffèrent.");
            if (next.getText().length() < 12) throw new IllegalArgumentException("Le nouveau mot de passe doit contenir au moins 12 caractères.");
            shell.gateway().changePassword(current.getText().toCharArray(), next.getText().toCharArray());
            return true;
        }, ok -> {
            shell.closeSheet();
            shell.toasts().success("Mot de passe modifié", "Utilisez-le dès votre prochaine connexion.");
        });
        shell.openSheet(f, 420);
    }

    private static Node notifRow(NotificationKind k, String title, String icon, String tile) {
        Controls.Switch sw = new Controls.Switch(Preferences.notificationsEnabled(k), "Notifications " + title,
                on -> Preferences.setNotificationsEnabled(k, on));
        return Ui.listRow(Ui.symbolTile(icon, tile), title, null, sw, null);
    }

    private static String initials(String name) {
        String[] p = name.replaceAll("\\(.*\\)", "").trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String s : p) if (!s.isEmpty() && b.length() < 2) b.append(Character.toUpperCase(s.charAt(0)));
        return b.toString();
    }
}
