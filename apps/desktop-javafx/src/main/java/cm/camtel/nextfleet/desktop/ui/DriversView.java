package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.DueItem;
import cm.camtel.nextfleet.desktop.data.Models.DueKind;
import cm.camtel.nextfleet.desktop.data.Models.DueLevel;
import cm.camtel.nextfleet.desktop.data.Models.Driver;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Conducteurs (NF-12) — parité avec l'ancien NexFleet : matricule, téléphone, permis et catégories, CNI,
 * véhicule affecté, compte à rebours des validités. Lecture seule dans ce lot ; saisie au lot 2.
 */
public final class DriversView extends ScrollPane {

    private final FleetGateway gateway;
    private final TextField search = new TextField();
    private final VBox body = new VBox(16);
    private final PauseTransition debounce = new PauseTransition(Duration.millis(220));

    public DriversView(FleetGateway gateway) {
        this.gateway = gateway;
        setFitToWidth(true);
        VBox titles = new VBox(4, Ui.label("Conducteurs", "large-title"),
                Ui.label("Permis, pièces d'identité et affectations, avec leurs échéances.", "body", "muted"));
        search.setPromptText("Nom ou matricule…");
        search.getStyleClass().add("search-field");
        search.setPrefWidth(300);
        search.setAccessibleText("Rechercher un conducteur");
        StackPane sbox = new StackPane(search, Icons.stroke(Icons.SEARCH, 16, "-nf-muted"));
        StackPane.setAlignment(sbox.getChildren().get(1), Pos.CENTER_LEFT);
        sbox.getChildren().get(1).setTranslateX(12);
        sbox.getChildren().get(1).setMouseTransparent(true);
        HBox head = new HBox(titles, Ui.spacer(), sbox);
        head.setAlignment(Pos.BOTTOM_LEFT);
        VBox root = new VBox(20, head, body);
        root.getStyleClass().add("content");
        setContent(root);
        debounce.setOnFinished(e -> load());
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        load();
    }

    private void load() {
        FlowPane sk = new FlowPane(16, 16);
        for (int i = 0; i < 6; i++) {
            VBox c = new VBox(10, Ui.skeleton(46, 46), Ui.skeleton(180, 14), Ui.skeleton(120, 12));
            c.getStyleClass().addAll("card", "driver-card");
            sk.getChildren().add(c);
        }
        body.getChildren().setAll(sk);
        String q = search.getText();
        Ui.async(() -> gateway.drivers(q), this::render, this::fail);
    }

    private void fail(GatewayException e) {
        body.getChildren().setAll(Ui.errorBanner(e, this::load));
    }

    private void render(List<Driver> drivers) {
        if (drivers.isEmpty()) {
            body.getChildren().setAll(Ui.statePanel(Icons.USER, "Aucun conducteur ne correspond", "Modifiez la recherche."));
            return;
        }
        FlowPane grid = new FlowPane(16, 16);
        int i = 0;
        for (Driver d : drivers) grid.getChildren().add(card(d, i++));
        Label count = Ui.label(drivers.size() + " conducteur" + (drivers.size() > 1 ? "s" : ""), "muted", "t-12");
        body.getChildren().setAll(count, grid);
        Motion.stagger(grid.getChildren());
    }

    private Node card(Driver d, int index) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Douala"));
        VBox name = new VBox(2, Ui.label(d.fullName(), "headline"), Ui.label(d.staffNumber() + " · " + d.site(), "muted", "t-12"));
        HBox top = new HBox(12, Ui.avatar(d.initials(), index), name);
        top.setAlignment(Pos.CENTER_LEFT);

        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);
        VBox rows = new VBox(10,
                row(Icons.CAR, "Véhicule affecté", d.assignedVehicle() == null ? "Aucun" : d.assignedVehicle(), null),
                row(Icons.PHONE, "Téléphone", d.phone(), null),
                row(Icons.ID_CARD, "Permis " + String.join(", ", d.licenseCategories()), d.licenseExpiresOn().format(f),
                        Ui.dueChip(due(DueKind.PERMIS, d, d.licenseExpiresOn(), today))),
                d.idCardExpiresOn() == null
                        ? row(Icons.SHIELD, "Carte d'identité", "Non renseignée", null)
                        : row(Icons.SHIELD, "Carte d'identité", d.idCardExpiresOn().format(f),
                        Ui.dueChip(due(DueKind.CNI, d, d.idCardExpiresOn(), today))));
        VBox card = new VBox(14, top, Ui.divider(), rows);
        card.getStyleClass().addAll("card", "driver-card");
        card.setAccessibleText("Conducteur " + d.fullName() + ", matricule " + d.staffNumber());
        Motion.hoverLift(card);
        return card;
    }

    private static DueItem due(DueKind k, Driver d, LocalDate on, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, on);
        DueLevel level = days < 0 ? DueLevel.EXPIRE : days <= 30 ? DueLevel.A_ECHEANCE : DueLevel.A_JOUR;
        return new DueItem(k, d.fullName(), d.staffNumber(), false, on, days, level);
    }

    private static Node row(String icon, String caption, String value, Node trailing) {
        VBox kv = new VBox(1, Ui.label(caption, "kv-caption"), Ui.label(value, "kv-value"));
        HBox h = new HBox(10, Icons.stroke(icon, 16, "-nf-muted"), kv, Ui.spacer());
        if (trailing != null) h.getChildren().add(trailing);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }
}
