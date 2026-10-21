package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Prod;
import cm.camtel.nextfleet.desktop.data.Prod.FuelFill;
import cm.camtel.nextfleet.desktop.data.Prod.FuelOrder;
import cm.camtel.nextfleet.desktop.data.Prod.FuelOrderStatus;
import cm.camtel.nextfleet.domain.security.Permission;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Carburant (NF-40 à NF-42) : bons demandés puis validés par une autre personne, pleins rattachés à un bon (ou exception
 * motivée) avec reçu, solde du bon consommé côté serveur sans jamais être dépassé, saisie idempotente.
 */
final class FuelView extends ScrollPane {

    private final AppShell shell;
    private final VBox col;
    private int tab;
    private List<FuelOrder> orders = List.of();
    private List<FuelFill> fills = List.of();
    private List<Vehicle> vehicles = List.of();
    private List<Prod.Supplier> suppliers = List.of();

    FuelView(AppShell shell) {
        this.shell = shell;
        col = Views.page(this);
        load();
    }

    private boolean can(Permission p) {
        return shell.session().can(p);
    }

    private void load() {
        col.getChildren().setAll(header(), Ui.skeleton(-1, 96), Ui.skeleton(-1, 280));
        Ui.async(() -> new Object[]{shell.gateway().fuelOrders(), shell.gateway().fuelFills(),
                shell.gateway().vehicles(null, null, 0, 200).items(), shell.gateway().suppliers()}, r -> {
            @SuppressWarnings("unchecked") List<FuelOrder> o = (List<FuelOrder>) r[0];
            @SuppressWarnings("unchecked") List<FuelFill> f = (List<FuelFill>) r[1];
            @SuppressWarnings("unchecked") List<Vehicle> v = (List<Vehicle>) r[2];
            @SuppressWarnings("unchecked") List<Prod.Supplier> s = (List<Prod.Supplier>) r[3];
            orders = o;
            fills = f;
            vehicles = v;
            suppliers = s;
            render();
        }, err -> col.getChildren().set(1, Ui.errorBanner(err, this::load)));
    }

    private Node header() {
        List<Node> actions = new ArrayList<>();
        if (can(Permission.VEHICLE_WRITE)) actions.add(Views.tinted("Ajouter une station", Icons.PLUS, this::supplierSheet));
        if (can(Permission.FUEL_FILL_CREATE)) actions.add(Views.tinted("Saisir un plein", Icons.FUEL, this::fillSheet));
        if (can(Permission.FUEL_ORDER_CREATE)) actions.add(Views.filled("Demander un bon", Icons.PLUS, this::orderSheet));
        return Views.header("Carburant", "Bons de carburant, validations et pleins avec reçu. Le solde d'un bon ne peut jamais être dépassé.",
                actions.toArray(Node[]::new));
    }

