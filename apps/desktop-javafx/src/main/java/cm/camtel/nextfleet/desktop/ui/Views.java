package cm.camtel.nextfleet.desktop.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Briques communes des écrans de production : page, en-tête, tuiles, pastilles, feuille de détail, formats. */
final class Views {

    static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRANCE);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.FRANCE);

    private Views() { }

    /** Page défilante centrée (largeur de lecture 1100 px) ; renvoie la colonne à remplir. */
    static VBox page(ScrollPane host) {
        VBox col = new VBox(22);
        col.getStyleClass().add("content");
        col.setMaxWidth(1100);
        StackPane center = new StackPane(col);
        center.setAlignment(Pos.TOP_CENTER);
        host.setFitToWidth(true);
        host.setContent(center);
        return col;
    }

    static HBox header(String title, String subtitle, Node... actions) {
        Label sub = Ui.label(subtitle, "body", "muted");
        sub.setWrapText(true);
        VBox text = new VBox(6, Ui.label(title, "large-title"), sub);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox h = new HBox(10);
        h.getChildren().add(text);
        h.getChildren().addAll(actions);
        h.setAlignment(Pos.TOP_LEFT);
        return h;
    }

    static Button filled(String text, String icon, Runnable action) {
        Button b = new Button(text, Icons.stroke(icon, 15, "white"));
        b.getStyleClass().add("btn-filled");
        b.setOnAction(e -> action.run());
        Motion.pressable(b);
        return b;
    }

    static Button tinted(String text, String icon, Runnable action) {
        Button b = new Button(text, icon == null ? null : Icons.stroke(icon, 15, "-nf-accent"));
        b.getStyleClass().add("btn-tinted");
        b.setOnAction(e -> action.run());
        Motion.pressable(b);
        return b;
    }

    static VBox tile(String value, String caption, String cls) {
        VBox t = new VBox(2, Ui.label(value, "summary-value"), Ui.label(caption, "category-label"));
        t.getStyleClass().addAll("summary-tile", cls);
        t.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(t, Priority.ALWAYS);
        return t;
    }

    static HBox tiles(Node... t) {
        HBox h = new HBox(12, t);
        h.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
        return h;
    }

    /** Pastille de statut : couleur DOUBLÉE d'un libellé (jamais la couleur seule). */
    static Label pill(String label, String tone) {
        Circle dot = new Circle(3);
        dot.getStyleClass().add("dot-" + tone);
        Label l = new Label(label, dot);
        l.getStyleClass().addAll("pill", "pill-" + tone);
        l.setAccessibleText("Statut : " + label);
        l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }

    /** Ligne de liste à deux niveaux (titre + sous-titre) avec accessoire à droite. */
    static HBox row(Node tile, String title, String subtitle, Node accessory, Runnable onClick) {
        Label t = Ui.label(title, "row-title");
        Label s = Ui.label(subtitle, "footnote", "muted");
        t.setMinWidth(0);
        s.setMinWidth(0);
        VBox text = new VBox(2, t, s);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12);
        row.getStyleClass().add("list-row");
        row.setAlignment(Pos.CENTER_LEFT);
        if (tile != null) {
            row.getChildren().add(tile);
            row.getProperties().put("hasIcon", true);
        }
        row.getChildren().add(text);
        if (accessory != null) row.getChildren().add(accessory);
        if (onClick != null) {
            row.getStyleClass().add("clickable");
            row.setOnMouseClicked(e -> onClick.run());
            row.getChildren().add(Icons.stroke(Icons.CHEVRON_RIGHT, 13, "-nf-tertiary"));
        }
        row.setAccessibleText(title + ", " + subtitle);
        return row;
    }

    /** Feuille de détail : barre Fermer · titre, lignes clé/valeur, actions en bas (concentriques à la feuille). */
    static ScrollPane detailSheet(AppShell shell, String title, String subtitle, List<String[]> facts, List<Button> actions) {
        VBox box = new VBox(14);
        box.getStyleClass().add("sheet");
        Button close = new Button("Fermer");
        close.getStyleClass().add("btn-plain");
        close.setCancelButton(true);
        close.setOnAction(e -> shell.closeSheet());
        Label t = Ui.label(title, "headline");
        t.setWrapText(true);
        StackPane bar = new StackPane(t, close);
        StackPane.setAlignment(close, Pos.CENTER_LEFT);
        bar.setMinHeight(40);
        box.getChildren().add(bar);
        if (subtitle != null) {
            Label s = Ui.label(subtitle, "footnote", "muted");
            s.setWrapText(true);
            box.getChildren().add(s);
        }
        List<Node> rows = new java.util.ArrayList<>();
        for (String[] f : facts) {
            if (f[1] == null || f[1].isBlank()) continue;
            Label v = Ui.label(f[1], "row-value");
            v.setWrapText(true);
            v.setMaxWidth(240);
            v.setAlignment(Pos.CENTER_RIGHT);
            HBox r = new HBox(12, Ui.label(f[0], "row-title"), Ui.spacer(), v);
            r.getStyleClass().add("list-row");
            r.setAlignment(Pos.CENTER_LEFT);
            rows.add(r);
        }
        if (!rows.isEmpty()) box.getChildren().add(Ui.insetGroup(null, rows, null));
        if (!actions.isEmpty()) {
            VBox acts = new VBox(8);
            for (Button b : actions) {
                b.setMaxWidth(Double.MAX_VALUE);
                acts.getChildren().add(b);
            }
            box.getChildren().add(acts);
        }
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    static Button sheetAction(String text, String style, Runnable r) {
        Button b = new Button(text);
        b.getStyleClass().addAll(style, "btn-large");
        b.setOnAction(e -> r.run());
        return b;
    }

    // ---------------------------------------------------------------- formats (fr-CM)

    static String num(BigDecimal v) {
        if (v == null) return "—";
        NumberFormat f = NumberFormat.getNumberInstance(Locale.FRANCE);
        f.setMaximumFractionDigits(v.scale() > 0 && v.stripTrailingZeros().scale() > 0 ? 1 : 0);
        return f.format(v.setScale(1, RoundingMode.HALF_UP));
    }

    static String xaf(BigDecimal v) {
        if (v == null) return "—";
        NumberFormat f = NumberFormat.getIntegerInstance(Locale.FRANCE);
        return f.format(v.setScale(0, RoundingMode.HALF_UP)) + " FCFA";
    }

    static String km(BigDecimal v) {
        return v == null ? "—" : num(v) + " km";
    }

    static String liters(BigDecimal v) {
        return v == null ? "—" : num(v) + " L";
    }

    static String day(LocalDate d) {
        return d == null ? "—" : DAY.format(d);
    }

    static String dayTime(Instant i) {
        return i == null ? "—" : DAY_TIME.format(i.atZone(DOUALA));
    }

    static LocalDate today() {
        return LocalDate.now(DOUALA);
    }
}
