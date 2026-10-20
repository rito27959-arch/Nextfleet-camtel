package cm.camtel.nextfleet.desktop.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Bannières de notification au style macOS (HIG « Notifications ») : en haut à droite, symbole à gauche,
 * titre court + « maintenant », corps en phrase complète ; entrée en ressort depuis la droite,
 * fermeture au survol (×) ou automatique après 4 s ; un clic ouvre le contenu lié.
 */
public final class Toasts {

    private final VBox stack = new VBox(10);

    public Toasts() {
        stack.setAlignment(Pos.TOP_RIGHT);
        stack.setFillWidth(false);
        stack.setPickOnBounds(false);
        stack.setPadding(new Insets(68, 20, 0, 0));
        stack.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(stack, Pos.TOP_RIGHT);
    }

    public Node host() {
        return stack;
    }

    public void success(String title, String detail) {
        show(Ui.symbolTile(Icons.CHECK, "tile-green"), title, detail, null);
    }

    public void info(String title, String detail) {
        show(Ui.symbolTile(Icons.BELL, "tile-gray"), title, detail, null);
    }

    public void show(Node tile, String title, String detail, Runnable onOpen) {
        Label t = Ui.label(title, "headline");
        t.setMaxWidth(230);
        Label when = Ui.label("maintenant", "footnote", "muted");
        HBox head = new HBox(8, t, Ui.spacer(), when);
        head.setAlignment(Pos.BASELINE_LEFT);
        VBox text = new VBox(2, head);
        HBox.setHgrow(text, Priority.ALWAYS);
        if (detail != null) {
            Label d = Ui.label(detail, "subhead");
            d.setWrapText(true);
            d.setMaxWidth(270);
            text.getChildren().add(d);
        }
        HBox banner = new HBox(12, tile, text);
        banner.getStyleClass().add("banner");
        banner.setPrefWidth(360);
        banner.setMaxWidth(360);
        banner.setAccessibleText(title + (detail == null ? "" : ". " + detail));

        Button close = new Button(null, Icons.stroke(Icons.CLOSE, 10, "-nf-muted"));
        close.getStyleClass().setAll("button", "sheet-close");
        close.setStyle("-fx-min-width: 20; -fx-min-height: 20; -fx-pref-width: 20; -fx-pref-height: 20;");
        close.setAccessibleText("Fermer la notification");
        close.setVisible(false);
        close.setManaged(false);
        StackPane holder = new StackPane(banner, close);
        StackPane.setAlignment(close, Pos.TOP_LEFT);
        close.setTranslateX(-7);
        close.setTranslateY(-7);
        holder.setOnMouseEntered(e -> close.setVisible(true));
        holder.setOnMouseExited(e -> close.setVisible(false));
        Runnable dismiss = () -> dismiss(holder);
        close.setOnAction(e -> dismiss.run());
        banner.setOnMouseClicked(e -> {
            dismiss.run();
            if (onOpen != null) onOpen.run();
        });
        stack.getChildren().add(0, holder);
        if (!Motion.isReduced()) {
            holder.setTranslateX(380);
            TranslateTransition in = new TranslateTransition(Duration.millis(520), holder);
            in.setToX(0);
            in.setInterpolator(Motion.SPRING);
            in.play();
        }
        PauseTransition p = new PauseTransition(Duration.millis(4200));
        p.setOnFinished(e -> {
            if (!holder.isHover()) dismiss.run();
            else holder.setOnMouseExited(x -> dismiss.run());
        });
        p.play();
    }

    private void dismiss(Node holder) {
        if (!stack.getChildren().contains(holder)) return;
        if (Motion.isReduced()) {
            stack.getChildren().remove(holder);
            return;
        }
        TranslateTransition out = new TranslateTransition(Duration.millis(220), holder);
        out.setToX(380);
        out.setInterpolator(Motion.EASE_IN_OUT);
        FadeTransition f = new FadeTransition(Duration.millis(220), holder);
        f.setToValue(0);
        ParallelTransition pt = new ParallelTransition(out, f);
        pt.setOnFinished(e -> stack.getChildren().remove(holder));
        pt.play();
    }
}
