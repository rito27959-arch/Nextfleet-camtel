package cm.camtel.nextfleet.desktop.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Confirmations non bloquantes (toasts), annoncées aux lecteurs d'écran, disparition après 3,2 s. */
public final class Toasts {

    private final VBox stack = new VBox(10);

    public Toasts() {
        stack.setAlignment(Pos.TOP_RIGHT);
        stack.setFillWidth(false);
        stack.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        stack.setPickOnBounds(false);
        stack.setPadding(new Insets(76, 28, 0, 0));
        stack.setMouseTransparent(true);
        StackPane.setAlignment(stack, Pos.TOP_RIGHT);
    }

    public Node host() {
        return stack;
    }

    public void success(String title, String detail) {
        show(Icons.stroke(Icons.CHECK, 18, "#3FD8C1"), title, detail);
    }

    public void info(String title, String detail) {
        show(Icons.stroke(Icons.BELL, 18, "#A9BAC5"), title, detail);
    }

    private void show(Node icon, String title, String detail) {
        VBox text = new VBox(2, Ui.label(title, "w-600"));
        if (detail != null) text.getChildren().add(Ui.label(detail, "muted"));
        HBox toast = new HBox(10, icon, text);
        toast.getStyleClass().add("toast");
        toast.setMaxWidth(380);
        toast.setAccessibleText(title + (detail == null ? "" : ". " + detail));
        toast.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        stack.getChildren().add(0, toast);
        if (Motion.isReduced()) {
            PauseTransition p = new PauseTransition(Duration.millis(3200));
            p.setOnFinished(e -> stack.getChildren().remove(toast));
            p.play();
            return;
        }
        toast.setTranslateY(-16);
        toast.setOpacity(0);
        TranslateTransition in = new TranslateTransition(Duration.millis(380), toast);
        in.setToY(0);
        in.setInterpolator(Motion.SPRING);
        FadeTransition fin = new FadeTransition(Motion.FAST, toast);
        fin.setToValue(1);
        FadeTransition out = new FadeTransition(Motion.BASE, toast);
        out.setToValue(0);
        SequentialTransition seq = new SequentialTransition(new ParallelTransition(in, fin), new PauseTransition(Duration.millis(3200)), out);
        seq.setOnFinished(e -> stack.getChildren().remove(toast));
        seq.play();
    }
}
