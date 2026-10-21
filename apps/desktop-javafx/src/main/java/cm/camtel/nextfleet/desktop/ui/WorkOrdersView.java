package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Prod;
import cm.camtel.nextfleet.desktop.data.Prod.WorkOrder;
import cm.camtel.nextfleet.desktop.data.Prod.WorkStatus;
import cm.camtel.nextfleet.domain.security.Permission;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Entretiens (gestionnaire) et Atelier (maintenance) : demandes et ordres d'intervention avec leur cycle complet,
 * clôture avec kilométrage, travaux, coût, justificatif et visa technique, vidanges. Les boutons proposés sont exactement
 * les transitions permises par le serveur ; le serveur les revérifie.
 */
final class WorkOrdersView extends ScrollPane {

    private final AppShell shell;
    private final AppShell.Section section;
    private final VBox col;
    private int tab;
    private List<WorkOrder> orders = List.of();
    private List<Prod.OilChange> oils = List.of();
    private List<Vehicle> vehicles = List.of();
    private List<Prod.Category> categories = List.of();

    WorkOrdersView(AppShell shell, AppShell.Section section) {
        this.shell = shell;
        this.section = section;
        col = Views.page(this);
        load();
    }

    private boolean workshop() {
        return shell.session().can(Permission.MAINTENANCE_WRITE);
    }

    private void load() {
        Ui.load("work:" + section, Ui.all(() -> shell.gateway().workOrders(false), () -> workshop() ? shell.gateway().oilChanges() : List.of(),
                () -> shell.gateway().vehicles(null, null, 0, 200).items(), () -> shell.gateway().maintenanceCategories()), r -> {
            @SuppressWarnings("unchecked") List<WorkOrder> o = (List<WorkOrder>) r[0];
            @SuppressWarnings("unchecked") List<Prod.OilChange> oc = (List<Prod.OilChange>) r[1];
            @SuppressWarnings("unchecked") List<Vehicle> v = (List<Vehicle>) r[2];
            @SuppressWarnings("unchecked") List<Prod.Category> c = (List<Prod.Category>) r[3];
            orders = o;
            oils = oc;
            vehicles = v;
            categories = c;
            render();
        }, err -> col.getChildren().setAll(header(), Ui.errorBanner(err, this::load)),
                () -> col.getChildren().setAll(header(), Ui.skeleton(-1, 96), Ui.skeleton(-1, 280)));
    }

    private Node header() {
        List<Node> actions = new ArrayList<>();
        if (workshop()) actions.add(Views.tinted("Saisir une vidange", Icons.GAUGE, this::oilSheet));
        actions.add(Views.filled("Nouvelle demande", Icons.PLUS, this::requestSheet));
        boolean shop = section == AppShell.Section.WORKSHOP;
        return Views.header(shop ? "Atelier" : "Entretiens",
                shop ? "Demandes à qualifier, interventions en cours, clôtures visées et vidanges."
                        : "Demandes d'intervention, immobilisations et coûts d'atelier de votre parc.",
                actions.toArray(Node[]::new));
    }

