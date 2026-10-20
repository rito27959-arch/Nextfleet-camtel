package cm.camtel.nextfleet.desktop.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Alerte modale conforme au HIG « Alerts » :
 * titre court et précis, message bref (sans expliquer les boutons), au plus trois boutons ;
 * deux boutons côte à côte — Annuler en tête (à gauche), action probable en fin (à droite) — au-delà, empilés avec Annuler en bas ;
 * style destructif uniquement pour une action non choisie délibérément ; Échap = Annuler ; Entrée = action préférée.
 * Apparition : 1,12 → 1 avec fondu (≈ 250 ms) ; disparition en fondu.
 */
public final class AlertDialog {

    public enum Role { DEFAULT, CANCEL, DESTRUCTIVE }

    public record Action(String title, Role role, boolean preferred, Runnable handler) {
        public static Action cancel(String title) {
            return new Action(title, Role.CANCEL, false, () -> { });
        }

        public static Action preferred(String title, Runnable r) {
            return new Action(title, Role.DEFAULT, true, r);
        }

        public static Action normal(String title, Runnable r) {
            return new Action(title, Role.DEFAULT, false, r);
        }

        public static Action destructive(String title, Runnable r) {
            return new Action(title, Role.DESTRUCTIVE, false, r);
        }
    }

    private AlertDialog() { }

    /** Affiche l'alerte au-dessus de {@code host} (un StackPane racine). */
    public static void show(StackPane host, Node icon, String title, String message, List<Action> actions) {
        if (actions.isEmpty() || actions.size() > 3) throw new IllegalArgumentException("1 à 3 boutons (HIG)");
        Region dim = new Region();
        dim.getStyleClass().add("scrim");

        VBox body = new VBox();
        body.getStyleClass().add("alert-body");
        if (icon != null) {
            StackPane ic = new StackPane(icon);
            ic.getStyleClass().add("alert-icon");
            body.getChildren().add(ic);
        }
        Label t = new Label(title);
        t.getStyleClass().add("alert-title");
        t.setWrapText(true);
        t.setMaxWidth(260);
        body.getChildren().add(t);
        if (message != null && !message.isBlank()) {
            Label m = new Label(message);
            m.getStyleClass().add("alert-message");
            m.setWrapText(true);
            m.setMaxWidth(260);
            body.getChildren().add(m);
        }

        VBox card = new VBox(body);
        card.setStyle("-fx-background-color: -nf-overlay-surface;");
        card.setMaxSize(300, Region.USE_PREF_SIZE);
        card.setPrefWidth(300);
        card.setAccessibleRole(javafx.scene.AccessibleRole.PARENT);
        card.setAccessibleText("Alerte : " + title);


        Runnable[] close = new Runnable[1];
        List<Button> buttons = new ArrayList<>();
        Button preferred = null;
        Button cancel = null;
        // Ordre HIG : Annuler à gauche (rangée) ou en bas (pile)
        List<Action> ordered = new ArrayList<>(actions);
        boolean stacked = actions.size() > 2;
        ordered.sort((a, b) -> {
            int ra = a.role() == Role.CANCEL ? (stacked ? 1 : -1) : 0;
            int rb = b.role() == Role.CANCEL ? (stacked ? 1 : -1) : 0;
            return Integer.compare(ra, rb);
        });
        for (Action a : ordered) {
            Button b = new Button(a.title());
            b.getStyleClass().setAll("button", "alert-button");
            if (a.preferred()) b.getStyleClass().add("preferred");
            if (a.role() == Role.DESTRUCTIVE) b.getStyleClass().add("destructive");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> {
                close[0].run();
                a.handler().run();
            });
            if (a.preferred()) preferred = b;
            if (a.role() == Role.CANCEL) cancel = b;
            buttons.add(b);
        }
        Region top = hairline(true);
        card.getChildren().add(top);
        if (stacked || buttons.size() == 1) {
            for (int i = 0; i < buttons.size(); i++) {
                if (i > 0) card.getChildren().add(hairline(true));
                card.getChildren().add(buttons.get(i));
            }
        } else {
            HBox row = new HBox();
            for (int i = 0; i < buttons.size(); i++) {
                if (i > 0) row.getChildren().add(hairline(false));
                HBox.setHgrow(buttons.get(i), Priority.ALWAYS);
                buttons.get(i).setPrefWidth(150);
                row.getChildren().add(buttons.get(i));
            }
            card.getChildren().add(row);
        }
        // Coins arrondis : découpe du contenu au rayon de la carte
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(32);
        clip.setArcHeight(32);
        clip.widthProperty().bind(card.widthProperty());
        clip.heightProperty().bind(card.heightProperty());
        card.setClip(clip);
        // L'ombre est portée par un support non découpé
        StackPane shadow = new StackPane(card);
        shadow.getStyleClass().add("alert-card");
        shadow.setMaxSize(300, Region.USE_PREF_SIZE);
        StackPane layer = new StackPane(dim, shadow);
        StackPane.setAlignment(shadow, Pos.CENTER);

        Button fPreferred = preferred;
        Button fCancel = cancel;
        layer.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && fCancel != null) fCancel.fire();
            if (e.getCode() == KeyCode.ENTER && fPreferred != null) fPreferred.fire();
        });
        Node previousFocus = host.getScene() == null ? null : host.getScene().getFocusOwner();
        close[0] = () -> {
            FadeTransition f = new FadeTransition(Duration.millis(Motion.isReduced() ? 1 : 160), layer);
            f.setToValue(0);
            f.setOnFinished(e -> {
                host.getChildren().remove(layer);
                if (previousFocus != null) previousFocus.requestFocus();
            });
            f.play();
        };
        host.getChildren().add(layer);
        layer.requestFocus();
        (fPreferred != null ? fPreferred : buttons.get(buttons.size() - 1)).requestFocus();
        if (!Motion.isReduced()) {
            dim.setOpacity(0);
            shadow.setOpacity(0);
            shadow.setScaleX(1.12);
            shadow.setScaleY(1.12);
            FadeTransition fd = new FadeTransition(Duration.millis(200), dim);
            fd.setToValue(1);
            FadeTransition fc = new FadeTransition(Duration.millis(200), shadow);
            fc.setToValue(1);
            ScaleTransition sc = new ScaleTransition(Duration.millis(380), shadow);
            sc.setToX(1);
            sc.setToY(1);
            sc.setInterpolator(Motion.SPRING);
            new ParallelTransition(fd, fc, sc).play();
        }
    }

    private static Region hairline(boolean horizontal) {
        Region r = new Region();
        r.getStyleClass().add("hairline");
        if (horizontal) {
            r.setMinHeight(1);
            r.setMaxHeight(1);
            r.setMaxWidth(Double.MAX_VALUE);
        } else {
            r.setMinWidth(1);
            r.setMaxWidth(1);
            r.setPrefWidth(1);
            r.setStyle("-fx-min-height: 46;");
        }
        return r;
    }
}