    private void render() {
        YearMonth month = YearMonth.now(Views.DOUALA);
        List<FuelFill> monthFills = fills.stream().filter(f -> YearMonth.from(f.filledAt().atZone(Views.DOUALA)).equals(month)).toList();
        BigDecimal liters = monthFills.stream().map(FuelFill::liters).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = monthFills.stream().map(FuelFill::amountXaf).reduce(BigDecimal.ZERO, BigDecimal::add);
        long toApprove = orders.stream().filter(o -> o.status() == FuelOrderStatus.SUBMITTED && !o.mine()).count();
        Node tiles = Views.tiles(
                Views.tile(String.valueOf(toApprove), can(Permission.FUEL_ORDER_APPROVE) ? "Bons à valider" : "Bons en attente", "summary-soon"),
                Views.tile(Views.liters(liters), "Litres ce mois", "summary-ok"),
                Views.tile(Ui.compactXaf(amount), "Dépense ce mois", "summary-ok"),
                Views.tile(liters.signum() > 0 ? Views.num(amount.divide(liters, 0, RoundingMode.HALF_UP)) + " F/L" : "—", "Prix moyen", "summary-ok"));
        Controls.Segmented seg = new Controls.Segmented(List.of("Bons · " + orders.size(), "Pleins · " + fills.size()), tab, i -> {
            tab = i;
            render();
        });
        List<Node> nodes = new ArrayList<>(List.of(header(), tiles, seg));
        if (tab == 0) {
            if (orders.isEmpty()) {
                nodes.add(Ui.statePanel(Icons.FUEL, "Aucun bon de carburant", "Un bon précise le véhicule, la station, les litres et le montant autorisés."));
            } else {
                List<Node> rows = new ArrayList<>();
                for (FuelOrder o : orders) {
                    String sub = o.supplierName() + " · " + Views.liters(o.authorizedLiters()) + " autorisés · " + Views.liters(o.deliveredLiters())
                            + " délivrés · jusqu'au " + Views.day(o.validUntil()) + " · " + (o.mine() ? "votre demande" : o.requestedBy());
                    rows.add(Views.row(Ui.symbolTile(Icons.FUEL, o.status() == FuelOrderStatus.SUBMITTED ? "tile-orange" : "tile-blue"),
                            o.orderNumber() + " · " + o.registration(), sub, Views.pill(o.status().label, o.status().tone), () -> orderDetail(o)));
                }
                nodes.add(Ui.insetGroup(null, rows, "Le demandeur ne peut pas valider son propre bon (contrôle indépendant)."));
            }
        } else {
            if (fills.isEmpty()) {
                nodes.add(Ui.statePanel(Icons.FUEL, "Aucun plein enregistré", "Chaque plein est rattaché à un bon validé, ou motivé en exception."));
            } else {
                List<Node> rows = new ArrayList<>();
                for (FuelFill f : fills) {
                    String sub = Views.dayTime(f.filledAt()) + " · " + f.supplierName() + " · " + (f.orderNumber() != null ? f.orderNumber() : "exception")
                            + (f.odometerKm() != null ? " · " + Views.km(f.odometerKm()) : "") + (f.fullTank() ? " · plein complet" : "");
                    rows.add(Views.row(Ui.symbolTile(Icons.FUEL, f.exceptionReason() != null ? "tile-orange" : "tile-green"),
                            f.registration() + " · " + Views.liters(f.liters()) + " · " + Views.xaf(f.amountXaf()), sub, null, () -> fillDetail(f)));
                }
                nodes.add(Ui.insetGroup(null, rows, "Les consommations sont calculées de plein complet à plein complet, compteur relevé."));
            }
        }
        col.getChildren().setAll(nodes);
        Motion.stagger(col.getChildren());
    }

    private void orderDetail(FuelOrder o) {
        List<String[]> facts = List.of(new String[]{"Véhicule", o.registration()}, new String[]{"Station", o.supplierName()},
                new String[]{"Statut", o.status().label}, new String[]{"Litres autorisés", Views.liters(o.authorizedLiters())},
                new String[]{"Montant autorisé", Views.xaf(o.authorizedAmountXaf())}, new String[]{"Délivré", Views.liters(o.deliveredLiters())
                        + " · " + Views.xaf(o.deliveredAmountXaf())},
                new String[]{"Valable jusqu'au", Views.day(o.validUntil())}, new String[]{"Demandé par", o.requestedBy() + " · " + Views.dayTime(o.createdAt())},
                new String[]{"Validé par", o.approvedBy()}, new String[]{"Motif", o.reason()});
        List<Button> actions = new ArrayList<>();
        if (o.status() == FuelOrderStatus.SUBMITTED && !o.mine() && can(Permission.FUEL_ORDER_APPROVE)) {
            actions.add(Views.sheetAction("Valider le bon", "btn-primary", () -> Ui.async(() -> {
                shell.gateway().approveFuelOrder(o.id(), null);
                return true;
            }, ok -> {
                shell.closeSheet();
                shell.toasts().success("Bon validé", o.orderNumber());
                load();
            }, err -> shell.toasts().info("Validation refusée", err.getMessage()))));
        }
        boolean cancellable = (o.status() == FuelOrderStatus.DRAFT || o.status() == FuelOrderStatus.SUBMITTED || o.status() == FuelOrderStatus.APPROVED)
                && o.deliveredLiters().signum() == 0 && (o.mine() || can(Permission.FUEL_ORDER_APPROVE));
        if (cancellable) actions.add(Views.sheetAction("Annuler le bon", "btn-destructive", () -> cancelSheet(o)));
        shell.openSheet(Views.detailSheet(shell, o.orderNumber(), null, facts, actions), 440);
    }

    private void cancelSheet(FuelOrder o) {
        TextArea reason = FormSheet.area("Ex. Mission reportée");
        FormSheet f = new FormSheet("Annuler le bon", "Confirmer", shell::closeSheet).intro(o.orderNumber() + " · " + o.registration())
                .field("Motif *", reason);
        f.onSubmit(() -> {
            if (reason.getText().strip().length() < 5) throw new IllegalArgumentException("Motif : 5 caractères au moins.");
            shell.gateway().cancelFuelOrder(o.id(), reason.getText().strip());
            return true;
        }, ok -> {
            shell.closeSheet();
            shell.toasts().success("Bon annulé", o.orderNumber());
            load();
        });
        shell.openSheet(f, 440);
    }

