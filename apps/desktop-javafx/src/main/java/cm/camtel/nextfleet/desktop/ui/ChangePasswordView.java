package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Première connexion avec un mot de passe provisoire : l'utilisateur choisit le sien avant d'accéder à quoi que ce soit
 * (le serveur refuse toute autre requête tant que ce n'est pas fait). Règles affichées et vérifiées en direct.
 */
public final class ChangePasswordView extends StackPane {

    private final PasswordField current = new PasswordField();
    private final PasswordField next = new PasswordField();
    private final PasswordField confirm = new PasswordField();
    private final Label ruleLength = Ui.label("", "footnote");
    private final Label ruleMix = Ui.label("", "footnote");
    private final Label ruleSame = Ui.label("", "footnote");
    private final Label error = Ui.label("", "error-text");
    private final Button save = new Button("Enregistrer et continuer");

    public ChangePasswordView(FleetGateway gateway, boolean forced, Runnable onDone, Runnable onLogout) {
        getStyleClass().add("login-root");
        StackPane icon = new StackPane(Icons.stroke(Icons.LOCK, 30, "white"));
        icon.getStyleClass().add("app-glyph");
        icon.setStyle("-fx-min-width: 64; -fx-min-height: 64; -fx-max-width: 64; -fx-max-height: 64; -fx-background-radius: 15;");
        Label title = Ui.label("Choisissez votre mot de passe", "large-title");
        title.setWrapText(true);
        Label hint = Ui.label(forced ? "Vous vous êtes connecté avec un mot de passe provisoire remis par l'administration. "
                + "Remplacez-le par un mot de passe personnel que vous seul connaissez." : "Votre nouveau mot de passe remplace l'actuel sur tous vos appareils.",
                "body", "muted");
        hint.setWrapText(true);

        current.setPromptText(forced ? "Mot de passe provisoire" : "Mot de passe actuel");
        next.setPromptText("Nouveau mot de passe");
        confirm.setPromptText("Confirmer le nouveau mot de passe");
        current.setAccessibleText(current.getPromptText());
        next.setAccessibleText("Nouveau mot de passe");
        confirm.setAccessibleText("Confirmation du nouveau mot de passe");
        Region h1 = new Region();
        h1.getStyleClass().add("hairline");
        Region h2 = new Region();
        h2.getStyleClass().add("hairline");
        VBox.setMargin(h1, new Insets(0, 0, 0, 16));
        VBox.setMargin(h2, new Insets(0, 0, 0, 16));
        VBox fields = new VBox(current, h1, next, h2, confirm);
        fields.getStyleClass().add("field-group");
        Ui.rounded(fields, 12);

        VBox rules = new VBox(4, ruleLength, ruleMix, ruleSame);
        next.textProperty().addListener((o, a, b) -> refreshRules());
        confirm.textProperty().addListener((o, a, b) -> refreshRules());
        refreshRules();

        error.setWrapText(true);
        error.setManaged(false);
        error.setVisible(false);
        save.getStyleClass().addAll("btn-primary", "btn-large");
        save.setMaxWidth(Double.MAX_VALUE);
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            error.setManaged(false);
            error.setVisible(false);
            if (!valid()) {
                fail("Le nouveau mot de passe ne respecte pas encore toutes les règles.");
                return;
            }
            save.setDisable(true);
            save.setText("Enregistrement…");
            char[] c = current.getText().toCharArray();
            char[] n = next.getText().toCharArray();
            Ui.async(() -> {
                gateway.changePassword(c, n);
                return true;
            }, ok -> onDone.run(), err -> {
                save.setDisable(false);
                save.setText("Enregistrer et continuer");
                fail(err.getMessage());
            });
        });
        Button logout = new Button("Se déconnecter");
        logout.getStyleClass().add("btn-plain");
        logout.setOnAction(e -> onLogout.run());

        VBox card = new VBox(12, icon, title, hint, fields, rules, error, save, logout);
        card.getStyleClass().add("login-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(420);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        getChildren().add(card);
        setPadding(new Insets(40));
        Motion.enter(card, javafx.util.Duration.millis(60), 16);
    }

    private boolean lengthOk() {
        return next.getText().length() >= 12;
    }

    private boolean mixOk() {
        String p = next.getText();
        return p.chars().anyMatch(Character::isLetter) && p.chars().anyMatch(ch -> !Character.isLetter(ch));
    }

    private boolean sameOk() {
        return !next.getText().isEmpty() && next.getText().equals(confirm.getText());
    }

    private boolean valid() {
        return lengthOk() && mixOk() && sameOk() && !current.getText().isEmpty();
    }

    private void refreshRules() {
        rule(ruleLength, lengthOk(), "12 caractères au moins");
        rule(ruleMix, mixOk(), "Lettres et chiffres ou symboles");
        rule(ruleSame, sameOk(), "Les deux saisies sont identiques");
    }

    private static void rule(Label l, boolean ok, String text) {
        l.setText((ok ? "✓  " : "•  ") + text);
        l.getStyleClass().removeAll("muted", "ok-text");
        l.getStyleClass().add(ok ? "ok-text" : "muted");
        l.setAccessibleText(text + (ok ? " : respecté" : " : à respecter"));
    }

    private void fail(String m) {
        error.setText(m);
        error.setManaged(true);
        error.setVisible(true);
    }
}
