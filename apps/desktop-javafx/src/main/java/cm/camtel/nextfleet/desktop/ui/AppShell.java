package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.Models.DueLevel;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
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
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cm.camtel.nextfleet.domain.security.Permission.*;

/**
 * Coquille de l'application bureau (maquette p. 17) : navigation latérale bleu nuit avec indicateur qui glisse,
 * recherche globale (Ctrl+K), badge DÉMO permanent, notifications, thème clair/sombre en fondu enchaîné,
 * réduction de mouvement, toasts. Chaque changement d'écran est une transition de 280 ms.
 */
public final class AppShell extends StackPane {

    public enum Section {
        MY_MISSIONS("Mes missions", Icons.ROUTE), WORKSHOP("Atelier", Icons.WRENCH), USERS("Comptes et rôles", Icons.USERS),
        OVERVIEW("Vue d'ensemble", Icons.HOME), TRACKING("Suivi GPS", Icons.LOCATION), MISSIONS("Missions", Icons.ROUTE), MESSAGES("Messages", Icons.BUBBLE),
        ASSISTANT("Assistant", Icons.SPARK), VEHICLES("Véhicules", Icons.CAR), DUE("Échéances", Icons.CALENDAR),
        DRIVERS("Conducteurs", Icons.USER), CATALOGUE("Catalogue 3D", Icons.CUBE), AUDIT("Journal d'audit", Icons.LIST), MAINTENANCE("Entretiens", Icons.WRENCH),
        FUEL("Carburant", Icons.FUEL), COSTS("Coûts", Icons.CHART), REPORTS("Rapports", Icons.REPORT),
        SETTINGS("Réglages", Icons.GEAR);

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
    private final Region indicator = new Region();
    private final Pane indicatorLayer = new Pane(indicator);
    private final Toasts toasts = new Toasts();
    private final Label dueBadge = Ui.label("", "nav-count", "nav-count-alert");
    private final Label messagesBadge = Ui.label("", "nav-count");
    private Node current;
    private Section currentSection;

    private final java.util.function.BooleanSupplier isDark;
    private final Label bellBadge = Ui.label("", "notif-badge");
    private Node sidePanel;

