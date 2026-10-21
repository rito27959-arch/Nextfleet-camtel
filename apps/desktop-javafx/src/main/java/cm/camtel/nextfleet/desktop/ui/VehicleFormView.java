package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Year;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Écran 04 — Ajout d'un véhicule, étape 1 « Informations » (lot 1).
 * Les étapes Photos et Modèle 3D (écrans 05 à 09) sont signalées comme non disponibles, jamais simulées.
 */
final class VehicleFormView extends ScrollPane {

    private final TextField registration = field("Ex. CE 3205 KJ");
    private final TextField vin = field("17 caractères (facultatif)");
    private final TextField make = field("Ex. Toyota");
    private final TextField model = field("Ex. Hilux");
    private final TextField year = field("Ex. 2022");
    private final ComboBox<String> energy = new ComboBox<>(FXCollections.observableArrayList(
            "DIESEL", "ESSENCE", "HYBRIDE", "ELECTRIQUE", "GPL", "AUTRE"));
    private final TextField tank = field("Litres (facultatif)");
    private final ComboBox<String> categoryBox = new ComboBox<>(FXCollections.observableArrayList(
            "", "Pick-up", "SUV", "4x4", "Berline", "Minibus", "Fourgon", "Camion", "Moto", "Autre"));
    private final TextField category = field("Ex. Pick-up");   // valeur transmise (liste fermée côté serveur)
    private final TextField odometer = field("Ex. 12500 (facultatif)");
    private final ComboBox<cm.camtel.nextfleet.desktop.data.Prod.OrgUnit> org = new ComboBox<>();
    private final Map<String, Label> errorLabels = new LinkedHashMap<>();
    private final Map<String, Control> controls = new LinkedHashMap<>();
    private final Label globalError = Ui.label("", "error-text");
    private final Button save = new Button("Enregistrer");

    VehicleFormView(FleetGateway gateway, Consumer<Vehicle> onCreated, Runnable onCancel) {
        VBox box = new VBox(10);
        box.getStyleClass().add("sheet");
        box.setPrefWidth(440);
        box.setMinWidth(0);
        setContent(box);
        setFitToWidth(true);
        setPrefWidth(460);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        // Barre de feuille iOS : Annuler (gauche) · titre (centre) · Ajouter (droite, gras)
        Button cancelTop = new Button("Annuler");
        cancelTop.getStyleClass().add("btn-plain");
        cancelTop.setCancelButton(true);
        cancelTop.setOnAction(e -> onCancel.run());
        Button addTop = new Button("Ajouter");
        addTop.getStyleClass().add("btn-plain");
        addTop.setStyle("-fx-font-weight: bold;");
        addTop.setOnAction(e -> save.fire());
        Label sheetTitle = Ui.label("Nouveau véhicule", "headline");
        StackPane title = new StackPane(sheetTitle, cancelTop, addTop);
        StackPane.setAlignment(cancelTop, Pos.CENTER_LEFT);
        StackPane.setAlignment(addTop, Pos.CENTER_RIGHT);
        title.setMinHeight(40);

        HBox steps = new HBox(8, step("1", "Infos", "step-active"), line(), step("2", "Photos", "step-unavailable"),
                line(), step("3", "3D", "step-unavailable"), line(), step("4", "Valider", ""));
        steps.setAlignment(Pos.CENTER_LEFT);
        steps.setMinWidth(0);
        Label stepsNote = Ui.label("Photos et modèle 3D : lot 4 (écrans 05 à 09). L'enregistrement se fait dès l'étape 1.", "muted", "t-12");
        stepsNote.setWrapText(true);

        energy.setValue("DIESEL");
        energy.setMaxWidth(Double.MAX_VALUE);
        energy.setAccessibleText("Énergie");

        box.getChildren().addAll(title, steps, stepsNote,
                group("registration", "Immatriculation *", registration),
                row(group("make", "Marque *", make), group("model", "Modèle *", model)),
                row(group("year", "Année", year), group("energy", "Énergie", energy)),
                row(group("tank", "Capacité du réservoir", tank), group("category", "Catégorie", categoryBox)),
                group("vin", "Numéro de châssis (VIN)", vin),
                group("odometer", "Kilométrage actuel", odometer),
                group("org", "Entité d'affectation", org),
                globalError);

        save.getStyleClass().addAll("btn-primary", "btn-large");
        save.setText("Ajouter le véhicule");
        save.setDefaultButton(true);
        save.setMaxWidth(Double.MAX_VALUE);
        Motion.pressable(save);
        box.getChildren().add(save);
        globalError.setWrapText(true);

        save.setOnAction(e -> submit(gateway, onCreated));
        categoryBox.setMaxWidth(Double.MAX_VALUE);
        categoryBox.valueProperty().addListener((o, a, b) -> category.setText(b == null ? "" : b));
        org.setMaxWidth(Double.MAX_VALUE);
        org.setAccessibleText("Entité d'affectation");
        Node orgGroup = org.getParent();
        if (gateway.isDemo()) {
            if (orgGroup != null) {
                orgGroup.setVisible(false);
                orgGroup.setManaged(false);
            }
        } else {
            Ui.async(() -> gateway.orgUnits("vehicle:write"), units -> {
                org.getItems().setAll(units);
                if (!units.isEmpty()) org.getSelectionModel().select(0);
                if (units.size() <= 1 && orgGroup != null) {
                    orgGroup.setVisible(false);
                    orgGroup.setManaged(false);
                }
            }, err -> { });
        }
    }

