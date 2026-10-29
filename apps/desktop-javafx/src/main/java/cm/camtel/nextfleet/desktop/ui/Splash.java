package cm.camtel.nextfleet.desktop.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/**
 * Écran de lancement (≈ 1,3 s) : fond bleu nuit, les quatre pétales du logo se déploient en ressort, un halo respire,
 * le nom monte, une fine ligne de chargement balaie la base. Puis fondu vers la connexion. Avec la réduction de
 * mouvement : logo fixe, 0,4 s.
 */
public final class Splash {

    private Splash() { }

    public static Parent create(Runnable onDone) {
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: radial-gradient(center 50% 42%, radius 75%, #0E3A3C 0%, #08202A 48%, #050E14 100%);");

        // logo : quatre pétales dessinés séparément pour se déployer depuis le centre
        String[] petals = {
                "M12 10.6 C9.2 7.2 7.6 3.8 5.4 4.6 C3.2 5.4 4.2 8.8 9.8 12 Z",
                "M12 10.6 C14.8 7.2 16.4 3.8 18.6 4.6 C20.8 5.4 19.8 8.8 14.2 12 Z",
                "M12 13.4 C9.2 16.8 7.6 20.2 5.4 19.4 C3.2 18.6 4.2 15.2 9.8 12 Z",
                "M12 13.4 C14.8 16.8 16.4 20.2 18.6 19.4 C20.8 18.6 19.8 15.2 14.2 12 Z"};
        Group logo = new Group();
        Node[] parts = new Node[4];
        for (int i = 0; i < 4; i++) {
            SVGPath p = new SVGPath();
            p.setContent(petals[i]);
            p.setFill(Color.web(i % 3 == 0 ? "#2AD4BC" : "#00B39C"));
            parts[i] = p;
            logo.getChildren().add(p);
        }
        logo.setScaleX(4.2);
        logo.setScaleY(4.2);
        StackPane mark = new StackPane(logo);
        mark.setMinSize(120, 120);
        mark.setMaxSize(120, 120);

        Circle halo = new Circle(70, Color.web("#2AD4BC", 0.0));
        halo.setStroke(Color.web("#2AD4BC", 0.35));
        halo.setStrokeWidth(1.5);
        StackPane emblem = new StackPane(halo, mark);

        Label name = new Label("NexFleet");
        name.setStyle("-fx-text-fill: white; -fx-font-family: 'Inter Display'; -fx-font-size: 44px; -fx-font-weight: 800;");
        Label tag = new Label("Gestion de flotte · CAMTEL");
        tag.setStyle("-fx-text-fill: #8FB3B8; -fx-font-family: 'Inter'; -fx-font-size: 15px; -fx-font-weight: 500;");
        VBox words = new VBox(6, name, tag);
        words.setAlignment(Pos.CENTER);

        VBox center = new VBox(26, emblem, words);
        center.setAlignment(Pos.CENTER);

        // ligne de chargement
        Region rail = new Region();
        rail.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-background-radius: 2;");
        rail.setMaxSize(220, 3);
        rail.setMinSize(220, 3);
        Region sweep = new Region();
        sweep.setStyle("-fx-background-color: linear-gradient(to right, transparent, #2AD4BC, transparent); -fx-background-radius: 2;");
        sweep.setMaxSize(70, 3);
        sweep.setMinSize(70, 3);
        StackPane bar = new StackPane(rail, sweep);
        bar.setMaxSize(220, 3);
        Rectangle clip = new Rectangle(220, 3);
        bar.setClip(clip);
        StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
        javafx.geometry.Insets m = new javafx.geometry.Insets(0, 0, 72, 0);
        StackPane.setMargin(bar, m);

        Label legal = new Label("Version 0.8.0");
        legal.setStyle("-fx-text-fill: rgba(255,255,255,0.32); -fx-font-size: 11px;");
        StackPane.setAlignment(legal, Pos.BOTTOM_CENTER);
        StackPane.setMargin(legal, new javafx.geometry.Insets(0, 0, 36, 0));

        root.getChildren().addAll(center, bar, legal);

        if (Motion.isReduced()) {
            PauseTransition p = new PauseTransition(Duration.millis(400));
            p.setOnFinished(e -> onDone.run());
            p.play();
            return root;
        }

        // états initiaux
        for (int i = 0; i < 4; i++) {
            Node n = parts[i];
            n.setOpacity(0);
            n.setScaleX(0.3);
            n.setScaleY(0.3);
        }
        logo.setRotate(-45);
        words.setOpacity(0);
        words.setTranslateY(14);
        halo.setScaleX(0.6);
        halo.setScaleY(0.6);
        halo.setOpacity(0);
        sweep.setTranslateX(-150);

        ParallelTransition bloom = new ParallelTransition();
        for (int i = 0; i < 4; i++) {
            Node n = parts[i];
            ScaleTransition s = new ScaleTransition(Duration.millis(620), n);
            s.setToX(1);
            s.setToY(1);
            s.setInterpolator(Motion.SPRING);
            FadeTransition f = new FadeTransition(Duration.millis(260), n);
            f.setToValue(1);
            ParallelTransition one = new ParallelTransition(s, f);
            one.setDelay(Duration.millis(70 * i));
            bloom.getChildren().add(one);
        }
        RotateTransition spin = new RotateTransition(Duration.millis(820), logo);
        spin.setToAngle(0);
        spin.setInterpolator(Motion.SPRING);
        bloom.getChildren().add(spin);

        ScaleTransition haloGrow = new ScaleTransition(Duration.millis(900), halo);
        haloGrow.setToX(1.25);
        haloGrow.setToY(1.25);
        haloGrow.setInterpolator(Motion.EASE_OUT);
        Timeline haloFade = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(halo.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(300), new KeyValue(halo.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(900), new KeyValue(halo.opacityProperty(), 0, Interpolator.EASE_IN)));
        ParallelTransition haloAnim = new ParallelTransition(haloGrow, haloFade);
        haloAnim.setDelay(Duration.millis(280));
        bloom.getChildren().add(haloAnim);

        FadeTransition wf = new FadeTransition(Duration.millis(420), words);
        wf.setToValue(1);
        TranslateTransition wt = new TranslateTransition(Duration.millis(520), words);
        wt.setToY(0);
        wt.setInterpolator(Motion.EASE_OUT);
        ParallelTransition wordsIn = new ParallelTransition(wf, wt);
        wordsIn.setDelay(Duration.millis(360));
        bloom.getChildren().add(wordsIn);

        TranslateTransition sw = new TranslateTransition(Duration.millis(900), sweep);
        sw.setToX(150);
        sw.setInterpolator(Interpolator.EASE_BOTH);
        sw.setCycleCount(2);
        sw.setDelay(Duration.millis(200));
        bloom.getChildren().add(sw);

        SequentialTransition all = new SequentialTransition(bloom, new PauseTransition(Duration.millis(180)));
        all.setOnFinished(e -> onDone.run());
        all.play();
        return root;
    }
}
