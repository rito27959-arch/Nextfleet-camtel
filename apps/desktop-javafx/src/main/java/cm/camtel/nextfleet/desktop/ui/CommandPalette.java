package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models;
import cm.camtel.nextfleet.desktop.data.Ops;
import cm.camtel.nextfleet.domain.security.Permission;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recherche universelle (Ctrl+K), à la manière de Spotlight : un champ unique au centre de l'écran, résultats
 * groupés pendant la frappe (écrans, véhicules, conducteurs, missions), navigation au clavier ↑ ↓ ↵ Échap,
 * correspondance surlignée, recherches récentes. Les résultats viennent de l'API : seul ce que le profil a le droit
 * de voir apparaît.
 */
final class CommandPalette {

    record Hit(String group, String icon, String tile, String title, String subtitle, Runnable action) { }

    private static final List<Hit> RECENT = new ArrayList<>();

    private final AppShell shell;
    private final StackPane layer = new StackPane();
    private final VBox card = new VBox();
    private final TextField field = new TextField();
    private final VBox results = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(results);
    private final PauseTransition debounce = new PauseTransition(Duration.millis(160));
    private final List<Hit> hits = new ArrayList<>();
    private final List<Node> rows = new ArrayList<>();
    private List<Ops.Mission> missions;
    private int selected;
    private long generation;
    private boolean open;

