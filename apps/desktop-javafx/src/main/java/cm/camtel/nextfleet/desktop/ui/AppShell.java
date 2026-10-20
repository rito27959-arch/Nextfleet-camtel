package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coquille de l'application bureau (maquette p. 17) : navigation latérale bleu nuit, recherche globale,
 * badge DÉMO permanent en mode démonstration, profil, bascule clair/sombre.
 */
public final class AppShell extends BorderPane {

    public enum Section {
        OVERVIEW("Vue d'ensemble", Icons.HOME), VEHICLES("Véhicules", Icons.CAR), MAINTENANCE("Entretiens", Icons.WRENCH),
        FUEL("Carburant", Icons.FUEL), COSTS("Coûts", Icons.CHART), DRIVERS("Conducteurs", Icons.USER),
        REPORTS("Rapports", Icons.REPORT), SETTINGS("Paramètres", Icons.GEAR);

        final String label;
        final String icon;

        Section(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private final FleetGateway gateway;
    private final Session session;
    private final Runnable onLogout;
    private final Runnable onToggleTheme;
    private final StackPane content = new StackPane();
    private final Map<Section, ToggleButton> nav = new LinkedHashMap<>();
    private final ToggleGroup group = new ToggleGroup();
    private final TextField search = new TextField();
    private Node current;

    public AppShell(FleetGateway gateway, Session session, Runnable onLogout, Runnable onToggleTheme) {
        this.gateway = gateway;
        this.session = session;
        this.onLogout = onLogout;
        this.onToggleTheme = onToggleTheme;
        setLeft(sidebar());
        BorderPane main = new BorderPane();
        main.setTop(topbar());
        main.setCenter(content);
        setCenter(main);
        sceneProperty().addListener((o, a, scene) -> {
            if (scene != null) {
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN), search::requestFocus);
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN), () -> show(Section.OVERVIEW));
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN), () -> show(Section.VEHICLES));
            }
        });
        show(Section.OVERVIEW);
    }

    private VBox sidebar() {
        HBox brand = new HBox(10, Icons.logo(28), Ui.label("NexFleet", "brand-name"));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setStyle("-fx-padding: 4 8 8 8;");
        VBox box = new VBox(brand, Ui.label("VOTRE ESPACE", "section-title"));
        box.getStyleClass().add("sidebar");
        for (Section s : Section.values()) {
            if (s == Section.SETTINGS) continue;
            box.getChildren().add(navButton(s));
        }
        box.getChildren().add(Ui.spacer());
        box.getChildren().add(navButton(Section.SETTINGS));
        StackPane mark = new StackPane(Icons.stroke(Icons.GAUGE, 18, "white"));
        mark.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-background-radius: 999; -fx-min-width: 34; -fx-min-height: 34;");
        VBox who = new VBox(Ui.label("CAMTEL", "w-700", "t-12"), Ui.label("Gestion de flotte", "muted", "t-12"));
        HBox footer = new HBox(10, mark, who);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("sidebar-footer");
        box.getChildren().add(footer);
        return box;
    }

    private ToggleButton navButton(Section s) {
        ToggleButton b = new ToggleButton(s.label, Icons.icon(s.icon, 18));
        b.getStyleClass().add("nav-item");
        b.setToggleGroup(group);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAccessibleText("Ouvrir " + s.label);
        b.setOnAction(e -> show(s));
        nav.put(s, b);
        return b;
    }

    private HBox topbar() {
        search.setPromptText("Rechercher un véhicule, un conducteur…");
        search.setTooltip(new Tooltip("Recherche globale (Ctrl+K)"));
        search.getStyleClass().add("search-field");
        search.setAccessibleText("Recherche globale");
        StackPane searchBox = new StackPane(search, Icons.stroke(Icons.SEARCH, 16, "-nf-muted"));
        StackPane.setAlignment(searchBox.getChildren().get(1), Pos.CENTER_LEFT);
        searchBox.getChildren().get(1).setTranslateX(12);
        searchBox.getChildren().get(1).setMouseTransparent(true);
        search.setOnAction(e -> openVehicles(search.getText()));

        HBox bar = new HBox(searchBox, Ui.spacer());
        bar.getStyleClass().add("topbar");
        if (gateway.isDemo()) {
            Label demo = Ui.label("DÉMO", "badge-demo");
            demo.setTooltip(new Tooltip("Données fictives — environnement de démonstration, séparé de la production (NF-04)."));
            bar.getChildren().add(demo);
        }
        Button theme = new Button(null, Icons.stroke(Icons.MOON, 18, "-nf-muted"));
        theme.getStyleClass().add("icon-button");
        theme.setAccessibleText("Basculer le thème clair ou sombre");
        theme.setTooltip(new Tooltip("Thème clair / sombre"));
        theme.setOnAction(e -> onToggleTheme.run());

        Circle dot = new Circle(4);
        dot.getStyleClass().add("notif-dot");
        StackPane bellIcon = new StackPane(Icons.stroke(Icons.BELL, 18, "-nf-muted"), dot);
        StackPane.setAlignment(dot, Pos.TOP_RIGHT);
        Button bell = new Button(null, bellIcon);
        bell.getStyleClass().add("icon-button");
        bell.setAccessibleText("Notifications");
        bell.setTooltip(new Tooltip("Centre de notifications : prévu au lot 3"));
        bell.setOnAction(e -> show(Section.OVERVIEW));

        String initials = initials(session.displayName());
        StackPane avatar = new StackPane(Ui.label(initials));
        avatar.getStyleClass().add("avatar");
        Button user = new Button(session.displayName(), avatar);
        user.getStyleClass().add("btn-ghost");
        user.setStyle("-fx-text-fill: -nf-text; -fx-font-size: 12px;");
        user.setGraphicTextGap(8);
        user.setTooltip(new Tooltip("Se déconnecter"));
        user.setAccessibleText("Profil de " + session.displayName() + ". Activer pour se déconnecter.");
        user.setOnAction(e -> onLogout.run());
        bar.getChildren().addAll(theme, bell, user);
        return bar;
    }

    public void show(Section s) {
        ToggleButton b = nav.get(s);
        if (b != null) b.setSelected(true);
        Node view = switch (s) {
            case OVERVIEW -> new DashboardView(gateway, session, this);
            case VEHICLES -> new VehiclesView(gateway, session, null);
            default -> unavailable(s);
        };
        setContent(view);
    }

    public void openVehicles(String query) {
        nav.get(Section.VEHICLES).setSelected(true);
        setContent(new VehiclesView(gateway, session, query));
    }

    public Node currentView() {
        return current;
    }

    private void setContent(Node view) {
        current = view;
        content.getChildren().setAll(view);
    }

    /** Module non livré dans ce lot : affiché comme tel, jamais simulé (règle du prompt maître). */
    private Node unavailable(Section s) {
        String lot = switch (s) {
            case MAINTENANCE, DRIVERS -> "lot 2 (parité métier : NF-12 à NF-17)";
            case FUEL -> "lot 3 (chaîne carburant : NF-40 à NF-45) — règles et base déjà réalisées côté serveur";
            case COSTS, REPORTS -> "lot 3 (NF-21, NF-22, NF-25)";
            default -> "lot 2 (NF-03)";
        };
        VBox p = Ui.statePanel(s.icon, s.label + " — module non disponible dans cette version",
                "Ce module est prévu au " + lot + ". Aucune donnée n'est affichée tant qu'il n'est pas réalisé et recetté.");
        p.getStyleClass().add("content");
        return p;
    }

    private static String initials(String name) {
        String[] parts = name.replaceAll("\\(.*\\)", "").trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) if (!p.isEmpty() && b.length() < 2) b.append(Character.toUpperCase(p.charAt(0)));
        return b.length() == 0 ? "?" : b.toString();
    }
}
