package cm.camtel.nextfleet.desktop.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Contrôles au comportement Apple : contrôle segmenté à pastille glissante et interrupteur. */
public final class Controls {

    private Controls() { }

    /**
     * Contrôle segmenté (HIG « Segmented controls ») : segments de largeur égale, pastille blanche qui glisse
     * avec un ressort sous le segment choisi. Navigable au clavier (flèches gauche/droite).
     */
    public static final class Segmented extends StackPane {
        private final IntegerProperty selected = new SimpleIntegerProperty(-1);
        private final Region thumb = new Region();
        private final HBox row = new HBox();
        private final List<Button> buttons = new ArrayList<>();

        public Segmented(List<String> labels, int initial, IntConsumer onChange) {
            getStyleClass().add("segmented");
            thumb.getStyleClass().add("segmented-thumb");
            thumb.setManaged(false);
            Pane thumbLayer = new Pane(thumb);
            thumbLayer.setMouseTransparent(true);
            for (int i = 0; i < labels.size(); i++) {
                int idx = i;
                Button b = new Button(labels.get(i));
                b.getStyleClass().setAll("button", "segment");
                b.setMaxWidth(Double.MAX_VALUE);
                b.setMinWidth(Region.USE_PREF_SIZE);
                HBox.setHgrow(b, javafx.scene.layout.Priority.ALWAYS);
                b.setOnAction(e -> {
                    if (selected.get() != idx) {
                        selected.set(idx);
                        onChange.accept(idx);
                    }
                });
                b.setAccessibleRole(AccessibleRole.RADIO_BUTTON);
                buttons.add(b);
                row.getChildren().add(b);
            }
            getChildren().addAll(thumbLayer, row);
            setMaxWidth(Region.USE_PREF_SIZE);
            setMaxHeight(Region.USE_PREF_SIZE);
            selected.addListener((o, a, b) -> place(true));
            row.widthProperty().addListener((o, a, b) -> javafx.application.Platform.runLater(() -> place(false)));
            for (Button b : buttons) {
                b.widthProperty().addListener((o, a, w) -> javafx.application.Platform.runLater(() -> place(false)));
            }
            setFocusTraversable(true);
            setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.RIGHT && selected.get() < buttons.size() - 1) buttons.get(selected.get() + 1).fire();
                if (e.getCode() == KeyCode.LEFT && selected.get() > 0) buttons.get(selected.get() - 1).fire();
            });
            selected.set(initial);
        }

        private void place(boolean animate) {
            int i = selected.get();
            if (i < 0 || i >= buttons.size()) return;
            Button b = buttons.get(i);
            if (b.getWidth() <= 0) return;
            for (int k = 0; k < buttons.size(); k++) {
                buttons.get(k).setStyle(k == i ? "-fx-font-weight: 600;" : "-fx-font-weight: 500;");
                buttons.get(k).setAccessibleHelp(k == i ? "sélectionné" : null);
            }
            thumb.resize(b.getWidth(), b.getHeight());
            if (animate && !Motion.isReduced()) {
                javafx.animation.TranslateTransition t = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(320), thumb);
                t.setToX(b.getLayoutX());
                t.setInterpolator(Motion.SPRING);
                t.play();
            } else {
                thumb.setTranslateX(b.getLayoutX());
            }
        }

        public int selected() {
            return selected.get();
        }
    }

    /** Interrupteur (HIG « Toggles ») 46×28 : piste verte quand activé, pastille blanche qui glisse en ressort. */
    public static final class Switch extends StackPane {
        private final BooleanProperty on = new SimpleBooleanProperty();
        private final Region knob = new Region();

        public Switch(boolean initial, String accessibleName, java.util.function.Consumer<Boolean> onChange) {
            getStyleClass().add("switch");
            Region track = new Region();
            track.getStyleClass().add("switch-track");
            track.setPrefSize(46, 28);
            track.setMaxSize(46, 28);
            knob.getStyleClass().add("switch-knob");
            knob.setPrefSize(24, 24);
            knob.setMaxSize(24, 24);
            getChildren().addAll(track, knob);
            setAlignment(Pos.CENTER_LEFT);
            setPrefSize(46, 28);
            setMaxSize(46, 28);
            setMinSize(46, 28);
            setCursor(javafx.scene.Cursor.HAND);
            setFocusTraversable(true);
            setAccessibleRole(AccessibleRole.CHECK_BOX);
            setAccessibleText(accessibleName);
            on.addListener((o, a, b) -> {
                render(true);
                notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED);
                onChange.accept(b);
            });
            setOnMouseClicked(e -> on.set(!on.get()));
            setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) on.set(!on.get());
            });
            on.set(initial);
            render(false);
        }

        private void render(boolean animate) {
            if (on.get()) getStyleClass().add("on"); else getStyleClass().removeAll("on");
            double x = on.get() ? 20 : 2;
            if (animate && !Motion.isReduced()) {
                javafx.animation.TranslateTransition t = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(300), knob);
                t.setToX(x);
                t.setInterpolator(Motion.SPRING);
                t.play();
            } else {
                knob.setTranslateX(x);
            }
        }

        @Override
        public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
            if (attribute == AccessibleAttribute.SELECTED) return on.get();
            return super.queryAccessibleAttribute(attribute, parameters);
        }

        public boolean isOn() {
            return on.get();
        }
    }
}