    private void fillDetail(FuelFill f) {
        List<String[]> facts = List.of(new String[]{"Véhicule", f.registration()}, new String[]{"Date", Views.dayTime(f.filledAt())},
                new String[]{"Station", f.supplierName()}, new String[]{"Bon", f.orderNumber()}, new String[]{"Exception", f.exceptionReason()},
                new String[]{"Conducteur", f.driverName()}, new String[]{"Litres", Views.liters(f.liters())},
                new String[]{"Prix unitaire", Views.xaf(f.unitPriceXaf())}, new String[]{"Montant", Views.xaf(f.amountXaf())},
                new String[]{"Compteur", f.odometerKm() == null ? null : Views.km(f.odometerKm())}, new String[]{"Plein complet", f.fullTank() ? "Oui" : "Non"},
                new String[]{"Reçu", f.receiptReference()}, new String[]{"Saisi par", f.enteredBy()});
        List<Button> actions = new ArrayList<>();
        if (f.receiptFileId() != null) actions.add(Views.sheetAction("Ouvrir le reçu", "btn-tinted", () -> Files.open(shell, f.receiptFileId(), "recu")));
        shell.openSheet(Views.detailSheet(shell, f.registration() + " · " + Views.liters(f.liters()), null, facts, actions), 440);
    }

    private ComboBox<Vehicle> vehicleCombo() {
        ComboBox<Vehicle> c = FormSheet.combo(vehicles);
        c.setConverter(new StringConverter<>() {
            @Override
            public String toString(Vehicle v) {
                return v == null ? "" : v.registration() + " · " + v.label();
            }

            @Override
            public Vehicle fromString(String s) {
                return null;
            }
        });
        return c;
    }

    private boolean ready() {
        if (vehicles.isEmpty()) {
            shell.toasts().info("Aucun véhicule", "Le parc de votre périmètre est vide.");
            return false;
        }
        if (suppliers.isEmpty()) {
            shell.toasts().info("Aucune station", can(Permission.VEHICLE_WRITE) ? "Ajoutez d'abord une station-service."
                    : "Demandez au gestionnaire du parc d'enregistrer les stations-service.");
            return false;
        }
        return true;
    }

    private void orderSheet() {
        if (!ready()) return;
        ComboBox<Vehicle> vehicle = vehicleCombo();
        ComboBox<Prod.Supplier> station = FormSheet.combo(suppliers.stream().filter(s -> !"GARAGE".equals(s.kind())).toList());
        TextField liters = FormSheet.text("Ex. 60");
        TextField amount = FormSheet.text("Ex. 50 400");
        var until = FormSheet.date(Views.today().plusDays(7));
        TextArea reason = FormSheet.area("Ex. Mission Yaoundé → Obala");
        FormSheet f = new FormSheet("Demander un bon", "Envoyer", shell::closeSheet)
                .intro("Le bon est soumis à validation : une autre personne habilitée le valide avant tout plein.")
                .field("Véhicule *", vehicle).field("Station *", station).row("Litres autorisés *", liters, "Montant autorisé (FCFA) *", amount)
                .field("Valable jusqu'au *", until).field("Motif", reason);
        f.onSubmit(() -> shell.gateway().createFuelOrder(new Prod.FuelOrderDraft(FormSheet.pick(vehicle, "Véhicule").id(),
                FormSheet.pick(station, "Station").id(), FormSheet.dec(liters, "Litres", true), FormSheet.dec(amount, "Montant", true),
                FormSheet.day(until, "Validité", true), reason.getText()), true), (FuelOrder o) -> {
            shell.closeSheet();
            shell.toasts().success("Bon envoyé pour validation", o.orderNumber());
            tab = 0;
            load();
        });
        shell.openSheet(f, 480);
    }

