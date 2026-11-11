package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.Driver;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;
import cm.camtel.nextfleet.desktop.data.Prod;
import cm.camtel.nextfleet.domain.security.Permission;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Corriger ou supprimer ce qui a été saisi : véhicule, vidange, document, conducteur, intervention.
 * Toujours avec un motif (5 caractères au moins), enregistré avec l'ancienne et la nouvelle valeur dans le journal
 * d'audit. Ce que la base protège (véhicule, plein de carburant, journal) n'est jamais effacé : le véhicule est archivé
 * et peut être restauré.
 */
final class Corrections {

    private Corrections() { }

    private static final List<String> ENERGIES = List.of("DIESEL", "ESSENCE", "HYBRIDE", "ELECTRIQUE", "GPL", "AUTRE");
    private static final List<String> CATEGORIES = List.of("Pick-up", "SUV", "4x4", "Berline", "Minibus", "Fourgon", "Camion", "Moto", "Autre");

    static String reason(TextField f) {
        String r = FormSheet.req(f, "Motif");
        if (r.length() < 5) throw new IllegalArgumentException("Motif : 5 caractères au moins.");
        return r;
    }

    /** Feuille de confirmation d'une suppression ou d'un archivage : motif obligatoire, bouton rouge. */
    static void confirm(AppShell shell, String title, String action, String explanation, String placeholder,
                        java.util.function.Function<String, Object> work, String doneTitle, Runnable after) {
        TextField why = FormSheet.text(placeholder);
        FormSheet f = new FormSheet(title, action, shell::closeSheet).destructive().intro(explanation).field("Motif *", why);
        f.onSubmit(() -> work.apply(reason(why)), o -> {
            shell.closeSheet();
            shell.toasts().success(doneTitle, "Le motif est conservé dans le journal d'audit.");
            after.run();
        });
        shell.openSheet(f, 440);
    }

    // ------------------------------------------------------------------ véhicule

