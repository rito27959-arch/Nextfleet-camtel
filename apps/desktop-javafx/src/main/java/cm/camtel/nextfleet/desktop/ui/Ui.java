package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Composants et utilitaires partagés du design system NexFleet (JavaFX). */
public final class Ui {

    private Ui() { }

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "nextfleet-io");
        t.setDaemon(true);
        return t;
    });

    private static final Locale FR = Locale.FRANCE;

    /** Exécute un appel hors du fil graphique ; succès et erreur reviennent sur le fil JavaFX. */
    public static <T> void async(Supplier<T> work, Consumer<T> onSuccess, Consumer<GatewayException> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() {
                return work.get();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            onError.accept(t instanceof GatewayException g ? g
                    : new GatewayException(GatewayException.Kind.SERVER,
                    "Erreur inattendue : " + (t == null ? "inconnue" : t.getClass().getSimpleName())));
        });
        IO.submit(task);
    }

    public static void later(Runnable r) {
        Platform.runLater(r);
    }

    // ---------------------------------------------------------------- texte et nombres (fr-FR, XAF)

    public static Label label(String text, String... classes) {
        Label l = new Label(text);
        l.getStyleClass().addAll(classes);
        return l;
    }

    public static String decimal(BigDecimal v, int digits) {
        if (v == null) return "—";
        DecimalFormat f = new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits), DecimalFormatSymbols.getInstance(FR));
        return f.format(v.setScale(digits, RoundingMode.HALF_UP));
    }

    /** 5 420 000 → « 5,42 M » (montant XAF compact, maquette p. 17). */
    public static String compactXaf(BigDecimal v) {
        if (v == null) return "—";
        if (v.abs().compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
            return decimal(v.divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP), 2) + " M";
        }
        return decimal(v, 0);
    }

    // ---------------------------------------------------------------- badges et jauges

    public static Label statusPill(VehicleStatus s) {
        Circle dot = new Circle(3);
        dot.getStyleClass().add("dot-" + s.tone);
        Label l = new Label(s.label, dot);
        l.getStyleClass().addAll("pill", "pill-" + s.tone);
        l.setAccessibleText("Statut : " + s.label);
        return l;
    }

    /** Barre de niveau carburant ; une valeur absente est affichée « non renseigné », jamais 0 % (RG-07). */
    public static Node fuelBar(Integer percent) {
        if (percent == null) {
            Label l = label("Non renseigné", "muted", "t-12");
            l.setAccessibleText("Niveau de carburant non renseigné");
            return l;
        }
        ProgressBar bar = new ProgressBar(0);
        Motion.animate(bar.progressProperty(), percent / 100.0, javafx.util.Duration.millis(700), javafx.util.Duration.millis(120));
        bar.getStyleClass().addAll("fuel-bar", percent < 20 ? "fuel-low" : percent < 50 ? "fuel-mid" : "fuel-ok");
        bar.setFocusTraversable(false);
        Label pct = label(percent + " %", "muted", "t-12");
        pct.setMinWidth(36);
        HBox box = new HBox(10, bar, pct);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setAccessibleText("Carburant " + percent + " %");
        return box;
    }

    /** Anneau de progression (taux de disponibilité). */
    public static StackPane ring(double ratio, String text, double radius) {
        Circle track = new Circle(radius);
        track.getStyleClass().add("ring-track");
        track.setStrokeWidth(5);
        Arc arc = new Arc(0, 0, radius, radius, 90, 0);
        Motion.animate(arc.lengthProperty(), -360 * Math.max(0, Math.min(1, ratio)), javafx.util.Duration.millis(900),
                javafx.util.Duration.millis(150));
        arc.setType(ArcType.OPEN);
        arc.getStyleClass().add("ring-value");
        arc.setStrokeWidth(5);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        Label l = label(text, "w-700", "t-12");
        l.setStyle("-fx-text-fill: white;");
        StackPane p = new StackPane(track, arc, l);
        p.setMinSize(radius * 2 + 8, radius * 2 + 8);
        return p;
    }

    public static StackPane bubble(String iconPath, String tone, String strokeVar) {
        StackPane b = new StackPane(Icons.stroke(iconPath, 18, strokeVar));
        b.getStyleClass().addAll("kpi-icon-bubble", "bubble-" + tone);
        return b;
    }

    public static Label trend(BigDecimal percent, boolean lowerIsBetter, String suffix) {
        return trend(percent, lowerIsBetter, suffix, " %");
    }

    /** Variation affichée avec son unité (« % » pour un taux, « » pour un nombre d'éléments). */
    public static Label trend(BigDecimal percent, boolean lowerIsBetter, String suffix, String unit) {
        if (percent == null) return label("Tendance indisponible", "muted", "t-12");
        boolean up = percent.signum() > 0;
        boolean good = lowerIsBetter != up || percent.signum() == 0;
        Node arrow = Icons.stroke(up ? Icons.ARROW_UP : Icons.ARROW_DOWN, 11, good ? "-nf-green-strong" : "-nf-danger");
        String sign = up ? "+" : percent.signum() < 0 ? "−" : "";
        Label l = new Label(sign + percent.abs().toPlainString() + unit + " " + suffix, arrow);
        l.getStyleClass().add(good ? "trend-good" : "trend-bad");
        l.setGraphicTextGap(4);
        return l;
    }

    // ---------------------------------------------------------------- états

    public static Region skeleton(double w, double h) {
        Region r = new Region();
        r.getStyleClass().add("skeleton");
        r.setPrefSize(w, h);
        r.setMaxWidth(w < 0 ? Double.MAX_VALUE : w);
        r.setAccessibleText("Chargement en cours");
        Motion.pulse(r, 0.55, javafx.util.Duration.millis(1100));
        return r;
    }

    /** Bandeau d'erreur explicite avec action « Réessayer » ; l'état bloquant n'est jamais masqué. */
    public static HBox errorBanner(GatewayException e, Runnable retry) {
        String path = switch (e.kind()) {
            case NETWORK -> Icons.WIFI_OFF;
            case FORBIDDEN, UNAUTHORIZED -> Icons.LOCK;
            default -> Icons.CLOSE;
        };
        Label msg = new Label(e.getMessage(), Icons.stroke(path, 18, "-nf-danger"));
        msg.setWrapText(true);
        msg.setGraphicTextGap(10);
        HBox.setHgrow(msg, Priority.ALWAYS);
        msg.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(msg);
        box.getStyleClass().add("banner-error");
        if (retry != null && e.kind() != GatewayException.Kind.FORBIDDEN) {
            Button b = new Button("Réessayer");
            b.getStyleClass().add("btn-secondary");
            b.setOnAction(a -> retry.run());
            box.getChildren().add(b);
        }
        box.setAccessibleText("Erreur : " + e.getMessage());
        return box;
    }

    public static VBox statePanel(String iconPath, String title, String detail) {
        Label t = label(title, "state-title");
        Label d = label(detail, "muted");
        d.setWrapText(true);
        d.setMaxWidth(460);
        d.setStyle("-fx-text-alignment: center;");
        VBox v = new VBox(Icons.stroke(iconPath, 44, "-nf-tertiary"), t, d);
        v.getStyleClass().add("state-panel");
        v.setPadding(new Insets(40));
        return v;
    }

    /** Compte à rebours d'échéance : « Expiré depuis 6 j », « J-12 », « À jour ». L'expiré respire doucement. */
    public static Label dueChip(cm.camtel.nextfleet.desktop.data.Models.DueItem d) {
        String text;
        String cls;
        switch (d.level()) {
            case EXPIRE -> {
                text = "Expiré depuis " + (-d.daysLeft()) + " j";
                cls = "due-expire";
            }
            case A_ECHEANCE -> {
                text = d.daysLeft() == 0 ? "Aujourd'hui" : "J-" + d.daysLeft();
                cls = "due-soon";
            }
            default -> {
                text = "À jour · " + d.daysLeft() + " j";
                cls = "due-ok";
            }
        }
        Label l = label(text, "due-chip", cls);
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.setAccessibleText(d.kind().label + " : " + text);
        if (d.level() == cm.camtel.nextfleet.desktop.data.Models.DueLevel.EXPIRE) {
            Motion.pulse(l, 0.62, javafx.util.Duration.millis(1800));
        }
        return l;
    }

    /** Micro-courbe de tendance (sparkline) tracée de gauche à droite. */
    public static Node sparkline(java.util.List<Double> values, double w, double h, String styleClass) {
        javafx.scene.shape.Polyline line = new javafx.scene.shape.Polyline();
        line.getStyleClass().addAll("sparkline", styleClass);
        if (values.size() >= 2) {
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
            double span = max - min == 0 ? 1 : max - min;
            for (int i = 0; i < values.size(); i++) {
                line.getPoints().addAll(i * w / (values.size() - 1), h - 3 - (values.get(i) - min) / span * (h - 6));
            }
        }
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(0, -4, 0, h + 8);
        Motion.animate(clip.widthProperty(), w + 4, javafx.util.Duration.millis(900), javafx.util.Duration.millis(200));
        javafx.scene.layout.Pane p = new javafx.scene.layout.Pane(line);
        p.setClip(clip);
        p.setMinSize(w, h);
        p.setPrefSize(w, h);
        p.setMaxSize(w, h);
        p.setAccessibleText("Tendance sur six mois");
        return p;
    }

    public static StackPane avatar(String initials, int colorIndex) {
        StackPane a = new StackPane(label(initials));
        a.getStyleClass().addAll("avatar-lg", "avatar-" + Math.floorMod(colorIndex, 4));
        return a;
    }

    public static Region divider() {
        Region r = new Region();
        r.getStyleClass().add("divider");
        return r;
    }

    /** Carré arrondi coloré avec pictogramme blanc (icônes de Réglages / notifications iOS). */
    public static StackPane symbolTile(String iconPath, String tileClass) {
        StackPane t = new StackPane(Icons.stroke(iconPath, 17, "white"));
        t.getStyleClass().addAll("symbol-tile", tileClass);
        return t;
    }

    /** Groupe inset (liste groupée iOS) : en-tête facultatif, lignes séparées par des filets inset, pied facultatif. */
    public static VBox insetGroup(String header, java.util.List<Node> rows, String footer) {
        VBox list = new VBox();
        list.getStyleClass().add("inset-group");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                Region sep = new Region();
                sep.getStyleClass().add("row-separator");
                VBox.setMargin(sep, new Insets(0, 0, 0, rows.get(i).getProperties().containsKey("hasIcon") ? 58 : 14));
                list.getChildren().add(sep);
            }
            list.getChildren().add(rows.get(i));
        }
        // découpe aux coins pour le survol des lignes
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(list.widthProperty());
        clip.heightProperty().bind(list.heightProperty());
        list.setClip(clip);
        VBox box = new VBox();
        if (header != null) box.getChildren().add(label(header.toUpperCase(Locale.FRANCE), "section-header"));
        box.getChildren().add(list);
        if (footer != null) {
            Label f = label(footer, "section-footer");
            f.setWrapText(true);
            box.getChildren().add(f);
        }
        return box;
    }

    /** Ligne de liste : tuile facultative, titre, valeur secondaire, accessoire (chevron, interrupteur…). */
    public static HBox listRow(Node tile, String title, String value, Node accessory, Runnable onClick) {
        HBox row = new HBox(12);
        row.getStyleClass().add("list-row");
        if (tile != null) {
            row.getChildren().add(tile);
            row.getProperties().put("hasIcon", true);
        }
        Label t = label(title, "row-title");
        row.getChildren().addAll(t, spacer());
        if (value != null) row.getChildren().add(label(value, "row-value"));
        if (accessory != null) row.getChildren().add(accessory);
        if (onClick != null) {
            row.getStyleClass().add("clickable");
            row.setOnMouseClicked(e -> onClick.run());
            if (accessory == null) row.getChildren().add(Icons.stroke(Icons.CHEVRON_RIGHT, 13, "-nf-tertiary"));
        }
        row.setAccessibleText(title + (value == null ? "" : ", " + value));
        return row;
    }

    /** « maintenant », « il y a 12 min », « il y a 3 h », « hier », « lun. 14:05 » (fuseau Africa/Douala). */
    public static String relativeTime(java.time.Instant at, java.time.Instant now) {
        long min = java.time.Duration.between(at, now).toMinutes();
        if (min < 1) return "maintenant";
        if (min < 60) return "il y a " + min + " min";
        if (min < 24 * 60) return "il y a " + (min / 60) + " h";
        java.time.ZonedDateTime z = at.atZone(java.time.ZoneId.of("Africa/Douala"));
        if (min < 48 * 60) return "hier";
        return z.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRANCE));
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }
}
