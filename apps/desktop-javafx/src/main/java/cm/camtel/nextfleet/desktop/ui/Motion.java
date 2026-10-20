package cm.camtel.nextfleet.desktop.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.util.List;
import java.util.function.DoubleFunction;

/**
 * Système de mouvement NexFleet (cahier V4 chap. 40 : 180–280 ms, transitions interrompables, réduction de mouvement).
 * Courbes inspirées des interfaces iOS : décélération douce et ressort critique légèrement amorti.
 * Avec la réduction de mouvement active, l'état final est appliqué immédiatement : aucune information n'attend une animation.
 */
public final class Motion {

    private Motion() { }

    private static volatile boolean reduced;

    /** Décélération « ease-out » proche de cubic-bezier(0.2, 0.8, 0.2, 1). */
    public static final Interpolator EASE_OUT = Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0);
    /** Accélération-décélération standard. */
    public static final Interpolator EASE_IN_OUT = Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0);

    /** Ressort amorti (rapport d'amortissement ≈ 0,72) : léger dépassement puis stabilisation, sans oscillation visible. */
    public static final Interpolator SPRING = new Interpolator() {
        @Override
        protected double curve(double t) {
            if (t >= 1) return 1;
            double zeta = 0.72;
            double omega = 11.0;
            double wd = omega * Math.sqrt(1 - zeta * zeta);
            return 1 - Math.exp(-zeta * omega * t) * (Math.cos(wd * t) + (zeta * omega / wd) * Math.sin(wd * t));
        }
    };

    public static final Duration FAST = Duration.millis(180);
    public static final Duration BASE = Duration.millis(240);
    public static final Duration SLOW = Duration.millis(280);

    public static boolean isReduced() {
        return reduced;
    }

    public static void setReduced(boolean value) {
        reduced = value;
    }

    /** Entrée d'un écran : fondu + montée de 12 px. */
    public static void enter(Node n) {
        enter(n, Duration.ZERO, 12);
    }

    public static void enter(Node n, Duration delay, double rise) {
        if (reduced) {
            n.setOpacity(1);
            n.setTranslateY(0);
            return;
        }
        n.setOpacity(0);
        n.setTranslateY(rise);
        FadeTransition f = new FadeTransition(BASE, n);
        f.setToValue(1);
        f.setInterpolator(EASE_OUT);
        TranslateTransition t = new TranslateTransition(SLOW, n);
        t.setToY(0);
        t.setInterpolator(EASE_OUT);
        ParallelTransition p = new ParallelTransition(f, t);
        p.setDelay(delay);
        p.play();
    }

    /** Apparition échelonnée (40 ms entre éléments, plafonnée pour ne jamais dépasser ~0,5 s au total). */
    public static void stagger(List<? extends Node> nodes) {
        int i = 0;
        for (Node n : nodes) {
            enter(n, Duration.millis(Math.min(i, 8) * 35.0), 10);
            i++;
        }
    }

    /** Compteur animé de 0 à la valeur finale (les KPI de l'ancien produit) ; texte final exact garanti. */
    public static void countUp(Label label, double target, DoubleFunction<String> format) {
        String finalText = format.apply(target);
        label.setAccessibleText(finalText);
        if (reduced || target == 0) {
            label.setText(finalText);
            return;
        }
        DoubleProperty v = new SimpleDoubleProperty(0);
        v.addListener((o, a, b) -> label.setText(format.apply(b.doubleValue())));
        Timeline tl = new Timeline(new KeyFrame(Duration.millis(720), new KeyValue(v, target, EASE_OUT)));
        tl.setOnFinished(e -> label.setText(finalText));
        label.setText(format.apply(0));
        tl.play();
    }

    /** Anime une propriété numérique (jauge, anneau, barre). */
    public static void animate(DoubleProperty p, double to, Duration d, Duration delay) {
        if (reduced) {
            p.set(to);
            return;
        }
        Timeline tl = new Timeline(new KeyFrame(d, new KeyValue(p, to, EASE_OUT)));
        tl.setDelay(delay);
        tl.play();
    }

    /** Effet de pression tactile : légère réduction puis retour en ressort. */
    public static void pressable(Node n) {
        n.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> scale(n, 0.97, FAST, EASE_OUT));
        n.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> scale(n, 1.0, SLOW, SPRING));
    }

    /** Survol d'une carte : soulèvement de 2 px. */
    public static void hoverLift(Node n) {
        n.setOnMouseEntered(e -> lift(n, -2));
        n.setOnMouseExited(e -> lift(n, 0));
    }

    private static void lift(Node n, double y) {
        if (reduced) return;
        TranslateTransition t = new TranslateTransition(FAST, n);
        t.setToY(y);
        t.setInterpolator(EASE_OUT);
        t.play();
    }

    private static void scale(Node n, double s, Duration d, Interpolator i) {
        if (reduced) return;
        ScaleTransition t = new ScaleTransition(d, n);
        t.setToX(s);
        t.setToY(s);
        t.setInterpolator(i);
        t.play();
    }

    /** Panneau latéral qui glisse depuis la droite avec un ressort (feuille iOS). */
    public static void slideInFromRight(Node n, double distance) {
        if (reduced) {
            n.setTranslateX(0);
            n.setOpacity(1);
            return;
        }
        n.setTranslateX(distance);
        n.setOpacity(0.4);
        TranslateTransition t = new TranslateTransition(Duration.millis(420), n);
        t.setToX(0);
        t.setInterpolator(SPRING);
        FadeTransition f = new FadeTransition(FAST, n);
        f.setToValue(1);
        new ParallelTransition(t, f).play();
    }

    public static void slideOutToRight(Node n, double distance, Runnable after) {
        if (reduced) {
            after.run();
            return;
        }
        TranslateTransition t = new TranslateTransition(FAST, n);
        t.setToX(distance);
        t.setInterpolator(Interpolator.EASE_IN);
        FadeTransition f = new FadeTransition(FAST, n);
        f.setToValue(0);
        ParallelTransition p = new ParallelTransition(t, f);
        p.setOnFinished(e -> after.run());
        p.play();
    }

    /** Respiration douce (squelettes de chargement, échéance expirée). Arrêtée si la réduction de mouvement est active. */
    public static Animation pulse(Node n, double min, Duration period) {
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(n.opacityProperty(), 1.0, EASE_IN_OUT)),
                new KeyFrame(period.divide(2), new KeyValue(n.opacityProperty(), min, EASE_IN_OUT)),
                new KeyFrame(period, new KeyValue(n.opacityProperty(), 1.0, EASE_IN_OUT)));
        tl.setCycleCount(Animation.INDEFINITE);
        if (!reduced) tl.play();
        n.sceneProperty().addListener((o, a, s) -> {
            if (s == null) tl.stop();
        });
        return tl;
    }

    /** Glissement vertical vers une position (indicateur de navigation latérale). */
    public static void slideTo(Node n, double y) {
        if (reduced || n.getOpacity() == 0) {
            n.setTranslateY(y);
            n.setOpacity(1);
            return;
        }
        TranslateTransition t = new TranslateTransition(Duration.millis(360), n);
        t.setToY(y);
        t.setInterpolator(SPRING);
        t.play();
    }
}