    CommandPalette(AppShell shell) {
        this.shell = shell;
        Region scrim = new Region();
        scrim.getStyleClass().add("palette-scrim");
        scrim.setOnMouseClicked(e -> close());

        field.setPromptText("Écran, immatriculation, conducteur, mission…");
        field.getStyleClass().add("palette-field");
        field.setAccessibleText("Recherche universelle");
        HBox top = new HBox(12, Icons.stroke(Icons.SEARCH, 20, "-nf-muted"), field, Ui.label("Échap", "palette-kbd"));
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("palette-top");
        HBox.setHgrow(field, Priority.ALWAYS);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("palette-scroll");
        scroll.setMaxHeight(430);
        results.getStyleClass().add("palette-results");

        HBox foot = new HBox(16, Ui.label("↑ ↓  naviguer", "palette-hint"), Ui.label("↵  ouvrir", "palette-hint"),
                Ui.spacer(), Ui.label("Ctrl K", "palette-hint"));
        foot.getStyleClass().add("palette-foot");

        card.getChildren().addAll(top, scroll, foot);
        card.getStyleClass().add("palette-card");
        card.setMaxWidth(660);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(card, Pos.TOP_CENTER);
        StackPane.setMargin(card, new Insets(96, 24, 24, 24));
        layer.getChildren().addAll(scrim, card);

        field.textProperty().addListener((o, a, b) -> {
            localResults(b);
            debounce.playFromStart();
        });
        debounce.setOnFinished(e -> remoteResults(field.getText()));
        field.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> { move(1); e.consume(); }
                case UP -> { move(-1); e.consume(); }
                case ENTER -> { run(selected); e.consume(); }
                case ESCAPE -> { close(); e.consume(); }
                default -> { }
            }
        });
        layer.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) close();
        });
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        if (open) {
            field.requestFocus();
            field.selectAll();
            return;
        }
        open = true;
        missions = null;
        field.clear();
        localResults("");
        shell.getChildren().add(layer);
        if (!Motion.isReduced()) {
            Node scrim = layer.getChildren().get(0);
            scrim.setOpacity(0);
            FadeTransition sf = new FadeTransition(Duration.millis(160), scrim);
            sf.setToValue(1);
            card.setOpacity(0);
            card.setScaleX(0.96);
            card.setScaleY(0.96);
            card.setTranslateY(-10);
            FadeTransition f = new FadeTransition(Duration.millis(160), card);
            f.setToValue(1);
            ScaleTransition s = new ScaleTransition(Duration.millis(380), card);
            s.setToX(1);
            s.setToY(1);
            s.setInterpolator(Motion.SPRING);
            TranslateTransition t = new TranslateTransition(Duration.millis(380), card);
            t.setToY(0);
            t.setInterpolator(Motion.SPRING);
            new ParallelTransition(sf, f, s, t).play();
        }
        field.requestFocus();
    }

    /** Ouvre la recherche avec une saisie (raccourci depuis un autre écran, parcours de captures). */
    void open(String query) {
        open();
        field.setText(query);
        field.positionCaret(query.length());
    }

    void close() {
        if (!open) return;
        open = false;
        generation++;
        if (Motion.isReduced()) {
            shell.getChildren().remove(layer);
            return;
        }
        FadeTransition f = new FadeTransition(Duration.millis(120), layer);
        f.setToValue(0);
        ScaleTransition s = new ScaleTransition(Duration.millis(120), card);
        s.setToX(0.98);
        s.setToY(0.98);
        ParallelTransition p = new ParallelTransition(f, s);
        p.setOnFinished(e -> {
            shell.getChildren().remove(layer);
            layer.setOpacity(1);
        });
        p.play();
    }

    // ------------------------------------------------------------------ résultats

    /** Écrans et récents : immédiats, sans réseau. */
    private void localResults(String q) {
        generation++;
        List<Hit> list = new ArrayList<>();
        String n = norm(q);
        if (n.isEmpty()) {
            list.addAll(RECENT);
            for (AppShell.Section s : shell.sections(false)) list.add(section(s));
            list.add(section(AppShell.Section.SETTINGS));
        } else {
            for (AppShell.Section s : shell.sections(false)) if (norm(s.label).contains(n)) list.add(section(s));
            if (norm("Réglages mot de passe serveur thème").contains(n)) list.add(section(AppShell.Section.SETTINGS));
        }
        show(list, q);
    }

    private Hit section(AppShell.Section s) {
        return new Hit("Écrans", s.icon, "tile-navy", s.label, "Ouvrir l'écran", () -> shell.show(s));
    }

    /** Véhicules, conducteurs, missions : interrogés en parallèle, réponses périmées ignorées. */
    private void remoteResults(String q) {
        String n = norm(q);
        if (n.length() < 2) return;
        long gen = generation;
        var session = shell.session();
        var gw = shell.gateway();
        boolean needMissions = shell.visible(AppShell.Section.MISSIONS) && missions == null;
        Ui.async(Ui.all(
                () -> session.can(Permission.VEHICLE_READ) ? gw.vehicles(q.strip(), null, 0, 6).items() : List.of(),
                () -> session.can(Permission.DRIVER_READ) ? gw.drivers(q.strip()) : List.of(),
                () -> needMissions ? gw.missions() : missions == null ? List.of() : missions), r -> {
            if (gen != generation || !open) return;
            @SuppressWarnings("unchecked") List<Models.Vehicle> vs = (List<Models.Vehicle>) r[0];
            @SuppressWarnings("unchecked") List<Models.Driver> ds = (List<Models.Driver>) r[1];
            @SuppressWarnings("unchecked") List<Ops.Mission> ms = (List<Ops.Mission>) r[2];
            if (needMissions) missions = ms;
            List<Hit> list = new ArrayList<>(hits.stream().filter(h -> h.group().equals("Écrans")).toList());
            for (Models.Vehicle v : vs) {
                list.add(new Hit("Véhicules", Icons.CAR, "tile-blue", v.registration(),
                        v.label() + (v.lastKnownPlace() != null ? " · " + v.lastKnownPlace() : "") + " · " + v.status().label,
                        () -> shell.openVehicle(v)));
            }
            ds.stream().limit(5).forEach(d -> list.add(new Hit("Conducteurs", Icons.USER, "tile-green", d.fullName(),
                    "Matricule " + d.staffNumber(), () -> shell.openDriver(d.fullName()))));
            ms.stream().filter(m -> norm(m.reference() + " " + m.purpose() + " " + m.registration() + " "
                            + (m.driverName() == null ? "" : m.driverName())).contains(n))
                    .limit(5).forEach(m -> list.add(new Hit("Missions", Icons.ROUTE, "tile-orange", m.reference() + " · " + m.purpose(),
                            m.registration() + " · " + m.status().label, () -> shell.openMission(m.id()))));
            show(list, q);
        }, err -> { });
    }

    private void show(List<Hit> list, String q) {
        hits.clear();
        hits.addAll(list);
        rows.clear();
        results.getChildren().clear();
        if (list.isEmpty()) {
            Label none = Ui.label(norm(q).length() < 2 ? "Tapez au moins deux caractères." : "Aucun résultat pour « " + q.strip() + " »",
                    "palette-empty");
            results.getChildren().add(none);
            selected = -1;
            return;
        }
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) groups.computeIfAbsent(list.get(i).group(), k -> new ArrayList<>()).add(i);
        Node[] byIndex = new Node[list.size()];
        for (Map.Entry<String, List<Integer>> g : groups.entrySet()) {
            results.getChildren().add(Ui.label(g.getKey().toUpperCase(Locale.FRANCE), "palette-group"));
            for (int i : g.getValue()) {
                Node row = row(list.get(i), q, i);
                byIndex[i] = row;
                results.getChildren().add(row);
            }
        }
        rows.addAll(List.of(byIndex));
        select(0);
    }

    private Node row(Hit h, String q, int index) {
        VBox text = new VBox(1, highlight(h.title(), q), Ui.label(h.subtitle(), "footnote", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        Label enter = Ui.label("↵", "palette-enter");
        HBox r = new HBox(12, Ui.symbolTile(h.icon(), h.tile()), text, enter);
        r.setAlignment(Pos.CENTER_LEFT);
        r.getStyleClass().add("palette-row");
        // comme Spotlight : la souris ne prend la sélection que si elle bouge réellement
        r.setOnMouseMoved(e -> {
            if (selected != index) select(index);
        });
        r.setOnMouseClicked(e -> run(index));
        r.setAccessibleText(h.group() + " : " + h.title() + ", " + h.subtitle());
        return r;
    }

    /** Titre avec la partie correspondant à la saisie mise en évidence (insensible aux accents et à la casse). */
    private static TextFlow highlight(String title, String q) {
        TextFlow f = new TextFlow();
        String n = norm(q);
        int at = n.isEmpty() ? -1 : norm(title).indexOf(n);
        if (at < 0) {
            f.getChildren().add(text(title, "palette-title"));
        } else {
            // la normalisation retire seulement les accents : les positions restent alignées sur le titre d'origine
            f.getChildren().addAll(text(title.substring(0, at), "palette-title"),
                    text(title.substring(at, Math.min(title.length(), at + n.length())), "palette-title", "palette-match"),
                    text(title.substring(Math.min(title.length(), at + n.length())), "palette-title"));
        }
        return f;
    }

    private static Text text(String s, String... cls) {
        Text t = new Text(s);
        t.getStyleClass().addAll(cls);
        return t;
    }

    private void move(int delta) {
        if (rows.isEmpty()) return;
        select(Math.floorMod(selected + delta, rows.size()));
    }

    private void select(int i) {
        if (selected >= 0 && selected < rows.size()) rows.get(selected).getStyleClass().remove("palette-row-selected");
        selected = i;
        if (i < 0 || i >= rows.size()) return;
        Node r = rows.get(i);
        r.getStyleClass().add("palette-row-selected");
        // garde la ligne sélectionnée visible
        double h = results.getHeight() - scroll.getViewportBounds().getHeight();
        if (h > 0) {
            double y = r.getBoundsInParent().getMinY();
            double top = scroll.getVvalue() * h;
            double view = scroll.getViewportBounds().getHeight();
            if (y < top) scroll.setVvalue(y / h);
            else if (y + r.getBoundsInLocal().getHeight() > top + view) scroll.setVvalue((y + r.getBoundsInLocal().getHeight() - view) / h);
        }
    }

    private void run(int i) {
        if (i < 0 || i >= hits.size()) return;
        Hit h = hits.get(i);
        if (!h.group().equals("Écrans") || !RECENT.contains(h)) {
            Hit recent = new Hit("Récents", h.icon(), h.tile(), h.title(), h.subtitle(), h.action());
            RECENT.removeIf(x -> x.title().equals(h.title()));
            RECENT.add(0, recent);
            while (RECENT.size() > 5) RECENT.remove(RECENT.size() - 1);
        }
        close();
        h.action().run();
    }

    static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }
}
