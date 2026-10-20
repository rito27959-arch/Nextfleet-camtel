package cm.camtel.nextfleet.desktop.ui;

import javafx.scene.Node;
import javafx.scene.Group;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * Pictogrammes NexFleet dessinés pour le projet (grille 24 px, trait 1,6). Aucun jeu d'icônes tiers embarqué.
 * La couleur est donnée par le CSS (classe {@code icon}) ou explicitement.
 */
public final class Icons {

    private Icons() { }

    public static final String HOME = "M4 11 L12 4 L20 11 M6 9.5 V20 H10 V14 H14 V20 H18 V9.5";
    public static final String CAR = "M3 16 V12.5 L5.2 7.5 C5.5 6.9 6 6.5 6.7 6.5 H17.3 C18 6.5 18.5 6.9 18.8 7.5 L21 12.5 V16 H3 Z M3 12.5 H21 M6.5 16 V18.5 M17.5 16 V18.5 M6.5 14.2 H7.5 M16.5 14.2 H17.5";
    public static final String WRENCH = "M14.5 6.5 A4 4 0 0 0 19.6 11.6 L19.9 11.3 A4.5 4.5 0 0 1 13.7 5.1 L13.4 5.4 Z M14.2 9.8 L5 19 A1.4 1.4 0 0 1 3 17 L12.2 7.8";
    public static final String FUEL = "M5 20 V5.5 C5 4.7 5.7 4 6.5 4 H12.5 C13.3 4 14 4.7 14 5.5 V20 M3.5 20 H15.5 M5 10 H14 M14 8.5 H16 C16.8 8.5 17.5 9.2 17.5 10 V16.5 C17.5 17.3 18.2 18 19 18 C19.8 18 20.5 17.3 20.5 16.5 V8 L18 5.5";
    public static final String CHART = "M5 19 V12 M10 19 V8 M15 19 V11 M20 19 V5 M3 20 H21";
    public static final String USER = "M12 12 A4 4 0 1 0 12 4 A4 4 0 1 0 12 12 Z M4.5 20.5 C5.2 16.8 8.2 14.5 12 14.5 C15.8 14.5 18.8 16.8 19.5 20.5";
    public static final String REPORT = "M6 3.5 H14 L19 8.5 V20.5 H6 Z M14 3.5 V8.5 H19 M9 12.5 H16 M9 16 H16";
    public static final String GEAR = "M12 15 A3 3 0 1 0 12 9 A3 3 0 1 0 12 15 Z M12 2.8 V5.2 M12 18.8 V21.2 M2.8 12 H5.2 M18.8 12 H21.2 M5.5 5.5 L7.2 7.2 M16.8 16.8 L18.5 18.5 M5.5 18.5 L7.2 16.8 M16.8 7.2 L18.5 5.5";
    public static final String BELL = "M6 16.5 V11 A6 6 0 0 1 18 11 V16.5 L19.5 18 H4.5 Z M10 20.5 H14";
    public static final String SEARCH = "M10.5 17 A6.5 6.5 0 1 0 10.5 4 A6.5 6.5 0 1 0 10.5 17 Z M15.5 15.5 L20 20";
    public static final String CALENDAR = "M4.5 6.5 H19.5 V20 H4.5 Z M4.5 10.5 H19.5 M8.5 4 V8 M15.5 4 V8";
    public static final String DOWNLOAD = "M12 4 V15 M7.5 10.5 L12 15 L16.5 10.5 M5 19.5 H19";
    public static final String COINS = "M4 7 C4 5.6 7.1 4.5 11 4.5 C14.9 4.5 18 5.6 18 7 C18 8.4 14.9 9.5 11 9.5 C7.1 9.5 4 8.4 4 7 Z M4 7 V11 C4 12.4 7.1 13.5 11 13.5 C14.9 13.5 18 12.4 18 11 V7 M4 11 V15 C4 16.4 7.1 17.5 11 17.5 M20 13 V19";
    public static final String BARS_UP = "M7 18 V13 M12 18 V9 M17 18 V6";
    public static final String MAP_PIN = "M12 21 C12 21 18.5 14.8 18.5 10 A6.5 6.5 0 0 0 5.5 10 C5.5 14.8 12 21 12 21 Z M12 12.5 A2.5 2.5 0 1 0 12 7.5 A2.5 2.5 0 1 0 12 12.5 Z";
    public static final String CHEVRON_RIGHT = "M9.5 6 L15.5 12 L9.5 18";
    public static final String ARROW_UP = "M12 19 V5 M6.5 10.5 L12 5 L17.5 10.5";
    public static final String ARROW_DOWN = "M12 5 V19 M6.5 13.5 L12 19 L17.5 13.5";
    public static final String DOC = "M7 3.5 H14 L18 7.5 V20.5 H7 Z M10 12 H15 M10 15.5 H15";
    public static final String PLUS = "M12 5 V19 M5 12 H19";
    public static final String CLOSE = "M6 6 L18 18 M18 6 L6 18";
    public static final String CHECK = "M5 12.5 L10 17.5 L19 7";
    public static final String SHIELD = "M12 3 L19 6 V11.5 C19 16 15.8 19.6 12 21 C8.2 19.6 5 16 5 11.5 V6 Z M9 12 L11.2 14.2 L15.2 10";
    public static final String MOTION = "M4 12 H9 M12 7 H20 M12 17 H20 M15 12 H20 M9 12 A3 3 0 1 0 15 12 A3 3 0 1 0 9 12";
    public static final String PHONE = "M7 3.5 H17 V20.5 H7 Z M11 17.5 H13";
    public static final String ID_CARD = "M3.5 6 H20.5 V18 H3.5 Z M7 10 H11 M7 13.5 H10 M14.5 11.5 A2 2 0 1 0 14.51 11.5";
    public static final String WIFI_OFF = "M3 3 L21 21 M8.5 16.5 A5 5 0 0 1 15.5 16.5 M5 13 A10 10 0 0 1 10 10.2 M14 10.2 A10 10 0 0 1 19 13 M12 20 H12.01";
    public static final String LOCK = "M6 11 H18 V20 H6 Z M8.5 11 V8 A3.5 3.5 0 0 1 15.5 8 V11";
    public static final String MOON = "M19.5 14.5 A8 8 0 1 1 9.5 4.5 A6.5 6.5 0 0 0 19.5 14.5 Z";
    public static final String GAUGE = "M4.5 17 A8 8 0 1 1 19.5 17 M12 13 L15.5 9";

