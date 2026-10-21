package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Fiche véhicule en panneau latéral (écran 10, version lot 1 ; maquette p. 18 écran 03).
 * Sans photo ni actif 3D licencié, une ILLUSTRATION GÉNÉRIQUE est affichée et signalée comme telle (3D-02).
 */
final class VehicleSheet extends VBox {

    VehicleSheet(Vehicle v, cm.camtel.nextfleet.desktop.data.FleetGateway gateway, Runnable onClose, Runnable onArchive) {
        this(v, gateway, null, onClose, onArchive);
    }

    VehicleSheet(Vehicle v, cm.camtel.nextfleet.desktop.data.FleetGateway gateway, AppShell shell, Runnable onClose, Runnable onArchive) {
        boolean demo = gateway.isDemo();
        getStyleClass().add("sheet");
        Button close = new Button(null, Icons.stroke(Icons.CLOSE, 12, "-nf-muted"));
        close.getStyleClass().setAll("button", "sheet-close");
        close.setAccessibleText("Fermer la fiche");
        close.setOnAction(e -> onClose.run());
        close.setCancelButton(true);

        Label overline = Ui.label(v.label().toUpperCase(Locale.FRANCE), "overline");
        Label reg = Ui.label(v.registration(), "t-28", "w-700");
        HBox title = new HBox(10, new VBox(2, overline, reg), Ui.spacer(), Ui.statusPill(v.status()), close);
        title.setAlignment(Pos.CENTER_LEFT);

        Label illusNote = Ui.label("Illustration générique — aucune photo ni modèle 3D associé", "muted", "t-12");
        illusNote.setWrapText(true);
        VBox illus = new VBox(6, genericVehicle(), illusNote);
        illus.setAlignment(Pos.CENTER);
        illus.getStyleClass().add("vehicle-illustration");

        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE);
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(14);
        grid.add(tile(Icons.USER, "Conducteur", v.driverName() == null ? "Non affecté" : v.driverName()), 0, 0);
        grid.add(tile(Icons.MAP_PIN, demo ? "Position déclarée (fictive)" : "Position déclarée",
                v.lastKnownPlace() == null ? "Non renseignée" : v.lastKnownPlace()), 1, 0);
        grid.add(tile(Icons.GAUGE, "Kilométrage", v.odometerKm() == null ? "Non renseigné" : Ui.decimal(v.odometerKm(), 0) + " km"), 0, 1);
        grid.add(tile(Icons.FUEL, "Carburant", v.fuelLevelPercent() == null ? "Non renseigné" : v.fuelLevelPercent() + " %"), 1, 1);
        grid.add(tile(Icons.CAR, "Catégorie", v.category() == null ? "Non renseignée" : v.category()), 0, 2);
        grid.add(tile(Icons.WRENCH, "Prochain entretien", v.nextMaintenance() == null ? "Non planifié" : v.nextMaintenance().format(f)), 1, 2);

        getChildren().addAll(title, illus, grid);

        if (v.fuelLevelPercent() != null && v.fuelLevelPercent() > 0 && v.fuelLevelPercent() < 20) {
            VBox t = new VBox(2, Ui.label("Niveau de carburant bas", "t-12", "w-600"),
                    Ui.label("Le niveau déclaré est inférieur à 20 %.", "muted", "t-12"));
            HBox warn = new HBox(12, Ui.bubble(Icons.FUEL, "warning", "-nf-warning"), t);
            warn.setAlignment(Pos.CENTER_LEFT);
            warn.setStyle("-fx-background-color: -nf-warning-soft; -fx-background-radius: 12; -fx-padding: 10;");
            getChildren().add(warn);
        }

