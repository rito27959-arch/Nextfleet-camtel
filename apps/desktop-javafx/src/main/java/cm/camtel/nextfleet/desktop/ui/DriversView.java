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
 * véhicule affecté, compte à rebours des validités ; création avec permis.
 */
public final class DriversView extends ScrollPane {

    private final FleetGateway gateway;
    private final TextField search = new TextField();
    private final VBox body = new VBox(16);
    private final PauseTransition debounce = new PauseTransition(Duration.millis(220));

    private final AppShell shell;

    public DriversView(FleetGateway gateway) {
        this(gateway, null);
    }

    public DriversView(FleetGateway gateway, AppShell shell) {
        this.gateway = gateway;
        this.shell = shell;
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
        HBox head = new HBox(12, titles, Ui.spacer(), sbox);
        if (shell != null && !gateway.isDemo() && shell.session().can(cm.camtel.nextfleet.domain.security.Permission.DRIVER_WRITE)) {
            head.getChildren().add(Views.filled("Nouveau conducteur", Icons.PLUS, this::createSheet));
        }
        head.setAlignment(Pos.BOTTOM_LEFT);
        VBox root = new VBox(20, head, body);
        root.getStyleClass().add("content");
        setContent(root);
        debounce.setOnFinished(e -> load());
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        load();
    }

    /** Pré-remplit la recherche (depuis la recherche globale). */
    void query(String q) {
        search.setText(q);
    }

    private void load() {
        String q = search.getText();
        Ui.load("drivers:" + q, () -> gateway.drivers(q), this::render, this::fail, () -> {
            FlowPane sk = new FlowPane(16, 16);
            for (int i = 0; i < 6; i++) {
                VBox c = new VBox(10, Ui.skeleton(46, 46), Ui.skeleton(180, 14), Ui.skeleton(120, 12));
                c.getStyleClass().addAll("card", "driver-card");
                sk.getChildren().add(c);
            }
            body.getChildren().setAll(sk);
        });
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
                d.licenseExpiresOn() == null
                        ? row(Icons.ID_CARD, "Permis", "Non renseigné", null)
                        : row(Icons.ID_CARD, "Permis " + String.join(", ", d.licenseCategories()), d.licenseExpiresOn().format(f),
                        Ui.dueChip(due(DueKind.PERMIS, d, d.licenseExpiresOn(), today))),
                d.idCardExpiresOn() == null
                        ? row(Icons.SHIELD, "Carte d'identité", "Non renseignée", null)
                        : row(Icons.SHIELD, "Carte d'identité", d.idCardExpiresOn().format(f),
                        Ui.dueChip(due(DueKind.CNI, d, d.idCardExpiresOn(), today))));
        if (!gateway.isDemo()) {
            // compte lié : condition pour recevoir une mission (le conducteur la démarre sur son téléphone)
            rows.getChildren().add(d.userLogin() != null
                    ? row(Icons.LOCK, "Compte NexFleet", d.userLogin(), null)
                    : row(Icons.LOCK, "Compte NexFleet", "Aucun : ne peut pas recevoir de mission", Views.pill("Sans compte", "warning")));
        }
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

    private void createSheet() {
        Ui.async(() -> gateway.orgUnits("driver:write"), units -> {
            if (units.isEmpty()) {
                shell.toasts().info("Création impossible", "Vous ne gérez les conducteurs d'aucune entité.");
                return;
            }
            var org = FormSheet.combo(units);
            var staff = FormSheet.text("Ex. MAT-2041");
            var name = FormSheet.text("Prénom et nom");
            var phone = FormSheet.text("+237 6 …");
            var email = FormSheet.text("Facultatif");
            var cni = FormSheet.text("Numéro de CNI");
            var cniUntil = FormSheet.date(null);
            var license = FormSheet.text("Numéro du permis");
            var cats = FormSheet.text("Ex. B, C");
            cats.setText("B");
            var licUntil = FormSheet.date(null);
            FormSheet f = new FormSheet("Nouveau conducteur", "Ajouter", shell::closeSheet)
                    .intro("Les dates de fin du permis et de la CNI alimentent les échéances et les notifications.")
                    .row("Matricule *", staff, "Nom complet *", name).row("Téléphone", phone, "E-mail", email)
                    .field("Entité *", org).section("Pièce d'identité").row("Numéro", cni, "Valable jusqu'au", cniUntil)
                    .section("Permis de conduire").field("Numéro", license).row("Catégories", cats, "Valable jusqu'au", licUntil);
            f.onSubmit(() -> {
                java.util.List<String> categories = java.util.Arrays.stream(cats.getText().toUpperCase(java.util.Locale.ROOT).split("[ ,;]+"))
                        .filter(c -> !c.isBlank()).toList();
                if (FormSheet.opt(license) != null && licUntil.getValue() == null) {
                    throw new IllegalArgumentException("Permis : indiquez la date de fin de validité.");
                }
                return gateway.createDriver(new cm.camtel.nextfleet.desktop.data.Prod.DriverDraft(FormSheet.req(staff, "Matricule"),
                        FormSheet.req(name, "Nom complet"), FormSheet.opt(phone), FormSheet.opt(email), FormSheet.pick(org, "Entité").id(),
                        FormSheet.opt(cni), cniUntil.getValue(), FormSheet.opt(license), categories, licUntil.getValue()));
            }, (cm.camtel.nextfleet.desktop.data.Models.Driver d) -> {
                shell.closeSheet();
                shell.toasts().success("Conducteur ajouté", d.fullName() + " · " + d.staffNumber());
                load();
            });
            shell.openSheet(f, 500);
        }, err -> shell.toasts().info("Création impossible", err.getMessage()));
    }
}