    public AppShell(FleetGateway gateway, Session session, Runnable onLogout, Runnable onToggleTheme,
                    java.util.function.BooleanSupplier isDark) {
        this.gateway = gateway;
        this.session = session;
        this.onLogout = onLogout;
        this.onToggleTheme = onToggleTheme;
        this.isDark = isDark;
        BorderPane frame = new BorderPane();
        frame.getStyleClass().add("app-backdrop");
        // iOS 26 : barre latérale flottante et décollée des bords (verre teinté), le contenu passe derrière visuellement
        StackPane sideHost = new StackPane(sidebar());
        sideHost.getStyleClass().add("sidebar-host");
        frame.setLeft(sideHost);
        BorderPane main = new BorderPane();
        main.setTop(topbar());
        main.setCenter(content);
        frame.setCenter(main);
        getChildren().addAll(frame, toasts.host());
        sceneProperty().addListener((o, a, scene) -> {
            if (scene != null) {
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN), search::requestFocus);
                List<Section> order = sections(false);
                KeyCode[] keys = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5};
                for (int i = 0; i < Math.min(order.size(), keys.length); i++) {
                    Section s = order.get(i);
                    scene.getAccelerators().put(new KeyCodeCombination(keys[i], KeyCombination.SHORTCUT_DOWN), () -> show(s));
                }
            }
        });
        if (session.can(VEHICLE_READ)) {
            refreshDueBadge();
            refreshBellBadge();
        }
        refreshMessagesBadge();
        show(home());
    }

    // ------------------------------------------------------------------ profils : navigation selon les permissions

    /** Visibilité d'une section pour la session ; le serveur revérifie chaque appel (masquage ≠ sécurité). */
    public boolean visible(Section s) {
        return visibleFor(s, session);
    }

    /** Règle de navigation pure (testée sans interface) : section visible pour une session donnée. */
    public static boolean visibleFor(Section s, Session session) {
        return switch (s) {
            case MY_MISSIONS -> session.can(MISSION_DRIVE) && !session.can(MISSION_MANAGE);
            case WORKSHOP -> session.can(MAINTENANCE_WRITE);
            case USERS -> session.can(ADMIN_USERS);
            case OVERVIEW -> session.can(VEHICLE_READ) && session.can(COST_READ);
            case MISSIONS -> session.can(MISSION_MANAGE) || session.can(GPS_READ);
            case TRACKING -> session.can(MISSION_MANAGE) || session.can(GPS_READ);
            case MESSAGES -> session.can(CHAT_USE);
            case ASSISTANT -> session.can(ASSISTANT_USE);
            case VEHICLES, DUE -> session.can(VEHICLE_READ);
            case DRIVERS -> session.can(DRIVER_READ);
            case CATALOGUE -> session.can(VEHICLE_READ) || session.can(MAINTENANCE_WRITE);
            case AUDIT -> session.can(AUDIT_READ);
            case MAINTENANCE -> session.can(VEHICLE_WRITE);
            case FUEL -> session.can(FUEL_ORDER_CREATE) || session.can(FUEL_ORDER_APPROVE) || session.can(FUEL_FILL_CREATE);
            case COSTS -> session.can(COST_READ);
            case REPORTS -> session.can(REPORT_EXPORT);
            case SETTINGS -> true;
        };
    }

    /** Tous les modules sont livrés : plus de section « À venir ». */
    private static boolean upcoming(Section s) {
        return false;
    }

    /** Sections livrées (ou à venir) visibles pour ce profil, dans l'ordre de la barre latérale. */
    public List<Section> sections(boolean upcomingOnes) {
        return sectionsFor(session, upcomingOnes);
    }

    public static List<Section> sectionsFor(Session session, boolean upcomingOnes) {
        return java.util.Arrays.stream(Section.values())
                .filter(s -> s != Section.SETTINGS && visibleFor(s, session) && upcoming(s) == upcomingOnes).toList();
    }

    public Section home() {
        List<Section> l = sections(false);
        return l.isEmpty() ? Section.SETTINGS : l.get(0);
    }

    public Session session() {
        return session;
    }

    public FleetGateway gateway() {
        return gateway;
    }

    public void refreshMessagesBadge() {
        if (!session.can(CHAT_USE)) return;
        Ui.async(gateway::conversations, list -> {
            long n = list.stream().mapToLong(cm.camtel.nextfleet.desktop.data.Ops.Conversation::unread).sum();
            messagesBadge.setText(String.valueOf(n));
            messagesBadge.setVisible(n > 0);
            messagesBadge.setAccessibleText(n + " messages non lus");
        }, err -> messagesBadge.setVisible(false));
    }

    // ------------------------------------------------------------------ actions partagées (Réglages, alertes, notifications)

    public boolean isDark() {
        return isDark.getAsBoolean();
    }

    public void setDark(boolean dark) {
        if (dark != isDark()) onToggleTheme.run();
    }

    /** Déconnexion confirmée par une alerte (HIG : titre précis, Annuler à gauche, action à droite). */
    public void confirmLogout() {
        AlertDialog.show(this, Ui.symbolTile(Icons.LOCK, "tile-navy"), "Se déconnecter de NexFleet ?",
                "Votre session sera révoquée sur ce poste.",
                java.util.List.of(AlertDialog.Action.cancel("Annuler"),
                        AlertDialog.Action.preferred("Se déconnecter", onLogout)));
    }

    public void openNotificationCenter() {
        NotificationCenter nc = new NotificationCenter(gateway, this::closeSidePanel, n -> {
            closeSidePanel();
            if (n.targetSection() == null) return;
            String target = n.targetSection().toUpperCase(java.util.Locale.ROOT);
            switch (target) {
                case "VEHICLES" -> openVehicles(n.targetQuery());
                case "FUEL" -> show(Section.FUEL);
                case "WORKSHOP" -> show(visible(Section.WORKSHOP) ? Section.WORKSHOP : Section.MAINTENANCE);
                case "ANOMALIES" -> show(Section.OVERVIEW);
                case "MISSIONS" -> show(visible(Section.MISSIONS) ? Section.MISSIONS : Section.MY_MISSIONS);
                default -> {
                    try {
                        show(Section.valueOf(target));
                    } catch (IllegalArgumentException e) {
                        show(home());
                    }
                }
            }
        }, this::refreshBellBadge);
        showSidePanel(nc, 400);
    }

    /** Feuille latérale (formulaire, détail) décollée des bords ; Échap ou clic sur le voile la ferme. */
    public void openSheet(javafx.scene.layout.Region panel, double width) {
        showSidePanel(panel, width);
    }

    public void closeSheet() {
        closeSidePanel();
    }

    private void showSidePanel(javafx.scene.layout.Region panel, double width) {
        closeSidePanelNow();
        javafx.scene.layout.Region scrim = new javafx.scene.layout.Region();
        scrim.getStyleClass().add("scrim");
        scrim.setOnMouseClicked(e -> closeSidePanel());
        panel.setMaxWidth(width);
        panel.setPrefWidth(width);
        StackPane layer = new StackPane(scrim, panel);
        StackPane.setAlignment(panel, Pos.CENTER_RIGHT);
        StackPane.setMargin(panel, new Insets(12));   // feuille décollée des bords (iOS 26)
        Ui.rounded(panel, 28);
        layer.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) closeSidePanel();
        });
        sidePanel = layer;
        getChildren().add(getChildren().size() - 1, layer);   // sous les bannières
        Motion.enter(scrim, javafx.util.Duration.ZERO, 0);
        Motion.slideInFromRight(panel, width);
        layer.requestFocus();
    }

    private void closeSidePanel() {
        Node layer = sidePanel;
        if (layer == null) return;
        sidePanel = null;
        Node panel = ((StackPane) layer).getChildren().get(1);
        Motion.slideOutToRight(panel, 420, () -> getChildren().remove(layer));
    }

    private void closeSidePanelNow() {
        if (sidePanel != null) getChildren().remove(sidePanel);
        sidePanel = null;
    }

    private void refreshBellBadge() {
        Ui.async(gateway::notifications, list -> {
            long n = NotificationCenter.unread(list);
            bellBadge.setText(n > 99 ? "99+" : String.valueOf(n));
            bellBadge.setVisible(n > 0);
            bellBadge.setAccessibleText(n + " notifications non lues");
        }, err -> bellBadge.setVisible(false));
    }

    public Toasts toasts() {
        return toasts;
    }

    // ------------------------------------------------------------------ barre latérale

    private VBox sidebar() {
        HBox brand = new HBox(10, Icons.logo(28), Ui.label("NexFleet", "brand-name"));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setStyle("-fx-padding: 4 8 8 8;");

        VBox items = new VBox(4);
        for (Section s : sections(false)) items.getChildren().add(navButton(s));
        List<Section> later = sections(true);
        if (!later.isEmpty()) {
            Label soon = Ui.label("À VENIR", "section-title");
            soon.setStyle("-fx-padding: 14 12 4 12;");
            items.getChildren().add(soon);
            for (Section s : later) {
                ToggleButton b = navButton(s);
                b.setOpacity(0.62);
                items.getChildren().add(b);
            }
        }
        indicator.getStyleClass().add("nav-indicator");
        indicator.setManaged(false);
        indicator.setOpacity(0);
        indicatorLayer.setMouseTransparent(true);
        StackPane navStack = new StackPane(indicatorLayer, items);
        navStack.setAlignment(Pos.TOP_LEFT);

        VBox box = new VBox(brand, Ui.label("VOTRE ESPACE", "section-title"), navStack, Ui.spacer(), navButton(Section.SETTINGS));
        box.getStyleClass().add("sidebar");
        StackPane mark = Ui.symbolTile(Icons.GAUGE, "tile-mint");
        cm.camtel.nextfleet.domain.security.Role role = session.primaryRole();
        if (role != null) mark = RoleUi.symbol(role);
        VBox who = new VBox(Ui.label("CAMTEL", "w-700", "t-12"), Ui.label(role == null ? "Gestion de flotte" : role.label, "muted", "t-12"));
        HBox footer = new HBox(10, mark, who);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("sidebar-footer");
        box.getChildren().add(footer);

        group.selectedToggleProperty().addListener((o, a, b) -> Platform.runLater(this::moveIndicator));
        items.heightProperty().addListener((o, a, b) -> moveIndicator());
        return box;
    }

    private ToggleButton navButton(Section s) {
        ToggleButton b = new ToggleButton(s.label, Icons.icon(s.icon, 18));
        b.getStyleClass().add("nav-item");
        b.setToggleGroup(group);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAccessibleText("Ouvrir " + s.label);
        b.setOnAction(e -> {
            if (!b.isSelected()) b.setSelected(true);
            show(s);
        });
        if (s == Section.DUE || s == Section.MESSAGES) {
            Label badge = s == Section.DUE ? dueBadge : messagesBadge;
            b.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
            HBox g = new HBox(12, Icons.icon(s.icon, 18), Ui.label(s.label), Ui.spacer(), badge);
            g.setAlignment(Pos.CENTER_LEFT);
            g.setMaxWidth(Double.MAX_VALUE);
            g.prefWidthProperty().bind(b.widthProperty().subtract(26));
            ((Label) g.getChildren().get(1)).textFillProperty().bind(b.textFillProperty());
            b.setText(null);
            b.setGraphic(g);
            badge.setVisible(false);
        }
        Motion.pressable(b);
        // l'indicateur se recale dès que la largeur réelle du bouton est connue (premier affichage)
        b.widthProperty().addListener((o, w0, w1) -> {
            if (b.isSelected()) Platform.runLater(this::moveIndicator);
        });
        nav.put(s, b);
        return b;
    }

    /** L'indicateur suit l'élément actif avec un ressort ; il est placé sans animation au premier affichage. */
    private void moveIndicator() {
        ToggleButton sel = (ToggleButton) group.getSelectedToggle();
        if (sel == null || sel.getParent() == null || !indicatorLayer.isVisible()) return;
        boolean inStack = sel.getParent().getParent() == indicatorLayer.getParent();
        if (!inStack) {   // Paramètres : hors de la pile principale → fond plein sur le bouton
            indicator.setOpacity(0);
            sel.setStyle("-fx-background-color: -nf-select;");
            return;
        }
        nav.values().forEach(b -> b.setStyle(null));
        Bounds bb = sel.getBoundsInParent();
        if (bb.getWidth() < 60) {   // mise en page pas encore terminée
            Platform.runLater(this::moveIndicator);
            return;
        }
        indicator.resizeRelocate(0, 0, bb.getWidth(), bb.getHeight());
        Motion.slideTo(indicator, bb.getMinY());
    }

    private void refreshDueBadge() {
        Ui.async(() -> gateway.dueDates(30), list -> {
            long n = list.stream().filter(d -> d.level() != DueLevel.A_JOUR).count();
            dueBadge.setText(String.valueOf(n));
            dueBadge.setVisible(n > 0);
            dueBadge.setAccessibleText(n + " échéances à traiter");
        }, err -> dueBadge.setVisible(false));
    }

    // ------------------------------------------------------------------ barre supérieure

    private HBox topbar() {
        search.setPromptText("Rechercher un véhicule, un conducteur…");
        search.setTooltip(new Tooltip("Recherche globale (Ctrl+K)"));
        search.getStyleClass().add("search-field");
        search.setAccessibleText("Recherche globale");
        StackPane searchBox = new StackPane(search, Icons.stroke(Icons.SEARCH, 16, "-nf-muted"));
        searchBox.getStyleClass().addAll("glass", "search-capsule");
        StackPane.setAlignment(searchBox.getChildren().get(1), Pos.CENTER_LEFT);
        searchBox.getChildren().get(1).setTranslateX(12);
        searchBox.getChildren().get(1).setMouseTransparent(true);
        search.setOnAction(e -> openVehicles(search.getText()));

        HBox bar = session.can(VEHICLE_READ) ? new HBox(searchBox, Ui.spacer()) : new HBox(Ui.spacer());
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

        Button bell = new Button(null, Icons.stroke(Icons.BELL, 18, "-nf-muted"));
        bell.getStyleClass().add("icon-button");
        bell.setAccessibleText("Notifications");
        bell.setTooltip(new Tooltip("Centre de notifications"));
        bell.setOnAction(e -> openNotificationCenter());
        bellBadge.setVisible(false);
        bellBadge.setMouseTransparent(true);
        StackPane bellBox = new StackPane(bell, bellBadge);
        StackPane.setAlignment(bellBadge, Pos.TOP_RIGHT);
        bellBadge.setTranslateX(6);
        bellBadge.setTranslateY(-3);

        StackPane avatar = new StackPane(Ui.label(initials(session.displayName())));
        avatar.getStyleClass().add("avatar");
        Button user = new Button(session.displayName(), avatar);
        user.getStyleClass().add("btn-ghost");
        user.setStyle("-fx-text-fill: -nf-text; -fx-font-size: 12px;");
        user.setGraphicTextGap(8);
        user.setTooltip(new Tooltip("Compte et réglages"));
        user.setAccessibleText("Compte de " + session.displayName() + ". Ouvrir les réglages.");
        user.setOnAction(e -> show(Section.SETTINGS));
        user.setStyle("-fx-text-fill: -nf-text; -fx-font-size: 13px; -fx-font-weight: 500;");
        for (Node n : new Node[]{theme, bell, user}) Motion.pressable(n);
        // Barre d'outils iOS 26 : icônes regroupées dans une seule capsule de verre, compte dans la sienne
        HBox tools = new HBox(2, theme);
        if (session.can(VEHICLE_READ)) tools.getChildren().add(bellBox);
        tools.getStyleClass().addAll("glass", "toolbar-group");
        tools.setAlignment(Pos.CENTER);
        user.getStyleClass().add("toolbar-account");
        HBox account = new HBox(user);
        account.getStyleClass().addAll("glass", "toolbar-group");
        account.setAlignment(Pos.CENTER);
        bar.getChildren().addAll(tools, account);
        return bar;
    }

    // ------------------------------------------------------------------ navigation

    public void show(Section requested) {
        closeSidePanelNow();
        Section s = visible(requested) ? requested : home();
        ToggleButton b = nav.get(s);
        if (b != null) b.setSelected(true);
        currentSection = s;
        Node view = switch (s) {
            case MY_MISSIONS -> new DriverHomeView(this);
            case WORKSHOP, MAINTENANCE -> new WorkOrdersView(this, s);
            case FUEL -> new FuelView(this);
            case COSTS -> new CostsView(this);
            case REPORTS -> new ReportsView(this);
            case USERS -> new UsersView(this);
            case MISSIONS -> new MissionsView(this);
            case TRACKING -> new FleetTrackingView(this);
            case MESSAGES -> new MessagesView(this, null);
            case ASSISTANT -> new AssistantView(this);
            case AUDIT -> new AuditView(this);
            case OVERVIEW -> new DashboardView(gateway, session, this);
            case VEHICLES -> new VehiclesView(gateway, session, null, this);
            case DUE -> new DueDatesView(gateway, this);
            case DRIVERS -> new DriversView(gateway);
            case CATALOGUE -> new CatalogueView(this);
            case SETTINGS -> new SettingsView(gateway, session, this);
            default -> unavailable(s);
        };
        setContent(view);
    }

    /** Ouvre la messagerie sur une conversation donnée (depuis une mission). */
    public void openConversation(String conversationId) {
        if (!visible(Section.MESSAGES)) return;
        closeSidePanelNow();
        nav.get(Section.MESSAGES).setSelected(true);
        currentSection = Section.MESSAGES;
        setContent(new MessagesView(this, conversationId));
    }

    public void openVehicles(String query) {
        if (!visible(Section.VEHICLES)) return;
        closeSidePanelNow();
        nav.get(Section.VEHICLES).setSelected(true);
        currentSection = Section.VEHICLES;
        setContent(new VehiclesView(gateway, session, query, this));
    }

    public Node currentView() {
        return current;
    }

    public Section currentSection() {
        return currentSection;
    }

    private void setContent(Node view) {
        current = view;
        content.getChildren().setAll(view);
        Motion.enter(view, javafx.util.Duration.ZERO, 8);
    }

    /** Module non livré dans ce lot : affiché comme tel, jamais simulé (règle du prompt maître). */
    private Node unavailable(Section s) {
        String lot = switch (s) {
            case MAINTENANCE -> "lot 2 (NF-13 à NF-16) — tables de contrôle 12 points, maintenance, vidanges et pneus déjà prêtes en base";
            case FUEL -> "lot 3 (chaîne carburant : NF-40 à NF-45) — règles et base déjà réalisées côté serveur";
            case COSTS, REPORTS -> "lot 3 (NF-21, NF-22, NF-25)";
            default -> "lot 2 (NF-03)";
        };
        VBox p = Ui.statePanel(s.icon, s.label + " — module non disponible dans cette version",
                "Ce module est prévu au " + lot + ". Aucune donnée n'est affichée tant qu'il n'est pas réalisé et recetté.");
        p.getStyleClass().add("content");
        return p;
    }

    static String greeting() {
        int h = LocalTime.now(ZoneId.of("Africa/Douala")).getHour();
        return h < 5 || h >= 18 ? "BONSOIR" : "BONJOUR";
    }

    private static String initials(String name) {
        String[] parts = name.replaceAll("\\(.*\\)", "").trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) if (!p.isEmpty() && b.length() < 2) b.append(Character.toUpperCase(p.charAt(0)));
        return b.length() == 0 ? "?" : b.toString();
    }
}