    /** Modifier toutes les informations d'un véhicule ; archiver (le « supprimer » du parc) en bas de la feuille. */
    static void vehicle(AppShell shell, Vehicle v, Consumer<Vehicle> after) {
        TextField reg = FormSheet.text("Ex. LT 1234 CE");
        reg.setText(v.registration());
        TextField make = FormSheet.text("Marque");
        make.setText(v.make());
        TextField model = FormSheet.text("Modèle");
        model.setText(v.model());
        TextField year = FormSheet.text("Ex. 2021");
        year.setText(v.modelYear() == null ? "" : String.valueOf(v.modelYear()));
        ComboBox<String> energy = FormSheet.combo(ENERGIES);
        energy.setValue(v.energy() != null && ENERGIES.contains(v.energy()) ? v.energy() : null);
        ComboBox<String> category = FormSheet.combo(CATEGORIES);
        category.setValue(v.category() != null && CATEGORIES.contains(v.category()) ? v.category() : null);
        TextField tank = FormSheet.text("Litres");
        tank.setText(v.tankCapacityLiters() == null ? "" : v.tankCapacityLiters().stripTrailingZeros().toPlainString());
        ComboBox<String> dir = new ComboBox<>();
        dir.setEditable(true);
        dir.setMaxWidth(Double.MAX_VALUE);
        dir.getEditor().setText(v.direction() == null ? "" : v.direction());
        Ui.async(() -> shell.gateway().directions(), l -> {
            String keep = dir.getEditor().getText();
            dir.getItems().setAll(l);
            dir.getEditor().setText(keep);
        }, err -> { });
        TextField site = FormSheet.text("Ex. CTT Yaoundé");
        site.setText(v.siteLabel() == null ? "" : v.siteLabel());
        List<VehicleStatus> statuses = v.status() == VehicleStatus.ON_MISSION
                ? List.of(VehicleStatus.ON_MISSION)
                : List.of(VehicleStatus.AVAILABLE, VehicleStatus.IN_SERVICE, VehicleStatus.MAINTENANCE, VehicleStatus.OUT_OF_SERVICE);
        ComboBox<VehicleStatus> status = FormSheet.combo(statuses);
        status.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(VehicleStatus s) {
                return s == null ? "" : s.label;
            }

            @Override
            public VehicleStatus fromString(String s) {
                return null;
            }
        });
        status.setValue(statuses.contains(v.status()) ? v.status() : null);
        status.setDisable(v.status() == VehicleStatus.ON_MISSION);
        TextField why = FormSheet.text("Ex. Plaque mal recopiée lors de la reprise");
        FormSheet f = new FormSheet("Modifier le véhicule", "Enregistrer", shell::closeSheet)
                .intro("Les modifications sont enregistrées avec leur motif ; l'ancienne valeur reste consultable dans le journal d'audit.")
                .row("Immatriculation *", reg, "Année", year).row("Marque *", make, "Modèle *", model)
                .row("Énergie", energy, "Catégorie", category).row("Réservoir (L)", tank, "Statut", status)
                .section("Affectation").row("Direction", dir, "Site", site)
                .field("Motif *", why);
        if (shell.session().can(Permission.VEHICLE_ARCHIVE)) {
            f.dangerAction("Archiver ce véhicule…", () -> archive(shell, v, after));
        }
        f.onSubmit(() -> {
            Integer y = FormSheet.integer(year, "Année");
            String d = dir.getEditor().getText() == null ? null : dir.getEditor().getText().strip().toUpperCase(Locale.ROOT);
            return shell.gateway().updateVehicle(v, new Prod.VehicleUpdate(FormSheet.req(reg, "Immatriculation"), null,
                    FormSheet.req(make, "Marque"), FormSheet.req(model, "Modèle"), y, energy.getValue(), category.getValue(),
                    FormSheet.dec(tank, "Réservoir", false), d, FormSheet.opt(site), status.getValue(), reason(why)));
        }, (Vehicle saved) -> {
            shell.closeSheet();
            shell.toasts().success("Véhicule modifié", saved.registration() + " · " + saved.label());
            after.accept(saved);
        });
        shell.openSheet(f, 520);
    }

    static void archive(AppShell shell, Vehicle v, Consumer<Vehicle> after) {
        Vehicle[] result = new Vehicle[1];
        confirm(shell, "Archiver " + v.registration(), "Archiver",
                "Le véhicule disparaît du parc actif, des listes et des rapports. Son historique (pleins, vidanges, missions) est conservé "
                        + "et il peut être restauré à tout moment. Un véhicule n'est jamais effacé définitivement, pour garder la traçabilité.",
                "Ex. Véhicule réformé, vendu ou saisi en double",
                r -> result[0] = shell.gateway().archiveVehicle(v.id(), v.version(), r), "Véhicule archivé", () -> after.accept(result[0]));
    }

    static void restore(AppShell shell, Vehicle v, Consumer<Vehicle> after) {
        TextField why = FormSheet.text("Ex. Archivé par erreur");
        FormSheet f = new FormSheet("Restaurer " + v.registration(), "Restaurer", shell::closeSheet)
                .intro("Le véhicule revient dans le parc actif avec le statut « Disponible ». Son historique est intact.")
                .field("Motif *", why);
        f.onSubmit(() -> shell.gateway().restoreVehicle(v, reason(why)), (Vehicle saved) -> {
            shell.closeSheet();
            shell.toasts().success("Véhicule restauré", saved.registration() + " est de nouveau disponible.");
            after.accept(saved);
        });
        shell.openSheet(f, 440);
    }

    // ------------------------------------------------------------------ vidange

    /** Corriger une vidange (toutes les valeurs), ou la supprimer si elle a été saisie par erreur. */
    static void oil(AppShell shell, Prod.OilChange o, Runnable after) {
        if (!shell.session().can(Permission.MAINTENANCE_WRITE)) {
            shell.toasts().info("Lecture seule", "La correction des vidanges est réservée à l'atelier.");
            return;
        }
        var date = FormSheet.date(o.doneOn());
        TextField km = FormSheet.text("Kilométrage");
        km.setText(o.odometerKm() == null ? "" : o.odometerKm().stripTrailingZeros().toPlainString());
        TextField oilLabel = FormSheet.text("Ex. 15W40");
        oilLabel.setText(o.oilLabel() == null ? "" : o.oilLabel());
        TextField liters = FormSheet.text("Ex. 7");
        liters.setText(o.oilLiters() == null ? "" : o.oilLiters().stripTrailingZeros().toPlainString());
        TextField everyKm = FormSheet.text("Ex. 5000");
        everyKm.setText(o.intervalKm() == null ? "" : String.valueOf(o.intervalKm()));
        TextField everyMonths = FormSheet.text("Ex. 6");
        everyMonths.setText(o.intervalMonths() == null ? "" : String.valueOf(o.intervalMonths()));
        TextField cost = FormSheet.text("FCFA (facultatif)");
        cost.setText(o.costXaf() == null ? "" : o.costXaf().stripTrailingZeros().toPlainString());
        TextField garage = FormSheet.text("Ex. Atelier central");
        garage.setText(o.garageLabel() == null ? "" : o.garageLabel());
        List<String> has = o.filters();
        var oilFilter = FormSheet.check("Filtre à huile", has.contains("Filtre à huile"));
        var airFilter = FormSheet.check("Filtre à air", has.contains("Filtre à air"));
        var fuelFilter = FormSheet.check("Filtre à carburant", has.contains("Filtre à carburant"));
        var gearbox = FormSheet.check("Huile de boîte", has.contains("Huile de boîte"));
        var coolant = FormSheet.check("Liquide de refroidissement", has.contains("Liquide de refroidissement"));
        var washer = FormSheet.check("Lave-glace", has.contains("Lave-glace"));
        FlowPane checks = new FlowPane(16, 8, oilFilter, airFilter, fuelFilter, gearbox, coolant, washer);
        TextField why = FormSheet.text("Ex. Kilométrage mal recopié");
        FormSheet f = new FormSheet("Modifier la vidange", "Enregistrer", shell::closeSheet)
                .intro(o.registration() + ". La prochaine échéance est recalculée et le relevé kilométrique de cette vidange suit la correction.")
                .row("Date *", date, "Kilométrage *", km).row("Huile", oilLabel, "Quantité (L)", liters).node(checks)
                .row("Intervalle (km)", everyKm, "Intervalle (mois)", everyMonths).row("Coût (FCFA)", cost, "Garage", garage)
                .field("Motif *", why)
                .dangerAction("Supprimer cette vidange…", () -> confirm(shell, "Supprimer la vidange", "Supprimer",
                        o.registration() + " · vidange du " + Views.day(o.doneOn()) + " à " + Views.km(o.odometerKm())
                                + ". Elle disparaît de l'historique et de l'état des vidanges ; son contenu reste dans le journal d'audit.",
                        "Ex. Vidange saisie deux fois", r -> {
                            shell.gateway().deleteOilChange(o.id(), r);
                            return Boolean.TRUE;
                        }, "Vidange supprimée", after));
        f.onSubmit(() -> shell.gateway().updateOilChange(o.id(), new Prod.OilChangeEdit(FormSheet.day(date, "Date", true),
                FormSheet.dec(km, "Kilométrage", true), FormSheet.opt(oilLabel), FormSheet.dec(liters, "Quantité", false),
                oilFilter.isSelected(), fuelFilter.isSelected(), airFilter.isSelected(), gearbox.isSelected(), coolant.isSelected(),
                washer.isSelected(), FormSheet.integer(everyKm, "Intervalle"), FormSheet.integer(everyMonths, "Intervalle"),
                FormSheet.dec(cost, "Coût", false), FormSheet.opt(garage)), reason(why)), (Prod.OilChange saved) -> {
            shell.closeSheet();
            shell.toasts().success("Vidange modifiée", saved.registration()
                    + (saved.nextDueKm() != null ? " · prochaine à " + Views.km(saved.nextDueKm()) : ""));
            after.run();
        });
        shell.openSheet(f, 520);
    }

    // ------------------------------------------------------------------ conducteur

    /** Corriger l'identité d'un conducteur, ou l'archiver (il ne peut plus recevoir de véhicule ni de mission). */
    static void driver(AppShell shell, Driver d, Runnable after) {
        Ui.async(() -> shell.gateway().driverDetail(d.id()), cur -> {
            TextField staff = FormSheet.text("Matricule");
            staff.setText(cur.staffNumber());
            TextField name = FormSheet.text("Prénom et nom");
            name.setText(cur.fullName());
            TextField phone = FormSheet.text("+237 6 …");
            phone.setText(cur.phone() == null ? "" : cur.phone());
            TextField email = FormSheet.text("Facultatif");
            email.setText(cur.email() == null ? "" : cur.email());
            TextField cni = FormSheet.text("Numéro de CNI");
            cni.setText(cur.idCardNumber() == null ? "" : cur.idCardNumber());
            var cniUntil = FormSheet.date(cur.idCardExpiresOn());
            TextField why = FormSheet.text("Ex. Numéro de téléphone changé");
            FormSheet f = new FormSheet("Modifier le conducteur", "Enregistrer", shell::closeSheet)
                    .intro("Le permis se met à jour en ajoutant le nouveau permis ; l'ancien reste dans l'historique.")
                    .row("Matricule *", staff, "Nom complet *", name).row("Téléphone", phone, "E-mail", email)
                    .section("Pièce d'identité").row("Numéro", cni, "Valable jusqu'au", cniUntil)
                    .field("Motif *", why)
                    .dangerAction("Archiver ce conducteur…", () -> confirm(shell, "Archiver " + cur.fullName(), "Archiver",
                            "Le conducteur disparaît de la liste et ne peut plus recevoir de véhicule ni de mission ; son affectation en cours "
                                    + "se termine maintenant. Son historique est conservé.",
                            "Ex. Départ de l'entreprise", r -> {
                                shell.gateway().archiveDriver(cur, r);
                                return Boolean.TRUE;
                            }, "Conducteur archivé", after));
            f.onSubmit(() -> shell.gateway().updateDriver(cur, new Prod.DriverDraft(FormSheet.req(staff, "Matricule"),
                    FormSheet.req(name, "Nom complet"), FormSheet.opt(phone), FormSheet.opt(email), null, FormSheet.opt(cni),
                    cniUntil.getValue(), null, List.of(), null), reason(why)), (Driver saved) -> {
                shell.closeSheet();
                shell.toasts().success("Conducteur modifié", saved.fullName() + " · " + saved.staffNumber());
                after.run();
            });
            shell.openSheet(f, 500);
        }, err -> shell.toasts().info("Modification impossible", err.getMessage()));
    }

    // ------------------------------------------------------------------ intervention

    /** Corriger l'objet, la catégorie ou le garage d'une intervention encore ouverte. */
    static void work(AppShell shell, Prod.WorkOrder w, List<Prod.Category> categories, Runnable after) {
        TextField title = FormSheet.text("Objet de l'intervention");
        title.setText(w.title());
        ComboBox<Prod.Category> cat = FormSheet.combo(categories);
        categories.stream().filter(c -> c.code().equals(w.categoryCode())).findFirst().ifPresent(cat::setValue);
        TextField garage = FormSheet.text("Ex. Garage du centre");
        garage.setText(w.garageLabel() == null ? "" : w.garageLabel());
        TextField why = FormSheet.text("Ex. Diagnostic précisé par l'atelier");
        FormSheet f = new FormSheet("Modifier l'intervention", "Enregistrer", shell::closeSheet)
                .intro(w.registration() + " · " + w.status().label + ". Pour l'abandonner, utilisez « Annuler l'intervention ».")
                .field("Objet *", title).row("Catégorie", cat, "Garage", garage).field("Motif *", why);
        f.onSubmit(() -> shell.gateway().updateWork(w, FormSheet.req(title, "Objet"), cat.getValue() == null ? null : cat.getValue().code(),
                FormSheet.opt(garage), reason(why)), (Prod.WorkOrder saved) -> {
            shell.closeSheet();
            shell.toasts().success("Intervention modifiée", saved.registration() + " · " + saved.title());
            after.run();
        });
        shell.openSheet(f, 480);
    }
}
