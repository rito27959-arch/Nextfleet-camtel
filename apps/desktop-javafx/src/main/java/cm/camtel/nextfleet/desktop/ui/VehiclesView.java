package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.Page;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Écran 03 — Liste des véhicules : recherche, filtres, pagination, fiche latérale (écran 10, partiel). */
public final class VehiclesView extends BorderPane {

    private static final int PAGE_SIZE = 10;

    private final FleetGateway gateway;
    private final Session session;
    private final TextField search = new TextField();
    private final ToggleGroup filters = new ToggleGroup();
    private final TableView<Vehicle> table = VehicleTable.create(false);
    private final VBox body = new VBox(12);
    private final Label pageInfo = Ui.label("", "muted", "t-12");
    private final Button prev = new Button("Précédent");
    private final Button next = new Button("Suivant");
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
    private VehicleStatus status;
    private int page;
    private long total;

    private final AppShell shell;
    private final StackPane layers = new StackPane();
    private Node overlay;

    public VehiclesView(FleetGateway gateway, Session session, String initialQuery, AppShell shell) {
        this.gateway = gateway;
        this.session = session;
        this.shell = shell;
        VBox content = new VBox(16, header(), toolbar(), body);
        content.getStyleClass().add("content");
        layers.getChildren().add(content);
        setCenter(layers);

        prev.getStyleClass().add("btn-secondary");
        next.getStyleClass().add("btn-secondary");
        prev.setOnAction(e -> { page--; load(); });
        next.setOnAction(e -> { page++; load(); });
        table.getSelectionModel().selectedItemProperty().addListener((o, a, v) -> {
            if (v != null) openSheet(v);
        });
        if (initialQuery != null) search.setText(initialQuery);
        debounce.setOnFinished(e -> { page = 0; load(); });
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        load();
    }

    private Node header() {
        VBox titles = new VBox(4, Ui.label("Votre flotte", "large-title"), Ui.label("Retrouvez votre véhicule.", "body", "muted"));
        HBox h = new HBox(titles, Ui.spacer());
        h.setAlignment(Pos.BOTTOM_LEFT);
        Button add = new Button("Nouveau véhicule", Icons.stroke(Icons.PLUS, 15, "white"));
        add.getStyleClass().add("btn-primary");
        if (session.can(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_WRITE)) {
            add.setOnAction(e -> openForm());
            h.getChildren().add(add);
        } else {
            add.setDisable(true);
            h.getChildren().add(DashboardView.wrapTooltip(add, "Réservé au gestionnaire de flotte (vehicle:write)."));
        }
        return h;
    }

