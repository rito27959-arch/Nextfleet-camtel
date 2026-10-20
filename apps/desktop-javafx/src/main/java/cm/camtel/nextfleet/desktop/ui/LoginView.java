package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** Écran 01 — Connexion (NF-01) : message d'échec générique, état d'attente, reprise après expiration. */
public final class LoginView extends HBox {

    private final TextField login = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label error = Ui.label("", "error-text");
    private final Button submit = new Button("Se connecter");
    private final ProgressIndicator spinner = new ProgressIndicator();

    public LoginView(FleetGateway gateway, String notice, Consumer<Session> onLogin) {
        getStyleClass().add("login-root");

        // ---- panneau identité
        Label brand = Ui.label("NexFleet", "t-28", "w-700");
        HBox brandRow = new HBox(12, Icons.logo(40), brand);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        Label pitch = Ui.label("Votre flotte, en un regard.", "t-36", "w-700");
        pitch.setWrapText(true);
        Label sub = Ui.label("Disponibilité, coûts et alertes du parc automobile CAMTEL. L'essentiel, au même endroit.", "t-16", "muted");
        sub.setWrapText(true);
        Label camtel = Ui.label("CAMTEL · Business Unit Transport", "t-12", "muted");
        VBox brandPane = new VBox(28, brandRow, Ui.spacer(), pitch, sub, Ui.spacer(), camtel);
        brandPane.getStyleClass().add("login-brand");
        brandPane.setPrefWidth(520);
        brandPane.setMinWidth(380);

        // ---- formulaire
        Label title = Ui.label("Connexion", "t-28", "w-700");
        Label hint = Ui.label("Gestion intelligente de votre flotte automobile", "muted");
        login.setPromptText("Identifiant");
        login.setAccessibleText("Identifiant");
        password.setPromptText("Mot de passe");
        password.setAccessibleText("Mot de passe");
        submit.getStyleClass().add("btn-primary");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setDefaultButton(true);
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);
        error.setWrapText(true);
        error.setManaged(false);

        VBox form = new VBox(8, title, hint, gap(16), Ui.label("Identifiant", "field-label"), login,
                gap(6), Ui.label("Mot de passe", "field-label"), password, error, gap(14),
                new StackPane(submit, spinner));
        StackPane.setAlignment(spinner, Pos.CENTER_RIGHT);
        StackPane.setMargin(spinner, new Insets(0, 14, 0, 0));
        if (notice != null) {
            Label n = Ui.label(notice, "t-12");
            n.setWrapText(true);
            VBox b = new VBox(n);
            b.getStyleClass().add("banner-info");
            form.getChildren().add(2, b);
        }
        if (gateway.isDemo()) {
            Label d = new Label("Mode démonstration — données fictives. Comptes : gestionnaire, controleur, auditeur ; "
                    + "mot de passe : demo. Ce mode est refusé en production (NF-04).");
            d.setWrapText(true);
            VBox demo = new VBox(d);
            demo.getStyleClass().add("demo-hint");
            form.getChildren().add(demo);
        }
        Label sso = Ui.label("Connexion fédérée CAMTEL (OIDC) : disponible après accord DSIR.", "t-12", "muted");
        sso.setWrapText(true);
        form.getChildren().add(sso);
        form.getStyleClass().add("login-card");
        form.setMaxWidth(420);
        form.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        form.setPrefWidth(420);

        StackPane right = new StackPane(form);
        right.setPadding(new Insets(32));
        HBox.setHgrow(right, Priority.ALWAYS);
        getChildren().addAll(brandPane, right);

        submit.setOnAction(e -> attempt(gateway, onLogin));
        password.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) attempt(gateway, onLogin);
        });
    }

    private void attempt(FleetGateway gateway, Consumer<Session> onLogin) {
        clearError();
        boolean ok = true;
        if (login.getText().isBlank()) {
            login.getStyleClass().add("field-error");
            ok = false;
        }
        if (password.getText().isEmpty()) {
            password.getStyleClass().add("field-error");
            ok = false;
        }
        if (!ok) {
            showError("Renseignez l'identifiant et le mot de passe.");
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
                    : "Identifiants invalides ou compte indisponible.");
            password.requestFocus();
        });
    }

    /** Pré-remplit l'identifiant (capture d'écran et reprise de session). */
    public void prefill(String user, String pass) {
        login.setText(user);
        password.setText(pass);
    }

    public void submitForTest() {
        submit.fire();
    }

    private void setBusy(boolean busy) {
        submit.setDisable(busy);
        submit.setText(busy ? "Connexion…" : "Se connecter");
        spinner.setVisible(busy);
        login.setDisable(busy);
        password.setDisable(busy);
    }

    private void showError(String m) {
        error.setText(m);
        error.setManaged(true);
        error.setAccessibleText("Erreur : " + m);
    }

    private void clearError() {
        error.setText("");
        error.setManaged(false);
        login.getStyleClass().remove("field-error");
        password.getStyleClass().remove("field-error");
    }

    private static javafx.scene.layout.Region gap(double h) {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.setMinHeight(h);
        return r;
    }
}