    private void fillSheet() {
        if (!ready()) return;
        ComboBox<Vehicle> vehicle = vehicleCombo();
        ComboBox<Object> order = new ComboBox<>();
        order.setMaxWidth(Double.MAX_VALUE);
        Runnable refreshOrders = () -> {
            Vehicle v = vehicle.getValue();
            List<Object> items = new ArrayList<>();
            orders.stream().filter(o -> v != null && o.vehicleId().equals(v.id())
                    && (o.status() == FuelOrderStatus.APPROVED || o.status() == FuelOrderStatus.PARTIALLY_CONSUMED)).forEach(items::add);
            items.add("Sans bon (exception motivée)");
            order.getItems().setAll(items);
            order.getSelectionModel().select(0);
        };
        vehicle.valueProperty().addListener((o, a, b) -> refreshOrders.run());
        refreshOrders.run();
        ComboBox<Prod.Supplier> station = FormSheet.combo(suppliers.stream().filter(s -> !"GARAGE".equals(s.kind())).toList());
        var date = FormSheet.date(Views.today());
        TextField time = FormSheet.text("HH:MM");
        time.setText(LocalTime.now(Views.DOUALA).withSecond(0).withNano(0).toString());
        TextField km = FormSheet.text("Compteur (km)");
        TextField liters = FormSheet.text("Ex. 45,5");
        TextField price = FormSheet.text("Ex. 840");
        var full = FormSheet.check("Plein complet (réservoir rempli)", true);
        TextField receipt = FormSheet.text("Numéro du ticket");
        FormSheet.FilePick file = new FormSheet.FilePick("Photo ou PDF du reçu");
        TextArea exception = FormSheet.area("Obligatoire sans bon : pourquoi ce plein a-t-il été fait sans bon ?");
        String key = "desk-" + UUID.randomUUID();   // même clé si l'envoi est répété : aucun doublon
        FormSheet f = new FormSheet("Saisir un plein", "Enregistrer", shell::closeSheet)
                .intro("Le compteur et le plein complet permettent de calculer la consommation réelle.")
                .field("Véhicule *", vehicle).field("Bon de carburant *", order).field("Station *", station)
                .row("Date *", date, "Heure *", time).row("Litres *", liters, "Prix au litre (FCFA) *", price)
                .row("Compteur", km, "Numéro du reçu *", receipt).node(full).field("Reçu (photo ou PDF)", file)
                .field("Motif de l'exception", exception);
        f.onSubmit(() -> {
            Vehicle v = FormSheet.pick(vehicle, "Véhicule");
            Object o = order.getValue();
            UUID orderId = o instanceof FuelOrder fo ? fo.id() : null;
            if (orderId == null && exception.getText().strip().length() < 5) {
                throw new IllegalArgumentException("Sans bon, le motif de l'exception est obligatoire (5 caractères au moins).");
            }
            LocalTime t;
            try {
                t = LocalTime.parse(time.getText().strip().length() == 4 ? "0" + time.getText().strip() : time.getText().strip());
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("Heure : format HH:MM attendu.");
            }
            Instant at = FormSheet.day(date, "Date", true).atTime(t).atZone(Views.DOUALA).toInstant();
            if (at.isAfter(Instant.now().plusSeconds(120))) throw new IllegalArgumentException("Un plein ne peut pas être daté dans le futur.");
            UUID receiptId = file.path() == null ? null : shell.gateway().upload("FUEL_RECEIPT", v.id(), file.path()).id();
            shell.gateway().recordFill(key, new Prod.FillDraft(orderId, orderId == null ? exception.getText().strip() : null, v.id(),
                    FormSheet.pick(station, "Station").id(), at, FormSheet.dec(km, "Compteur", false), FormSheet.dec(liters, "Litres", true),
                    FormSheet.dec(price, "Prix au litre", true), full.isSelected(), FormSheet.req(receipt, "Numéro du reçu"), receiptId));
            return v.registration();
        }, (String reg) -> {
            shell.closeSheet();
            shell.toasts().success("Plein enregistré", reg);
            tab = 1;
            load();
        });
        shell.openSheet(f, 500);
    }

    private void supplierSheet() {
        TextField name = FormSheet.text("Ex. Station Nlongkak");
        TextField code = FormSheet.text("Ex. STATION-NLONGKAK");
        name.textProperty().addListener((o, a, b) -> {
            String c = java.text.Normalizer.normalize(b == null ? "" : b, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                    .toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("(^-|-$)", "");
            code.setText(c.length() > 30 ? c.substring(0, 30) : c);
        });
        ComboBox<String> kind = FormSheet.combo(List.of("STATION", "GARAGE", "AUTRE"));
        FormSheet f = new FormSheet("Nouvelle station", "Ajouter", shell::closeSheet)
                .field("Nom *", name).field("Code *", code).field("Type *", kind);
        f.onSubmit(() -> shell.gateway().createSupplier(FormSheet.req(code, "Code"), FormSheet.req(name, "Nom"), FormSheet.pick(kind, "Type")),
                (Prod.Supplier s) -> {
                    shell.closeSheet();
                    shell.toasts().success("Station ajoutée", s.name());
                    load();
                });
        shell.openSheet(f, 420);
    }
}
