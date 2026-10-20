package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.NextfleetDesktopApp;
import cm.camtel.nextfleet.desktop.data.DemoFleetGateway;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Parcours automatique de recette visuelle (REC-11, REC-UX-01) : enchaîne les écrans réellement exécutés
 * et enregistre des captures 1440×900. Aucun écran n'est fabriqué pour la capture.
 */
public final class ScreenshotTour {

    private final NextfleetDesktopApp app;
    private final Path dir;
    private final Deque<Runnable> steps = new ArrayDeque<>();

    public ScreenshotTour(NextfleetDesktopApp app, Path dir) {
        this.app = app;
        this.dir = dir;
    }

    public void start() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        add(() -> shot("01-connexion"));
        add(() -> login("gestionnaire"));
        add(() -> shot("02-tableau-de-bord"));
        add(() -> app.toggleTheme());
        add(() -> shot("02b-tableau-de-bord-sombre"));
        add(() -> app.toggleTheme());
        add(() -> shell().show(AppShell.Section.VEHICLES));
        add(() -> shot("03-liste-vehicules"));
        add(() -> find(VehiclesView.class).selectFirstForTest());
        add(() -> shot("10-fiche-vehicule-panneau"));
        add(() -> shell().show(AppShell.Section.VEHICLES));
        add(() -> find(VehiclesView.class).openFormForTest());
        add(() -> find(VehicleFormView.class).fillInvalidForTest());
        add(() -> shot("04-ajout-vehicule-erreurs"));
        add(() -> shell().openVehicles("hilux"));
        add(() -> shot("03b-recherche-hilux"));
        add(() -> ((DemoFleetGateway) app.gateway()).setNetworkDown(true));
        add(() -> shell().show(AppShell.Section.VEHICLES));
        add(() -> shot("etat-erreur-reseau"));
        add(() -> ((DemoFleetGateway) app.gateway()).setNetworkDown(false));
        add(() -> shell().show(AppShell.Section.FUEL));
        add(() -> shot("etat-module-non-disponible"));
        add(() -> app.showLogin(null));
        add(() -> login("auditeur"));
        add(() -> shell().show(AppShell.Section.VEHICLES));
        add(() -> shot("etat-droits-auditeur"));
        add(() -> app.showLogin("Votre session a expiré. Reconnectez-vous pour continuer."));
        add(() -> shot("01b-session-expiree"));
        add(Platform::exit);
        next();
    }

    private void add(Runnable r) {
        steps.add(r);
    }

    private void next() {
        Runnable r = steps.poll();
        if (r == null) return;
        r.run();
        PauseTransition p = new PauseTransition(Duration.millis(900));
        p.setOnFinished(e -> next());
        p.play();
    }

    private void login(String user) {
        LoginView v = (LoginView) app.scene().getRoot();
        v.prefill(user, "demo");
        v.submitForTest();
    }

    private AppShell shell() {
        return (AppShell) app.scene().getRoot();
    }

    private <T extends Node> T find(Class<T> type) {
        T t = search(app.scene().getRoot(), type);
        if (t == null) throw new IllegalStateException("Vue absente : " + type.getSimpleName());
        return t;
    }

    private static <T extends Node> T search(Node n, Class<T> type) {
        if (type.isInstance(n)) return type.cast(n);
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            T t = search(sp.getContent(), type);
            if (t != null) return t;
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                T t = search(c, type);
                if (t != null) return t;
            }
        }
        return null;
    }

    private void shot(String name) {
        WritableImage img = app.scene().snapshot(null);
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", dir.resolve(name + ".png").toFile());
            System.out.println("capture " + name + ".png");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