    /** Pictogramme au trait, couleur pilotée par le CSS (classe {@code icon}) ; encapsulé pour des dimensions exactes. */
    public static Node icon(String path, double size) {
        return new Group(raw(path, size));
    }

    private static SVGPath raw(String path, double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add("icon");
        p.setStrokeLineCap(StrokeLineCap.ROUND);
        p.setStrokeLineJoin(StrokeLineJoin.ROUND);
        p.setStyle("-fx-fill: transparent; -fx-stroke-width: 1.6;");
        double scale = size / 24.0;
        p.setScaleX(scale);
        p.setScaleY(scale);
        return p;
    }

    /** Pictogramme au trait dans une couleur donnée (variable CSS ou couleur). */
    public static Node stroke(String path, double size, String strokeCss) {
        SVGPath p = raw(path, size);
        p.setStyle("-fx-fill: transparent; -fx-stroke-width: 1.6; -fx-stroke: " + strokeCss + ";");
        return new Group(p);
    }

    /**
     * Emblème NexFleet : quatre pétales en X autour d'un centre (reprise de l'identité visuelle du cahier V4,
     * à remplacer par le fichier vectoriel officiel lorsqu'il sera fourni).
     */
    public static Node logo(double size) {
        SVGPath p = new SVGPath();
        p.setContent("M12 10.6 C9.2 7.2 7.6 3.8 5.4 4.6 C3.2 5.4 4.2 8.8 9.8 12 "
                + "M12 10.6 C14.8 7.2 16.4 3.8 18.6 4.6 C20.8 5.4 19.8 8.8 14.2 12 "
                + "M12 13.4 C9.2 16.8 7.6 20.2 5.4 19.4 C3.2 18.6 4.2 15.2 9.8 12 "
                + "M12 13.4 C14.8 16.8 16.4 20.2 18.6 19.4 C20.8 18.6 19.8 15.2 14.2 12 Z");
        p.setStyle("-fx-fill: #00B39C; -fx-stroke: transparent;");
        double scale = size / 24.0;
        p.setScaleX(scale);
        p.setScaleY(scale);
        return new Group(p);
    }
}
