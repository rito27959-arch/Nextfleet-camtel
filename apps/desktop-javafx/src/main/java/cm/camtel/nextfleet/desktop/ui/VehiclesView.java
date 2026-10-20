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

    public VehiclesView(FleetGateway gateway, Session session, String initialQuery) {
        this.gateway = gateway;
        this.session = session;
        VBox content = new VBox(16, header(), toolbar(), body);
        content.getStyleClass().add("content");
        setCenter(content);

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
        VBox titles = new VBox(4, Ui.label("Votre flotte", "h1"), Ui.label("Retrouvez votre véhicule.", "muted"));
        HBox h = new HBox(titles, Ui.spacer());
        h.setAlignment(Pos.BOTTOM_LEFT);
        Button add = new Button("Ajouter", Icons.stroke(Icons.PLUS, 16, "white"));
        add.getStyleClass().add("btn-primary");
        if (session.has("FLEET_MANAGER")) {
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
        setRight(new VehicleSheet(v, gateway.isDemo(), () -> {
            setRight(null);
            table.getSelectionModel().clearSelection();
        }));
    }

    private void openForm() {
        VehicleFormView form = new VehicleFormView(gateway, created -> {
            setRight(null);
            search.setText(created.registration());
        }, () -> setRight(null));
        setRight(form);
    }

    /** Pour la recette automatisée : sélectionne le premier véhicule affiché. */
    public void selectFirstForTest() {
        if (!table.getItems().isEmpty()) table.getSelectionModel().select(0);
    }

    public void openFormForTest() {
        openForm();
    }
}