    private void render() {
        YearMonth month = YearMonth.now(Views.DOUALA);
        long toQualify = orders.stream().filter(o -> o.status() == WorkStatus.DEMANDE).count();
        long inProgress = orders.stream().filter(o -> o.status() == WorkStatus.EN_COURS).count();
        long planned = orders.stream().filter(o -> o.status() == WorkStatus.QUALIFIEE || o.status() == WorkStatus.PLANIFIEE).count();
        BigDecimal monthCost = orders.stream().filter(o -> o.status() == WorkStatus.CLOTUREE && o.costXaf() != null
                        && YearMonth.from((o.immobilisedTo() != null ? o.immobilisedTo() : o.createdAt()).atZone(Views.DOUALA)).equals(month))
                .map(WorkOrder::costXaf).reduce(BigDecimal.ZERO, BigDecimal::add);
        Node tiles = Views.tiles(Views.tile(String.valueOf(toQualify), "Demandes à qualifier", "summary-soon"),
                Views.tile(String.valueOf(inProgress), "Véhicules immobilisés", "summary-expire"),
                Views.tile(String.valueOf(planned), "Qualifiées ou planifiées", "summary-ok"),
                Views.tile(Ui.compactXaf(monthCost), "Coût clôturé ce mois", "summary-ok"));

        List<String> tabs = workshop() ? List.of("En cours", "Historique", "Vidanges") : List.of("En cours", "Historique");
        Controls.Segmented seg = new Controls.Segmented(tabs, Math.min(tab, tabs.size() - 1), i -> {
            tab = i;
            render();
        });
        List<Node> nodes = new ArrayList<>(List.of(header(), tiles, seg));
        if (tab == 2) {
            nodes.add(oilList());
        } else {
            boolean open = tab == 0;
            List<WorkOrder> shown = orders.stream().filter(o -> o.status().open() == open).toList();
            if (shown.isEmpty()) {
                nodes.add(Ui.statePanel(Icons.WRENCH, open ? "Aucune intervention en cours" : "Aucune intervention clôturée",
                        open ? "Les demandes des conducteurs et des gestionnaires apparaissent ici dès leur création."
                                : "Les interventions clôturées ou annulées apparaîtront ici."));
            } else {
                List<Node> rows = new ArrayList<>();
                for (WorkOrder o : shown) rows.add(orderRow(o));
                nodes.add(Ui.insetGroup(open ? "Interventions ouvertes · " + shown.size() : "Historique · " + shown.size(), rows,
                        open ? "Une intervention en cours rend le véhicule indisponible ; la clôture le remet en service." : null));
            }
        }
        col.getChildren().setAll(nodes);
        Motion.stagger(col.getChildren());
    }

    private Node orderRow(WorkOrder o) {
        String tileCls = switch (o.status()) {
            case EN_COURS -> "tile-red";
            case DEMANDE -> "tile-orange";
            case CLOTUREE -> "tile-green";
            case ANNULEE -> "tile-gray";
            default -> "tile-blue";
        };
        String sub = o.categoryLabel() + " · demandée par " + o.requestedBy() + " · " + Views.dayTime(o.createdAt())
                + (o.costXaf() != null ? " · " + Views.xaf(o.costXaf()) : "");
        return Views.row(Ui.symbolTile(Icons.WRENCH, tileCls), o.registration() + " · " + o.title(), sub,
                Views.pill(o.status().label, o.status().tone), () -> detail(o));
    }

    private void detail(WorkOrder o) {
        List<String[]> facts = new ArrayList<>(List.of(
                new String[]{"Véhicule", o.registration() + " · " + o.vehicleLabel()},
                new String[]{"Catégorie", o.categoryLabel()},
                new String[]{"Statut", o.status().label},
                new String[]{"Demandée par", o.requestedBy() + " · " + Views.dayTime(o.createdAt())},
                new String[]{"Garage", o.garageLabel()},
                new String[]{"Immobilisé depuis", o.immobilisedFrom() == null ? null : Views.dayTime(o.immobilisedFrom())},
                new String[]{"Remis en service", o.immobilisedTo() == null ? null : Views.dayTime(o.immobilisedTo())},
                new String[]{"Kilométrage", o.odometerKm() == null ? null : Views.km(o.odometerKm())},
                new String[]{"Travaux réalisés", o.workDone()},
                new String[]{"Coût", o.costXaf() == null ? null : Views.xaf(o.costXaf())},
                new String[]{"Visa technique", o.technicalVisaBy()}));
        List<Button> actions = new ArrayList<>();
        if (o.proofFileId() != null) {
            actions.add(Views.sheetAction("Ouvrir le justificatif", "btn-tinted", () -> Files.open(shell, o.proofFileId(), "justificatif")));
        }
        if (workshop()) {
            for (WorkStatus next : o.status().next()) {
                String text = switch (next) {
                    case QUALIFIEE -> "Qualifier la demande";
                    case PLANIFIEE -> "Planifier";
                    case EN_COURS -> "Démarrer l'intervention";
                    case CLOTUREE -> "Clôturer avec visa technique";
                    case ANNULEE -> "Annuler l'intervention";
                    default -> next.label;
                };
                String style = next == WorkStatus.ANNULEE ? "btn-destructive" : next == WorkStatus.CLOTUREE ? "btn-primary" : "btn-tinted";
                actions.add(Views.sheetAction(text, style, () -> transitionSheet(o, next)));
            }
        }
        shell.openSheet(Views.detailSheet(shell, o.registration() + " · " + o.title(), null, facts, actions), 440);
    }

