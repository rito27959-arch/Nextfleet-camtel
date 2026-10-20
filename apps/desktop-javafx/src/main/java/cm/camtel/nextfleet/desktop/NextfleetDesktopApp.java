package cm.camtel.nextfleet.desktop;

import cm.camtel.nextfleet.desktop.data.DemoFleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.HttpFleetGateway;
import cm.camtel.nextfleet.desktop.ui.AppShell;
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
 *   --api=https://serveur/            API NexFleet (HTTPS obligatoire hors localhost)
 *   --demo                            mode démonstration, données fictives (badge DÉMO)
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
        boolean demo = getParameters().getUnnamed().contains("--demo") || !args.containsKey("api");
        dark = getParameters().getUnnamed().contains("--dark");
        String shots = args.get("screenshot");
        gateway = demo
                ? new DemoFleetGateway(Clock.systemUTC(), shots != null ? Duration.ZERO : Duration.ofMillis(350))
                : new HttpFleetGateway(URI.create(args.get("api")));

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
            new ScreenshotTour(this, java.nio.file.Path.of(shots)).start();
        }
    }

    public void showLogin(String notice) {
        setRoot(new LoginView(gateway, notice, session -> setRoot(new AppShell(gateway, session, this::logout, this::toggleTheme))));
    }

    private void logout() {
        gateway.logout();
        showLogin("Vous êtes déconnecté. Votre session a été révoquée.");
    }

    public void toggleTheme() {
        dark = !dark;
        applyTheme();
    }

    public void setRoot(Parent root) {
        scene.setRoot(root);
        applyTheme();
    }

    private void applyTheme() {
        Parent root = scene.getRoot();
        root.getStyleClass().remove("dark");
        if (dark) root.getStyleClass().add("dark");
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
        for (String w : new String[]{"Regular", "Medium", "SemiBold", "Bold"}) {
            try (InputStream in = getClass().getResourceAsStream("fonts/Inter-" + w + ".otf")) {
                if (in != null) Font.loadFont(in, 14);
            } catch (java.io.IOException ignored) {
                // repli sur la police système
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