    private Node toolbar() {
        search.setPromptText("Plaque, modèle, conducteur…");
        search.getStyleClass().add("search-field");
        search.setPrefWidth(280);
        search.setMinWidth(220);
        search.setAccessibleText("Rechercher un véhicule");
        StackPane sbox = new StackPane(search, Icons.stroke(Icons.SEARCH, 16, "-nf-muted"));
        StackPane.setAlignment(sbox.getChildren().get(1), Pos.CENTER_LEFT);
        sbox.getChildren().get(1).setTranslateX(12);
        sbox.getChildren().get(1).setMouseTransparent(true);
        HBox chips = new HBox(8);
        chips.setAlignment(Pos.CENTER_LEFT);
        addChip(chips, "Tous", null).setSelected(true);
        addChip(chips, "En service", VehicleStatus.IN_SERVICE);
        addChip(chips, "En déplacement", VehicleStatus.ON_MISSION);
        addChip(chips, "Disponible", VehicleStatus.AVAILABLE);
        addChip(chips, "Entretien", VehicleStatus.MAINTENANCE);
        addChip(chips, "Hors service", VehicleStatus.OUT_OF_SERVICE);
        javafx.scene.control.ScrollPane chipScroll = new javafx.scene.control.ScrollPane(chips);
        chipScroll.setFitToHeight(true);
        chipScroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        chipScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        chipScroll.setStyle("-fx-background-color: transparent; -fx-padding: 2;");
        HBox.setHgrow(chipScroll, javafx.scene.layout.Priority.ALWAYS);
        HBox bar = new HBox(16, sbox, chipScroll);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private ToggleButton addChip(HBox box, String label, VehicleStatus s) {
        ToggleButton b = new ToggleButton(label);
        b.getStyleClass().add("chip");
        b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        b.setToggleGroup(filters);
        b.setOnAction(e -> {
            if (!b.isSelected()) b.setSelected(true);   // un filtre est toujours actif
            status = s;
            page = 0;
            load();
        });
        b.setAccessibleText("Filtrer : " + label);
        box.getChildren().add(b);
        return b;
    }

    private void load() {
        VBox sk = new VBox(8);
        for (int i = 0; i < 6; i++) sk.getChildren().add(Ui.skeleton(-1, 40));
        sk.getStyleClass().add("card");
        body.getChildren().setAll(sk);
        String q = search.getText();
        VehicleStatus st = status;
        int p = page;
        Ui.async(() -> gateway.vehicles(q, st, p, PAGE_SIZE), this::render, this::fail);
    }

    private void render(Page<Vehicle> r) {
        total = r.total();
        if (r.items().isEmpty()) {
            boolean filtered = !search.getText().isBlank() || status != null;
            body.getChildren().setAll(Ui.statePanel(Icons.SEARCH,
                    filtered ? "Aucun véhicule ne correspond" : "Aucun véhicule dans votre périmètre",
                    filtered ? "Modifiez la recherche ou le filtre." : "Les véhicules de votre entité apparaîtront ici."));
            return;
        }
        table.setItems(FXCollections.observableArrayList(r.items()));
        table.setFixedCellSize(52);
        table.setPrefHeight(52 * r.items().size() + 42);
        long from = (long) r.page() * r.size() + 1;
        long to = from + r.items().size() - 1;
        pageInfo.setText(from + "–" + to + " sur " + total + " véhicule" + (total > 1 ? "s" : ""));
        prev.setDisable(page == 0);
        next.setDisable(to >= total);
        HBox pager = new HBox(10, pageInfo, Ui.spacer(), prev, next);
        pager.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(8, table, pager);
        card.getStyleClass().add("card");
        body.getChildren().setAll(card);
    }

    private void fail(GatewayException e) {
        body.getChildren().setAll(Ui.errorBanner(e, this::load));
    }

    private void openSheet(Vehicle v) {
        VehicleSheet sheet = new VehicleSheet(v, gateway, shell, () -> {
            closeOverlay();
            table.getSelectionModel().clearSelection();
        }, session.can(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_ARCHIVE) ? () -> confirmArchive(v) : null);
        showOverlay(sheet, 420);
    }

    private void openForm() {
        VehicleFormView form = new VehicleFormView(gateway, created -> {
            closeOverlay();
            search.setText(created.registration());
            if (shell != null) shell.toasts().success("Véhicule enregistré", created.registration() + " · " + created.label());
        }, this::closeOverlay);
        showOverlay(form, 460);
    }

    /**
     * Archivage confirmé par une alerte (HIG : action délibérée → bouton « Archiver » en style normal, préféré ;
     * Annuler à gauche). En cas de conflit de version, l'erreur est expliquée par une seconde alerte.
     */
    private void confirmArchive(Vehicle v) {
        StackPane host = shell;
        AlertDialog.show(host, Ui.symbolTile(Icons.DOC, "tile-orange"), "Archiver " + v.registration() + " ?",
                "Le véhicule disparaîtra du parc actif. Son historique et ses pièces restent consultables.",
                java.util.List.of(AlertDialog.Action.cancel("Annuler"), AlertDialog.Action.preferred("Archiver", () ->
                        Ui.async(() -> gateway.archiveVehicle(v.id(), v.version(), "Archivage depuis la fiche véhicule"), a -> {
                            closeOverlay();
                            shell.toasts().show(Ui.symbolTile(Icons.CHECK, "tile-green"), "Véhicule archivé",
                                    a.registration() + " a été retiré du parc actif.", null);
                            load();
                        }, err -> AlertDialog.show(host, null, "Archivage impossible", err.getMessage(),
                                java.util.List.of(AlertDialog.Action.preferred("OK", () -> { })))))));
    }

    /** Feuille latérale sur voile : glisse depuis la droite avec un ressort ; clic sur le voile ou Échap pour fermer. */
    private void showOverlay(javafx.scene.layout.Region panel, double width) {
        if (overlay != null) layers.getChildren().remove(overlay);
        javafx.scene.layout.Region scrim = new javafx.scene.layout.Region();
        scrim.getStyleClass().add("scrim");
        scrim.setOnMouseClicked(e -> {
            closeOverlay();
            table.getSelectionModel().clearSelection();
        });
        panel.setMaxWidth(width);
        panel.setPrefWidth(width);
        StackPane o = new StackPane(scrim, panel);
        StackPane.setAlignment(panel, Pos.CENTER_RIGHT);
        o.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) closeOverlay();
        });
        overlay = o;
        layers.getChildren().add(o);
        Motion.enter(scrim, javafx.util.Duration.ZERO, 0);
        Motion.slideInFromRight(panel, width);
        panel.requestFocus();
    }

    private void closeOverlay() {
        Node o = overlay;
        if (o == null) return;
        overlay = null;
        Node panel = ((StackPane) o).getChildren().get(1);
        Motion.slideOutToRight(panel, 460, () -> layers.getChildren().remove(o));
        javafx.animation.FadeTransition f = new javafx.animation.FadeTransition(Motion.FAST, ((StackPane) o).getChildren().get(0));
        f.setToValue(0);
        if (!Motion.isReduced()) f.play();
    }

    /** Pour la recette automatisée : sélectionne le premier véhicule affiché. */
    public void selectFirstForTest() {
        if (!table.getItems().isEmpty()) table.getSelectionModel().select(0);
    }

    public void archiveFirstForTest() {
        if (!table.getItems().isEmpty()) confirmArchive(table.getItems().get(0));
    }

    public void openFormForTest() {
        openForm();
    }
}