    private void transitionSheet(WorkOrder o, WorkStatus next) {
        TextField garage = FormSheet.text("Ex. Atelier central CAMTEL");
        if (o.garageLabel() != null) garage.setText(o.garageLabel());
        TextField km = FormSheet.text("Ex. 12500");
        if (o.odometerKm() != null) km.setText(o.odometerKm().toPlainString());
        TextField cost = FormSheet.text("Montant en FCFA");
        TextArea work = FormSheet.area("Pièces remplacées, réglages, observations");
        TextArea reason = FormSheet.area("Motif (obligatoire pour une annulation)");
        FormSheet.FilePick proof = new FormSheet.FilePick("Joindre la facture ou le bon de travaux");
        String title = switch (next) {
            case CLOTUREE -> "Clôturer l'intervention";
            case ANNULEE -> "Annuler l'intervention";
            case EN_COURS -> "Démarrer l'intervention";
            case PLANIFIEE -> "Planifier l'intervention";
            default -> "Qualifier la demande";
        };
        FormSheet f = new FormSheet(title, next == WorkStatus.ANNULEE ? "Confirmer" : "Valider", shell::closeSheet)
                .intro(o.registration() + " · " + o.title());
        switch (next) {
            case CLOTUREE -> f.intro("Kilométrage, travaux, coût et justificatif sont exigés ; votre visa technique est enregistré.")
                    .field("Kilométrage au compteur *", km).field("Coût total (FCFA) *", cost).field("Travaux réalisés *", work)
                    .field("Justificatif *", proof);
            case ANNULEE -> f.field("Motif *", reason);
            case EN_COURS -> f.intro("Le véhicule devient indisponible jusqu'à la clôture.").field("Garage", garage).field("Kilométrage à l'entrée", km);
            default -> f.field("Garage", garage);
        }
        f.onSubmit(() -> {
            java.util.UUID proofId = null;
            if (next == WorkStatus.CLOTUREE && proof.path() != null) {
                proofId = shell.gateway().upload("MAINTENANCE", o.vehicleId(), proof.path()).id();
            }
            return shell.gateway().transitionWork(o.id(), o.version(), new Prod.WorkTransition(next, FormSheet.opt(garage),
                    FormSheet.dec(km, "Kilométrage", next == WorkStatus.CLOTUREE), FormSheet.dec(cost, "Coût", next == WorkStatus.CLOTUREE),
                    work.getText(), proofId, reason.getText()));
        }, (WorkOrder done) -> {
            shell.closeSheet();
            shell.toasts().success(title, done.registration() + " : " + done.status().label.toLowerCase(java.util.Locale.FRANCE) + ".");
            load();
        });
        shell.openSheet(f, 460);
    }

    private void requestSheet() {
        if (vehicles.isEmpty()) {
            shell.toasts().info("Aucun véhicule", "Ajoutez d'abord un véhicule au parc.");
            return;
        }
        ComboBox<Vehicle> vehicle = FormSheet.combo(vehicles);
        vehicle.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Vehicle v) {
                return v == null ? "" : v.registration() + " · " + v.label();
            }

