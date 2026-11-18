package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Updater;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.nio.file.Path;

/**
 * Fenêtre de mise à jour : téléchargement du paquet annoncé par le serveur, puis installation et redémarrage, sans autre
 * action de l'utilisateur. « Plus tard » interrompt tout ; la proposition revient au prochain démarrage.
 */
public final class UpdateDialog {

    private UpdateDialog() { }

    /** Ouvre la fenêtre et démarre aussitôt le téléchargement (sauf version portable : lien de téléchargement). */
    public static void show(Window owner, Updater.Info info, boolean dark) {
        Updater.Kind kind = Updater.kind();
        Stage st = new Stage(StageStyle.TRANSPARENT);
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle("Mise à jour de NexFleet");

        Label title = Ui.label("NexFleet " + info.version(), "title-2");
        Label body = Ui.label(kind == Updater.Kind.MANUAL
                ? "Une nouvelle version est disponible. Cette copie de l'application (version portable) ne peut pas s'installer "
                + "d'elle-même : téléchargez la nouvelle version depuis la page de publication."
                : "Une nouvelle version est disponible. Elle se télécharge et s'installe automatiquement, puis NexFleet redémarre "
                + "tout seul. Vos données restent sur le serveur.", "footnote", "muted");
        body.setWrapText(true);
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        Label status = Ui.label(kind == Updater.Kind.MANUAL ? "" : "Préparation…", "footnote", "muted");
        Button later = new Button("Plus tard");
        later.getStyleClass().add("btn-plain");
        HBox buttons = new HBox(10, Ui.spacer(), later);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox card = new VBox(14, Icons.logo(36), title, body);
        if (kind != Updater.Kind.MANUAL) card.getChildren().addAll(bar, status);
        card.getChildren().add(buttons);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(28));
        card.setPrefWidth(440);
        card.setStyle("-fx-background-radius: 18; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.28), 40, 0.2, 0, 12);");
        VBox root = new VBox(card);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: transparent;");
        if (dark) root.getStyleClass().add("dark");
        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        if (owner != null && owner.getScene() != null) scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        st.setScene(scene);

        Thread[] worker = new Thread[1];
        later.setOnAction(e -> {
            if (worker[0] != null) worker[0].interrupt();
            st.close();
        });
        if (kind == Updater.Kind.MANUAL) {
            Button open = Views.filled("Ouvrir la page de téléchargement", Icons.DOWNLOAD, () -> {
                Thread.ofVirtual().start(() -> {
                    try {
                        java.awt.Desktop.getDesktop().browse(java.net.URI.create(info.notesUrl()));
                    } catch (Exception ignored) {
                        // pas de navigateur associé
                    }
                });
                st.close();
            });
            buttons.getChildren().add(open);
        } else {
            String url = Updater.packageUrl(info, kind);
            worker[0] = Thread.ofVirtual().start(() -> {
                try {
                    Path pkg = Updater.download(url, info.version(), p -> Platform.runLater(() -> {
                        bar.setProgress(p < 0 ? ProgressBar.INDETERMINATE_PROGRESS : p);
                        status.setText(p < 0 ? "Téléchargement…" : "Téléchargement… " + Math.round(p * 100) + " %");
                    }));
                    Platform.runLater(() -> {
                        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        status.setText(kind == Updater.Kind.LINUX_DEB
                                ? "Installation : le système va demander le mot de passe de ce poste…"
                                : "Installation… NexFleet redémarre dans un instant.");
                        later.setDisable(true);
                    });
                    Updater.installAndRestart(kind, pkg);
                    Platform.runLater(() -> {
                        Platform.exit();
                        System.exit(0);
                    });
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        bar.setProgress(0);
                        status.setText("Mise à jour impossible pour le moment : " + ex.getMessage());
                        later.setText("Fermer");
                        later.setDisable(false);
                    });
                }
            });
        }
        st.show();
        if (owner != null) {
            st.setX(owner.getX() + (owner.getWidth() - st.getWidth()) / 2);
            st.setY(owner.getY() + (owner.getHeight() - st.getHeight()) / 3);
        }
    }
}
