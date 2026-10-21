package cm.camtel.nextfleet.desktop;

import cm.camtel.nextfleet.desktop.data.DemoFleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.HttpFleetGateway;
import cm.camtel.nextfleet.desktop.ui.AppShell;
import cm.camtel.nextfleet.desktop.ui.ChangePasswordView;
import cm.camtel.nextfleet.desktop.ui.LoginView;
import cm.camtel.nextfleet.desktop.ui.ScreenshotTour;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Client NexFleet Windows / Linux (JavaFX).
 * <pre>
 *   (sans option)                     serveur NexFleet : adresse choisie à la connexion ou livrée avec l'application
 *   --api=https://serveur/            impose l'adresse du serveur (HTTPS obligatoire hors localhost)
 *   --demo                            mode démonstration (formation), données fictives, badge DÉMO
 *   --dark                            thème sombre au démarrage
 *   --screenshot=DOSSIER              parcours automatique et captures PNG (recette visuelle)
 * </pre>
 */
public class NextfleetDesktopApp extends Application {

    private Stage stage;
    private Scene scene;
    private FleetGateway gateway;
    private boolean dark;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Map<String, String> args = getParameters().getNamed();
        boolean demo = getParameters().getUnnamed().contains("--demo");
        dark = getParameters().getUnnamed().contains("--dark");
        cm.camtel.nextfleet.desktop.ui.Motion.setReduced(getParameters().getUnnamed().contains("--reduce-motion"));
        cm.camtel.nextfleet.desktop.ui.Appearance.setOpaque(getParameters().getUnnamed().contains("--opaque"));
        cm.camtel.nextfleet.desktop.ui.FleetTrackingView.configureTiles(args.get("tiles"));
        String shots = args.get("screenshot");
        gateway = demo
                ? new DemoFleetGateway(Clock.systemUTC(), shots != null ? Duration.ZERO : Duration.ofMillis(350))
                : new HttpFleetGateway(serverUri(args.get("api")));

        loadFonts();
        scene = new Scene(new javafx.scene.layout.Pane(), 1440, 900);
        scene.getStylesheets().add(getClass().getResource("nextfleet.css").toExternalForm());
        stage.setTitle("NexFleet — CAMTEL" + (demo ? " (démonstration)" : ""));
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setScene(scene);
        showLogin(null);
        stage.show();

        if (shots != null) {
            ScreenshotTour tour = new ScreenshotTour(this, java.nio.file.Path.of(shots));
            if ("lot2".equals(args.get("tour"))) tour.startLot2();
            else tour.start();
        }
    }

    /** Adresse : --api, sinon choix enregistré, sinon adresse livrée ; à défaut l'écran de connexion la demande. */
    private static URI serverUri(String arg) {
        String url = arg != null && !arg.isBlank() ? arg : cm.camtel.nextfleet.desktop.data.ServerConfig.load();
        if (url == null || url.isBlank()) return URI.create(cm.camtel.nextfleet.desktop.data.ServerConfig.UNSET);
        try {
            return HttpFleetGateway.validate(URI.create(url.strip()));
        } catch (IllegalArgumentException e) {
            return URI.create(cm.camtel.nextfleet.desktop.data.ServerConfig.UNSET);
        }
    }

    public void showLogin(String notice) {
        setRoot(new LoginView(gateway, notice, session -> {
            if (gateway.mustChangePassword()) {
                setRoot(new ChangePasswordView(gateway, true, () -> openShell(session), this::logout));
            } else {
                openShell(session);
            }
        }));
    }

    private void openShell(cm.camtel.nextfleet.desktop.data.Models.Session session) {
        setRoot(new AppShell(gateway, session, this::logout, this::toggleTheme, () -> dark));
    }

    private void logout() {
        gateway.logout();
        showLogin("Vous êtes déconnecté. Votre session a été révoquée.");
    }

    /** Bascule de thème en fondu enchaîné (240 ms) : l'ancien rendu s'efface au-dessus du nouveau. */
    public void toggleTheme() {
        Parent root = scene.getRoot();
        if (!cm.camtel.nextfleet.desktop.ui.Motion.isReduced() && root instanceof javafx.scene.layout.Pane pane) {
            javafx.scene.image.ImageView before = new javafx.scene.image.ImageView(scene.snapshot(null));
            before.setManaged(false);
            before.setMouseTransparent(true);
            dark = !dark;
            applyTheme();
            pane.getChildren().add(before);
            javafx.animation.FadeTransition f = new javafx.animation.FadeTransition(javafx.util.Duration.millis(240), before);
            f.setToValue(0);
            f.setInterpolator(cm.camtel.nextfleet.desktop.ui.Motion.EASE_IN_OUT);
            f.setOnFinished(e -> pane.getChildren().remove(before));
            f.play();
            return;
        }
        dark = !dark;
        applyTheme();
    }

    public void setRoot(Parent root) {
        scene.setRoot(root);
        applyTheme();
        cm.camtel.nextfleet.desktop.ui.Motion.enter(root, javafx.util.Duration.ZERO, 0);
    }

    private void applyTheme() {
        Parent root = scene.getRoot();
        root.getStyleClass().remove("dark");
        if (dark) root.getStyleClass().add("dark");
        cm.camtel.nextfleet.desktop.ui.Appearance.apply(root);
    }

    public Scene scene() {
        return scene;
    }

    public Stage stage() {
        return stage;
    }

    public FleetGateway gateway() {
        return gateway;
    }

    /** Police Inter embarquée (OFL 1.1, licence jointe dans fonts/) ; police système en repli. */
    private void loadFonts() {
        for (String w : new String[]{"Inter-Regular", "Inter-Medium", "Inter-SemiBold", "Inter-Bold",
                "InterDisplay-SemiBold", "InterDisplay-Bold"}) {
            try (InputStream in = getClass().getResourceAsStream("fonts/" + w + ".otf")) {
                if (in != null) Font.loadFont(in, 14);
            } catch (java.io.IOException ignored) {
                // repli sur la police système
            }
        }
    }

    public static void main(String[] args) {
        // dates (sélecteurs, formats) à la française quel que soit le réglage du poste
        java.util.Locale.setDefault(java.util.Locale.FRANCE);
        launch(args);
    }
}