        // Échéances du véhicule : même source et mêmes niveaux que l'écran Échéances (NF-11)
        VBox dues = new VBox(6, Ui.label("ÉCHÉANCES", "sheet-section"), Ui.skeleton(-1, 18), Ui.skeleton(-1, 18));
        getChildren().add(dues);
        Ui.async(() -> gateway.vehicleDueDates(v.id()), list -> {
            dues.getChildren().setAll(Ui.label("ÉCHÉANCES", "sheet-section"));
            DateTimeFormatter df = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);
            for (cm.camtel.nextfleet.desktop.data.Models.DueItem d : list) {
                HBox row = new HBox(10, Ui.label(d.kind().label, "t-12", "w-600"), Ui.spacer(),
                        Ui.label(d.dueOn().format(df), "muted", "t-12"), Ui.dueChip(d));
                row.setAlignment(Pos.CENTER_LEFT);
                dues.getChildren().add(row);
            }
            Motion.stagger(dues.getChildren().subList(1, dues.getChildren().size()));
        }, err -> dues.getChildren().setAll(Ui.label("ÉCHÉANCES", "sheet-section"), Ui.label(err.getMessage(), "muted", "t-12")));

        // Documents du véhicule (assurance, vignette, contrôle technique, carte grise) avec justificatif
        boolean canWrite = shell != null && shell.session().can(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_WRITE);
        if (!demo) {
            VBox docs = new VBox(6, Ui.label("DOCUMENTS", "sheet-section"), Ui.skeleton(-1, 18));
            getChildren().add(docs);
            Ui.async(() -> gateway.vehicleDocuments(v.id()), list -> {
                docs.getChildren().setAll(Ui.label("DOCUMENTS", "sheet-section"));
                if (list.isEmpty()) docs.getChildren().add(Ui.label("Aucun document enregistré.", "muted", "t-12"));
                DateTimeFormatter df = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);
                for (cm.camtel.nextfleet.desktop.data.Prod.VehicleDocument d : list) {
                    HBox row = new HBox(10, Ui.label(docLabel(d.docType()) + (d.reference() != null ? " · " + d.reference() : ""), "t-12", "w-600"),
                            Ui.spacer(), Ui.label("jusqu'au " + d.validUntil().format(df), "muted", "t-12"));
                    row.setAlignment(Pos.CENTER_LEFT);
                    if (d.fileId() != null && shell != null) {
                        Button open = new Button("Ouvrir");
                        open.getStyleClass().addAll("btn-plain", "t-12");
                        open.setOnAction(e -> Files.open(shell, d.fileId(), docLabel(d.docType()).toLowerCase(Locale.FRANCE)));
                        row.getChildren().add(open);
                    }
                    docs.getChildren().add(row);
                }
            }, err -> docs.getChildren().setAll(Ui.label("DOCUMENTS", "sheet-section"), Ui.label(err.getMessage(), "muted", "t-12")));
        }
        VBox actions = new VBox(8);
        if (canWrite && !demo) {
            Button doc = new Button("Ajouter un document", Icons.stroke(Icons.DOC, 15, "-nf-accent"));
            doc.getStyleClass().add("btn-tinted");
            doc.setMaxWidth(Double.MAX_VALUE);
            doc.setOnAction(e -> documentSheet(v, shell));
            Button assign = new Button(v.driverName() == null ? "Affecter un conducteur" : "Changer de conducteur", Icons.stroke(Icons.USER, 15, "-nf-accent"));
            assign.getStyleClass().add("btn-tinted");
            assign.setMaxWidth(Double.MAX_VALUE);
            assign.setOnAction(e -> assignSheet(v, shell));
            actions.getChildren().addAll(doc, assign);
        }
        if (shell != null && (shell.visible(AppShell.Section.MAINTENANCE) || shell.visible(AppShell.Section.WORKSHOP))) {
            Button maint = new Button("Voir les entretiens", Icons.stroke(Icons.WRENCH, 16, "white"));
            maint.getStyleClass().addAll("btn-primary", "btn-large");
            maint.setMaxWidth(Double.MAX_VALUE);
            maint.setOnAction(e -> shell.show(shell.visible(AppShell.Section.WORKSHOP) ? AppShell.Section.WORKSHOP : AppShell.Section.MAINTENANCE));
            actions.getChildren().add(maint);
        }
        getChildren().addAll(Ui.spacer(), actions);
        if (onArchive != null) {
            Button archive = new Button("Archiver le véhicule…");
            archive.getStyleClass().add("btn-gray");
            archive.setMaxWidth(Double.MAX_VALUE);
            archive.setOnAction(e -> onArchive.run());
            Motion.pressable(archive);
            getChildren().add(archive);
        }
        setAccessibleText("Fiche du véhicule " + v.registration());
    }

    static String docLabel(String type) {
        return switch (type) {
            case "ASSURANCE" -> "Assurance";
            case "CONTROLE_TECHNIQUE" -> "Contrôle technique";
            case "VIGNETTE" -> "Vignette";
            case "CARTE_GRISE" -> "Carte grise";
            default -> "Autre document";
        };
    }

    private static void documentSheet(Vehicle v, AppShell shell) {
        var type = FormSheet.combo(java.util.List.of("ASSURANCE", "VIGNETTE", "CONTROLE_TECHNIQUE", "CARTE_GRISE", "AUTRE"));
        type.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String t) {
                return t == null ? "" : docLabel(t);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        var ref = FormSheet.text("Ex. numéro de police");
        var from = FormSheet.date(null);
        var until = FormSheet.date(Views.today().plusYears(1));
        FormSheet.FilePick file = new FormSheet.FilePick("Joindre le document");
        FormSheet f = new FormSheet("Nouveau document", "Enregistrer", shell::closeSheet).intro(v.registration() + " · " + v.label()
                        + ". L'échéance apparaît aussitôt dans les échéances et les notifications.")
                .field("Type *", type).field("Référence", ref).row("Valable du", from, "au *", until).field("Justificatif", file);
        f.onSubmit(() -> {
            java.util.UUID fileId = file.path() == null ? null : shell.gateway().upload("VEHICLE_DOCUMENT", v.id(), file.path()).id();
            return shell.gateway().addVehicleDocument(v.id(), FormSheet.pick(type, "Type"), FormSheet.opt(ref), from.getValue(),
                    FormSheet.day(until, "Fin de validité", true), fileId);
        }, (cm.camtel.nextfleet.desktop.data.Prod.VehicleDocument d) -> {
            shell.closeSheet();
            shell.toasts().success("Document enregistré", docLabel(d.docType()) + " · " + v.registration());
        });
        shell.openSheet(f, 460);
    }

    private static void assignSheet(Vehicle v, AppShell shell) {
        Ui.async(() -> shell.gateway().driverRefs(), drivers -> {
            java.util.List<Object> items = new java.util.ArrayList<>(drivers);
            items.add(0, "Aucun conducteur attitré");
            var who = FormSheet.combo(items);
            if (!drivers.isEmpty()) who.getSelectionModel().select(1);
            var reason = FormSheet.text("Ex. Affectation au service technique");
            FormSheet f = new FormSheet("Conducteur attitré", "Affecter", shell::closeSheet)
                    .intro(v.registration() + " · " + v.label() + ". L'affectation précédente se termine maintenant ; l'historique est conservé.")
                    .field("Conducteur", who).field("Motif *", reason);
            f.onSubmit(() -> {
                Object o = who.getValue();
                java.util.UUID id = o instanceof cm.camtel.nextfleet.desktop.data.Prod.DriverRef d ? d.id() : null;
                String r = FormSheet.req(reason, "Motif");
                if (r.length() < 5) throw new IllegalArgumentException("Motif : 5 caractères au moins.");
                shell.gateway().assignDriver(v.id(), id, r);
                return o instanceof cm.camtel.nextfleet.desktop.data.Prod.DriverRef d ? d.fullName() : "aucun conducteur";
            }, (String name) -> {
                shell.closeSheet();
                shell.toasts().success("Affectation enregistrée", v.registration() + " → " + name);
                shell.openVehicles(v.registration());
            });
            shell.openSheet(f, 440);
        }, err -> shell.toasts().info("Affectation impossible", err.getMessage()));
    }

    private static Node tile(String icon, String caption, String value) {
        Label c = Ui.label(caption, "caption");
        Label val = Ui.label(value, "value");
        val.setWrapText(true);
        HBox h = new HBox(10, Icons.stroke(icon, 16, "-nf-muted"), new VBox(2, c, val));
        h.getStyleClass().add("info-tile");
        h.setPrefWidth(170);
        return h;
    }

    /** Silhouette générique de pick-up, dessinée pour NexFleet (aucun modèle de constructeur reproduit). */
    private static Node genericVehicle() {
        SVGPath body = new SVGPath();
        body.setContent("M10 70 L10 52 Q10 46 16 45 L58 42 L78 22 Q82 18 90 18 L150 18 Q158 18 162 26 L172 44 "
                + "L280 44 Q288 44 288 52 L288 70 Z M88 26 L150 26 L160 44 L74 44 Z "
                + "M60 70 A16 16 0 1 0 92 70 M210 70 A16 16 0 1 0 242 70");
        body.setStyle("-fx-fill: transparent; -fx-stroke: -nf-muted; -fx-stroke-width: 2; -fx-stroke-line-join: round;");
        SVGPath wheels = new SVGPath();
        wheels.setContent("M62 72 A14 14 0 1 0 90 72 A14 14 0 1 0 62 72 Z M212 72 A14 14 0 1 0 240 72 A14 14 0 1 0 212 72 Z");
        wheels.setStyle("-fx-fill: -nf-surface; -fx-stroke: -nf-muted; -fx-stroke-width: 2;");
        StackPane p = new StackPane(new Group(body, wheels));
        p.setPrefHeight(100);
        return p;
    }
}