            @Override
            public Vehicle fromString(String s) {
                return null;
            }
        });
        ComboBox<Prod.Category> cat = FormSheet.combo(categories);
        TextField title = FormSheet.text("Ex. Bruit au freinage, voyant moteur allumé");
        FormSheet f = new FormSheet("Nouvelle demande", "Envoyer", shell::closeSheet)
                .intro("L'atelier est prévenu et qualifie la demande. Décrivez le symptôme constaté.")
                .field("Véhicule *", vehicle).field("Catégorie *", cat).field("Description *", title);
        f.onSubmit(() -> shell.gateway().requestWork(FormSheet.pick(vehicle, "Véhicule").id(), FormSheet.pick(cat, "Catégorie").code(),
                FormSheet.req(title, "Description")), (WorkOrder o) -> {
            shell.closeSheet();
            shell.toasts().success("Demande envoyée", o.registration() + " · " + o.title());
            tab = 0;
            load();
        });
        shell.openSheet(f, 460);
    }

    private void oilSheet() {
        if (vehicles.isEmpty()) return;
        ComboBox<Vehicle> vehicle = FormSheet.combo(vehicles);
        vehicle.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Vehicle v) {
                return v == null ? "" : v.registration() + " · " + v.label();
            }

            @Override
            public Vehicle fromString(String s) {
                return null;
            }
        });
        var date = FormSheet.date(Views.today());
        TextField km = FormSheet.text("Ex. 12600");
        TextField oil = FormSheet.text("Ex. 15W40");
        TextField liters = FormSheet.text("Ex. 7");
        TextField everyKm = FormSheet.text("Ex. 5000");
        TextField everyMonths = FormSheet.text("Ex. 6");
        TextField cost = FormSheet.text("FCFA (facultatif)");
        TextField garage = FormSheet.text("Ex. Atelier central");
        var oilFilter = FormSheet.check("Filtre à huile", true);
        var airFilter = FormSheet.check("Filtre à air", false);
        var fuelFilter = FormSheet.check("Filtre à carburant", false);
        FormSheet f = new FormSheet("Vidange", "Enregistrer", shell::closeSheet)
                .intro("La prochaine échéance (kilométrage et date) est calculée et apparaît dans les échéances.")
                .field("Véhicule *", vehicle).row("Date *", date, "Kilométrage *", km).row("Huile", oil, "Quantité (L)", liters)
                .node(new javafx.scene.layout.HBox(16, oilFilter, airFilter, fuelFilter))
                .row("Intervalle (km)", everyKm, "Intervalle (mois)", everyMonths).row("Coût (FCFA)", cost, "Garage", garage);
        f.onSubmit(() -> shell.gateway().addOilChange(new Prod.OilChangeDraft(FormSheet.pick(vehicle, "Véhicule").id(),
                FormSheet.day(date, "Date", true), FormSheet.dec(km, "Kilométrage", true), FormSheet.opt(oil),
                FormSheet.dec(liters, "Quantité", false), oilFilter.isSelected(), airFilter.isSelected(), fuelFilter.isSelected(),
                FormSheet.integer(everyKm, "Intervalle"), FormSheet.integer(everyMonths, "Intervalle"), FormSheet.dec(cost, "Coût", false),
                FormSheet.opt(garage))), (Prod.OilChange o) -> {
            shell.closeSheet();
            shell.toasts().success("Vidange enregistrée", o.registration() + (o.nextDueKm() != null ? " · prochaine à " + Views.km(o.nextDueKm()) : ""));
            tab = 2;
            load();
        });
        shell.openSheet(f, 480);
    }

    private Node oilList() {
        if (oils.isEmpty()) {
            return Ui.statePanel(Icons.GAUGE, "Aucune vidange enregistrée", "Saisissez la dernière vidange de chaque véhicule pour suivre la prochaine échéance.");
        }
        List<Node> rows = new ArrayList<>();
        for (Prod.OilChange o : oils) {
            String next = o.nextDueKm() != null ? "prochaine à " + Views.km(o.nextDueKm()) : "";
            if (o.nextDueOn() != null) next += (next.isEmpty() ? "prochaine le " : " ou le ") + Views.day(o.nextDueOn());
            String sub = Views.day(o.doneOn()) + " · " + Views.km(o.odometerKm()) + (o.oilLabel() != null ? " · " + o.oilLabel() : "")
                    + (o.costXaf() != null ? " · " + Views.xaf(o.costXaf()) : "");
            rows.add(Views.row(Ui.symbolTile(Icons.GAUGE, "tile-mint"), o.registration() + (next.isEmpty() ? "" : " · " + next), sub, null, null));
        }
        return Ui.insetGroup("Vidanges · " + oils.size(), rows, "Le coût d'une vidange rattachée à une intervention est compté une seule fois.");
    }

    static Instant now() {
        return Instant.now();
    }
}