    private void submit(FleetGateway gateway, Consumer<Vehicle> onCreated) {
        clearErrors();
        VehicleFormValidator.Result r = new VehicleFormValidator().validate(registration.getText(), vin.getText(),
                make.getText(), model.getText(), year.getText(), energy.getValue(), tank.getText(), category.getText(),
                Year.now(ZoneId.of("Africa/Douala")));
        if (!r.ok()) {
            r.errors().forEach(this::showFieldError);
            controls.get(r.errors().keySet().iterator().next()).requestFocus();
            return;
        }
        save.setDisable(true);
        save.setText("Ajout…");
        java.math.BigDecimal km;
        try {
            String raw = odometer.getText() == null ? "" : odometer.getText().strip().replace(" ", "").replace(',', '.');
            km = raw.isEmpty() ? null : new java.math.BigDecimal(raw);
            if (km != null && km.signum() < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            showFieldError("odometer", "Kilométrage : nombre positif attendu.");
            return;
        }
        java.util.UUID orgId = org.getValue() == null ? null : org.getValue().id();
        Ui.async(() -> gateway instanceof cm.camtel.nextfleet.desktop.data.HttpFleetGateway http
                ? http.createVehicle(r.draft(), orgId, km) : gateway.createVehicle(r.draft()), v -> {
            save.setDisable(false);
            save.setText("Ajouter le véhicule");
            onCreated.accept(v);
        }, err -> {
            save.setDisable(false);
            save.setText("Ajouter le véhicule");
            if (err.kind() == GatewayException.Kind.CONFLICT) {
                showFieldError("registration", err.getMessage());
                registration.requestFocus();
            } else {
                globalError.setText(err.getMessage());
            }
        });
    }

    /** Pour la recette visuelle : remplit le formulaire avec une saisie erronée puis valide. */
    void fillInvalidForTest() {
        registration.setText("CE-4401 lt");
        make.setText("Toyota");
        model.setText("");
        year.setText("1890");
        tank.setText("abc");
        save.fire();
    }

    /** Pour la recette visuelle : saisie valide puis enregistrement (toast de confirmation). */
    void fillValidForTest() {
        registration.setText("CE 2026 NF");
        make.setText("Toyota");
        model.setText("Hilux");
        year.setText("2026");
        tank.setText("80");
        categoryBox.setValue("Pick-up");
        save.fire();
    }

    private void showFieldError(String key, String message) {
        Label l = errorLabels.get(key);
        if (l != null) {
            l.setText(message);
            l.setManaged(true);
            l.setVisible(true);
        }
        Control c = controls.get(key);
        if (c != null) {
            c.getStyleClass().add("field-error");
            c.setAccessibleHelp(message);
        }
    }

    private void clearErrors() {
        globalError.setText("");
        errorLabels.values().forEach(l -> {
            l.setText("");
            l.setManaged(false);
            l.setVisible(false);
        });
        controls.values().forEach(c -> c.getStyleClass().remove("field-error"));
    }

    private Node group(String key, String label, Control control) {
        Label l = Ui.label(label, "field-label");
        l.setLabelFor(control);
        Label err = Ui.label("", "error-text");
        err.setWrapText(true);
        err.setManaged(false);
        err.setVisible(false);
        errorLabels.put(key, err);
        controls.put(key, control);
        control.setMaxWidth(Double.MAX_VALUE);
        if (control.getAccessibleText() == null) control.setAccessibleText(label.replace(" *", ""));
        VBox v = new VBox(4, l, control, err);
        HBox.setHgrow(v, Priority.ALWAYS);
        v.setMaxWidth(Double.MAX_VALUE);
        return v;
    }

    private static HBox row(Node a, Node b) {
        HBox h = new HBox(12, a, b);
        return h;
    }

    private static TextField field(String prompt) {
        TextField t = new TextField();
        t.setPromptText(prompt);
        t.setPrefColumnCount(8);
        return t;
    }

    private static HBox step(String n, String label, String state) {
        Label num = Ui.label(n, "step-number");
        Label l = Ui.label(label, "step-label");
        l.setMinWidth(0);
        num.setMinWidth(Region.USE_PREF_SIZE);
        HBox h = new HBox(6, num, l);
        h.getStyleClass().add("step");
        if (!state.isEmpty()) h.getStyleClass().add(state);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Region line() {
        Region r = new Region();
        r.getStyleClass().add("step-line");
        HBox.setHgrow(r, Priority.ALWAYS);
        r.setMinWidth(8);
        return r;
    }
}
