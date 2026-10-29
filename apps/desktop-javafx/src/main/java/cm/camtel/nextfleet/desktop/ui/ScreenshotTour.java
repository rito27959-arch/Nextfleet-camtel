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
import java.util.function.Consumer;

/**
 * Parcours automatique de recette visuelle (REC-11, REC-UX-01) : enchaîne les écrans réellement exécutés,
 * enregistre des captures 1440×900 et des GIF des animations. Aucun écran n'est fabriqué pour la capture.
 */
public final class ScreenshotTour {

    private final NextfleetDesktopApp app;
    private final Path dir;
    private final Deque<Consumer<Runnable>> steps = new ArrayDeque<>();

    public ScreenshotTour(NextfleetDesktopApp app, Path dir) {
        this.app = app;
        this.dir = dir;
    }

    /** Parcours du lot 2 : six profils, missions et carte, messagerie, assistant, administration, audit. */
    public void startLot2() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        add(() -> { });
        add(() -> shot("20-connexion-six-profils"));
        add(() -> login("gestionnaire"));
        record("anim-07-trace-mission-en-direct", () -> shell().show(AppShell.Section.MISSIONS), 28, 50);
        add(() -> { });
        add(() -> shot("21-missions-carte-en-direct"));
        add(() -> find(MissionsView.class).selectReference("MIS-2026-012"));
        add(() -> shot("21b-mission-terminee"));
        add(() -> { var n = app.scene().getRoot().lookup(".map-card"); if (n instanceof TrackMap t) t.seek(0.55); });
        add(() -> shot("21e-relecture-trajet"));
        add(() -> find(MissionsView.class).selectReference("MIS-2026-014"));
        add(app::toggleTheme);
        add(() -> shot("21c-missions-sombre"));
        add(app::toggleTheme);
        add(() -> shell().show(AppShell.Section.TRACKING));
        add(() -> { });
        add(() -> { });
        add(() -> shot("21d-suivi-gps-flotte"));
        add(() -> shell().show(AppShell.Section.CATALOGUE));
        add(() -> { });
        add(() -> shot("23-catalogue-3d"));
        add(() -> shell().show(AppShell.Section.MESSAGES));
        add(() -> shot("22-messages"));
        add(() -> find(MessagesView.class).typeAndSendForTest("Bien noté, merci Alain. Bonne fin de route."));
        add(() -> shot("22b-message-envoye"));
        add(() -> ((DemoFleetGateway) app.gateway()).setNetworkDown(true));
        add(() -> find(MessagesView.class).typeAndSendForTest("Peux-tu m'appeler à l'arrivée ?"));
        add(() -> shot("22c-message-non-envoye"));
        add(() -> ((DemoFleetGateway) app.gateway()).setNetworkDown(false));
        add(app::toggleTheme);
        add(() -> shot("22d-messages-sombre"));
        add(app::toggleTheme);
        add(() -> shell().show(AppShell.Section.ASSISTANT));
        add(() -> shot("23-assistant"));
        add(() -> find(AssistantView.class).askForTest("Coût des véhicules CE 3205 KJ, LT 5520 CE et CE 4401 LT ce mois-ci ?"));
        add(() -> find(AssistantView.class).openSourcesForTest());
        add(() -> shot("23b-assistant-reponse-sources"));
        add(() -> find(AssistantView.class).askForTest("Supprime le véhicule CE 3205 KJ"));
        add(() -> shot("23c-assistant-refus-ecriture"));
        add(() -> app.showLogin(null));
        add(() -> login("conducteur"));
        add(() -> shot("24-conducteur-mes-missions"));
        record("anim-08-alerte-localisation", () -> find(DriverHomeView.class).startForTest(), 14, 40);
        add(() -> shot("24b-alerte-information-localisation"));
        add(() -> app.scene().getRoot().lookupAll(".alert-button").stream()
                .filter(b -> ((javafx.scene.control.Button) b).getText().equals("Démarrer")).findFirst()
                .ifPresent(b -> ((javafx.scene.control.Button) b).fire()));
        add(() -> { });
        add(() -> shot("24c-conducteur-mission-en-cours"));
        add(() -> app.showLogin(null));
        add(() -> login("maintenance"));
        add(() -> shot("25-maintenance-atelier"));
        add(() -> app.scene().getRoot().lookupAll(".list-row.clickable").stream().findFirst()
                .ifPresent(n -> n.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                        javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, false, null))));
        add(() -> shot("25b-atelier-detail-intervention"));
        add(() -> app.showLogin(null));
        add(() -> login("controleur"));
        add(() -> shell().show(AppShell.Section.MISSIONS));
        add(() -> shot("26-controleur-trajets"));
        add(() -> app.showLogin(null));
        add(() -> login("admin"));
        add(() -> shot("27-admin-comptes-roles"));
        add(() -> find(UsersView.class).showTab(1));
        add(() -> shot("27b-admin-matrice-habilitations"));
        add(() -> find(UsersView.class).showTab(2));
        add(() -> shot("27c-admin-activation-assistant"));
        add(() -> app.showLogin(null));
        add(() -> login("auditeur"));
        add(() -> shell().show(AppShell.Section.AUDIT));
        add(() -> shot("28-auditeur-journal-chaine"));
        add(() -> shell().show(AppShell.Section.SETTINGS));
        add(() -> shot("29-auditeur-reglages-droits"));
        add(Platform::exit);
        next();
    }

    public void start() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        add(() -> { });   // laisse l'animation d'entrée se terminer
        add(() -> shot("01-connexion"));
        add(() -> ((LoginView) app.scene().getRoot()).forgotForTest());
        add(() -> shot("01d-alerte-mot-de-passe-oublie"));
        add(() -> app.scene().getRoot().lookupAll(".alert-button").stream().findFirst()
                .ifPresent(b -> ((javafx.scene.control.Button) b).fire()));
        add(() -> login("gestionnaire"));
        record("anim-01-entree-tableau-de-bord", () -> shell().show(AppShell.Section.OVERVIEW), 30, 50);
        add(() -> shot("02-tableau-de-bord"));
        record("anim-02-theme-sombre", app::toggleTheme, 10, 40);
        add(() -> shot("02b-tableau-de-bord-sombre"));
        add(app::toggleTheme);
        record("anim-05-centre-notifications", () -> shell().openNotificationCenter(), 16, 45);
        add(() -> shot("07-centre-notifications"));
        add(() -> app.toggleTheme());
        add(() -> shell().openNotificationCenter());
        add(() -> shot("07b-centre-notifications-sombre"));
        add(() -> app.toggleTheme());
        add(() -> shell().show(AppShell.Section.VEHICLES));
        add(() -> shot("03-liste-vehicules"));
        record("anim-03-fiche-vehicule-ressort", () -> find(VehiclesView.class).selectFirstForTest(), 16, 40);
        add(() -> shot("10-fiche-vehicule-echeances"));
        add(() -> shell().show(AppShell.Section.VEHICLES));
        record("anim-06-alerte", () -> find(VehiclesView.class).archiveFirstForTest(), 14, 40);
        add(() -> shot("08-alerte-archivage"));
        add(() -> app.scene().getRoot().lookupAll(".alert-button").stream()
                .filter(b -> ((javafx.scene.control.Button) b).getText().equals("Archiver")).findFirst()
                .ifPresent(b -> ((javafx.scene.control.Button) b).fire()));
        add(() -> shot("08b-banniere-vehicule-archive"));
        add(() -> find(VehiclesView.class).openFormForTest());
        add(() -> find(VehicleFormView.class).fillInvalidForTest());
        add(() -> shot("04-ajout-vehicule-erreurs"));
        add(() -> find(VehicleFormView.class).fillValidForTest());
        add(() -> shot("04b-ajout-vehicule-confirme"));
        record("anim-04-echeances", () -> shell().show(AppShell.Section.DUE), 22, 50);
        add(() -> shot("05-echeances"));
        add(() -> shell().show(AppShell.Section.DRIVERS));
        add(() -> shot("06-conducteurs"));
        add(() -> shell().show(AppShell.Section.SETTINGS));
        add(() -> shot("09-reglages"));
        add(() -> app.toggleTheme());
        add(() -> shot("09b-reglages-sombre"));
        add(() -> app.toggleTheme());
        add(() -> shell().confirmLogout());
        add(() -> shot("09c-alerte-deconnexion"));
        add(() -> app.scene().getRoot().lookupAll(".alert-button").stream().findFirst()
                .ifPresent(b -> ((javafx.scene.control.Button) b).fire()));
        add(() -> shell().openVehicles("hilux"));
        add(() -> shot("03b-recherche-hilux"));
        add(() -> ((DemoFleetGateway) app.gateway()).setNetworkDown(true));
        add(() -> shell().show(AppShell.Section.VEHICLES));
        add(() -> shot("etat-erreur-reseau"));
        add(() -> ((DemoFleetGateway) app.gateway()).setNetworkDown(false));
        add(() -> shell().show(AppShell.Section.FUEL));
        add(() -> shot("30-carburant-bons"));
        add(() -> shell().show(AppShell.Section.COSTS));
        add(() -> shot("31-couts-budget"));
        add(() -> shell().show(AppShell.Section.REPORTS));
        add(() -> shot("32-rapports"));
        add(() -> shell().show(AppShell.Section.MAINTENANCE));
        add(() -> shot("33-entretiens-gestionnaire"));
        add(() -> app.scene().setRoot(Splash.create(() -> { })));
        add(() -> { });
        add(() -> { });
        add(() -> shot("00-ecran-de-lancement"));
        add(() -> app.showLogin(null));
        add(() -> login("gestionnaire"));
        add(() -> shell().openSearch(""));
        add(() -> shot("34-recherche-universelle"));
        add(() -> shell().openSearch("lt"));
        add(() -> { });
        add(() -> shot("34b-recherche-universelle-saisie"));
        add(() -> shell().closeSearch());
        add(() -> app.showLogin(null));
        add(() -> login("auditeur"));
        add(() -> shell().show(AppShell.Section.VEHICLES));
        add(() -> shot("etat-droits-auditeur"));
        add(() -> app.showLogin("Votre session a expiré. Reconnectez-vous pour continuer."));
        add(() -> shot("01b-session-expiree"));
        add(() -> {
            LoginView v = (LoginView) app.scene().getRoot();
            v.prefill("gestionnaire", "faux");
            v.submitForTest();
        });
        add(() -> shot("01c-connexion-refusee"));
        add(Platform::exit);
        next();
    }

    private void add(Runnable r) {
        steps.add(done -> {
            r.run();
            PauseTransition p = new PauseTransition(Duration.millis(1100));
            p.setOnFinished(e -> done.run());
            p.play();
        });
    }

    private void record(String name, Runnable action, int frames, int everyMs) {
        steps.add(done -> {
            GifRecorder rec = new GifRecorder(app.scene(), 0.5);
            rec.record(action, frames, everyMs, () -> {
                try {
                    rec.write(dir.resolve(name + ".gif"), everyMs);
                    System.out.println("animation " + name + ".gif (" + rec.frameCount() + " images)");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                PauseTransition p = new PauseTransition(Duration.millis(600));
                p.setOnFinished(e -> done.run());
                p.play();
            });
        });
    }

    private void next() {
        Consumer<Runnable> s = steps.poll();
        if (s != null) s.accept(this::next);
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
