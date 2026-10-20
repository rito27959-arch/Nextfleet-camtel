package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * Écran 01 — Connexion (NF-01), composition iOS : icône d'application, grand titre, champs regroupés dans un même
 * conteneur arrondi séparé par un filet, bouton plein de 44 px avec indicateur d'activité et libellé « Connexion… »,
 * lien « Mot de passe oublié ? ». Message d'échec générique et secousse horizontale en cas de refus.
 */
public final class LoginView extends StackPane {

    private final TextField login = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label error = Ui.label("", "error-text");
    private final Button submit = new Button("Se connecter");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final VBox form;

    public LoginView(FleetGateway gateway, String notice, Consumer<Session> onLogin) {
        getStyleClass().add("login-root");

        Label brand = Ui.label("NexFleet", "title1");
        HBox brandRow = new HBox(12, Icons.logo(36), brand);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        Label pitch = Ui.label("Votre flotte,\nen un regard.", "large-title");
        pitch.setStyle("-fx-font-size: 44px;");
        pitch.setWrapText(true);
        Label sub = Ui.label("Disponibilité, coûts et alertes du parc automobile CAMTEL. L'essentiel, au même endroit.", "body", "muted");
        sub.setWrapText(true);
        Label camtel = Ui.label("CAMTEL · Business Unit Transport", "footnote", "muted");
        VBox brandPane = new VBox(24, brandRow, Ui.spacer(), pitch, sub, Ui.spacer(), camtel);
        brandPane.getStyleClass().add("login-brand");
        brandPane.setPrefWidth(520);
        brandPane.setMinWidth(380);

        StackPane appIcon = new StackPane(Icons.logo(40));
        appIcon.getStyleClass().add("app-glyph");
        appIcon.setStyle("-fx-min-width: 64; -fx-min-height: 64; -fx-max-width: 64; -fx-max-height: 64; -fx-background-radius: 15;");
        Label title = Ui.label("Connexion", "large-title");
        Label hint = Ui.label("Utilisez votre compte NexFleet.", "body", "muted");

        login.setPromptText("Identifiant");
        login.setAccessibleText("Identifiant");
        password.setPromptText("Mot de passe");
        password.setAccessibleText("Mot de passe");
        Region hair = new Region();
        hair.getStyleClass().add("hairline");
        VBox.setMargin(hair, new Insets(0, 0, 0, 16));
        VBox fields = new VBox(login, hair, password);
        fields.getStyleClass().add("field-group");
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(fields.widthProperty());
        clip.heightProperty().bind(fields.heightProperty());
        fields.setClip(clip);

        submit.getStyleClass().addAll("btn-primary", "btn-large");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setDefaultButton(true);
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        spinner.setStyle("-fx-progress-color: white;");
        error.setWrapText(true);
        error.setManaged(false);
        error.setVisible(false);

        Button forgot = new Button("Mot de passe oublié ?");
        forgot.getStyleClass().add("btn-plain");
        forgot.setOnAction(e -> AlertDialog.show(this, Ui.symbolTile(Icons.LOCK, "tile-gray"), "Mot de passe oublié",
                "Contactez l'administrateur NexFleet de votre direction. La réinitialisation en libre-service "
                        + "arrivera avec l'annuaire CAMTEL (NF-50).",
                List.of(AlertDialog.Action.preferred("OK", () -> { }))));

        form = new VBox(12, appIcon, gap(4), title, hint, gap(10), fields, error, gap(4), submit, forgot);
        form.setAlignment(Pos.CENTER_LEFT);
        if (notice != null) {
            Label n = Ui.label(notice, "footnote");
            n.setWrapText(true);
            VBox b = new VBox(n);
            b.getStyleClass().add("banner-info");
            form.getChildren().add(4, b);
        }
        if (gateway.isDemo()) {
            form.getChildren().add(demoAccounts());
        }
        Label sso = Ui.label("Connexion fédérée CAMTEL (OIDC) disponible après accord DSIR.", "footnote", "muted");
        sso.setWrapText(true);
        form.getChildren().add(sso);
        form.getStyleClass().add("login-card");
        form.setMaxWidth(380);
        form.setPrefWidth(380);
        form.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane right = new StackPane(form);
        right.setPadding(new Insets(40));
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox split = new HBox(brandPane, right);
        getChildren().add(split);

        submit.setOnAction(e -> attempt(gateway, onLogin));
        password.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) attempt(gateway, onLogin);
        });
        Motion.enter(form, javafx.util.Duration.millis(80), 18);
        Motion.stagger(List.of(brandRow, pitch, sub));
        Motion.pressable(submit);
    }

    /** Six comptes fictifs, un par profil de la matrice des habilitations ; un clic remplit le formulaire. */
    private Node demoAccounts() {
        String[][] accounts = {{"gestionnaire", "FLEET_MANAGER"}, {"conducteur", "DRIVER"}, {"maintenance", "MAINTENANCE"},
                {"controleur", "CONTROLLER"}, {"admin", "ADMIN"}, {"auditeur", "AUDITOR"}};
        VBox list = new VBox();
        list.getStyleClass().add("inset-group");
        for (int i = 0; i < accounts.length; i++) {
            cm.camtel.nextfleet.domain.security.Role r = cm.camtel.nextfleet.domain.security.Role.valueOf(accounts[i][1]);
            String login = accounts[i][0];
            VBox text = new VBox(1, Ui.label(r.label, "role-label"), Ui.label(RoleUi.space(r), "role-summary"));
            Label id = Ui.label(login, "footnote", "muted");
            id.setMinWidth(Region.USE_PREF_SIZE);
            text.setMinWidth(0);
            HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);
            HBox row = new HBox(12, RoleUi.symbol(r), text, id);
            row.getStyleClass().add("demo-account");
            row.setOnMouseClicked(e -> {
                prefill(login, "demo");
                submit.requestFocus();
            });
            row.setAccessibleText("Compte de démonstration " + r.label + ", identifiant " + login);
            if (i > 0) {
                Region sep = new Region();
                sep.getStyleClass().add("row-separator");
                VBox.setMargin(sep, new Insets(0, 0, 0, 56));
                list.getChildren().add(sep);
            }
            list.getChildren().add(row);
        }
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(list.widthProperty());
        clip.heightProperty().bind(list.heightProperty());
        list.setClip(clip);
        Label head = Ui.label("COMPTES DE DÉMONSTRATION · MOT DE PASSE « demo »", "section-header");
        head.setPadding(new Insets(8, 0, 6, 4));
        return new VBox(head, list);
    }

    private void attempt(FleetGateway gateway, Consumer<Session> onLogin) {
        clearError();
        if (login.getText().isBlank() || password.getText().isEmpty()) {
            showError("Renseignez l'identifiant et le mot de passe.");
            shake(form);
            return;
        }
        setBusy(true);
        String l = login.getText();
        char[] p = password.getText().toCharArray();
        Ui.async(() -> gateway.login(l, p), s -> {
            setBusy(false);
            password.clear();
            onLogin.accept(s);
        }, err -> {
            setBusy(false);
            password.clear();
            showError(err.kind() == GatewayException.Kind.NETWORK ? err.getMessage()
                    : "Identifiant ou mot de passe incorrect.");
            shake(form);
            password.requestFocus();
        });
    }

    public void prefill(String user, String pass) {
        login.setText(user);
        password.setText(pass);
    }

    public void submitForTest() {
        submit.fire();
    }

    /** Pour la recette visuelle : ouvre l'alerte « Mot de passe oublié ». */
    public void forgotForTest() {
        form.getChildren().stream().filter(n -> n instanceof Button b && b.getText().startsWith("Mot de passe"))
                .findFirst().ifPresent(n -> ((Button) n).fire());
    }

    private static void shake(Node n) {
        if (Motion.isReduced()) return;
        javafx.animation.Timeline t = new javafx.animation.Timeline();
        double[] xs = {0, -10, 9, -7, 5, -3, 0};
        for (int i = 0; i < xs.length; i++) {
            t.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.millis(i * 55),
                    new javafx.animation.KeyValue(n.translateXProperty(), xs[i], Motion.EASE_IN_OUT)));
        }
        t.play();
    }

    private void setBusy(boolean busy) {
        submit.setDisable(busy);
        submit.setText(busy ? "Connexion…" : "Se connecter");
        submit.setGraphic(busy ? spinner : null);
        login.setDisable(busy);
        password.setDisable(busy);
    }

    private void showError(String m) {
        error.setText(m);
        error.setManaged(true);
        error.setVisible(true);
        error.setAccessibleText("Erreur : " + m);
    }

    private void clearError() {
        error.setText("");
        error.setManaged(false);
        error.setVisible(false);
    }

    private static Region gap(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        return r;
    }
}
