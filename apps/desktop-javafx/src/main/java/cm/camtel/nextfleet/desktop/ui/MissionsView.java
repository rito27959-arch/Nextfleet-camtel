package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Ops.Mission;
import cm.camtel.nextfleet.desktop.data.Ops.MissionStatus;
import cm.camtel.nextfleet.domain.gps.GpsFix;
import cm.camtel.nextfleet.domain.security.Permission;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Missions et trajets (gestionnaire, contrôleur) : liste filtrée à gauche, détail à droite avec tracé, statistiques
 * qualifiées, informations de collecte. Rafraîchissement toutes les 5 s tant qu'une mission en cours est affichée.
 */
final class MissionsView extends BorderPane {

    private final AppShell shell;
    private final VBox list = new VBox(8);
    private final VBox detail = new VBox(16);
    private final TrackMap map = new TrackMap();
    private List<Mission> missions = List.of();
    private UUID selected;
    private int filter;
    private Controls.Segmented seg;
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(5), e -> reloadSelected()));

    MissionsView(AppShell shell) {
        this.shell = shell;
        setStyle("-fx-background-color: -nf-bg;");

        seg = new Controls.Segmented(List.of("En cours", "Planifiées", "Terminées"), 0, i -> {
            filter = i;
            renderList();
        });
        HBox titleRow = new HBox(8, Ui.label("Missions", "large-title"), Ui.spacer());
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (shell.session().can(Permission.MISSION_MANAGE)) {
            Button add = new Button(null, Icons.stroke(Icons.PLUS, 16, "white"));
            add.getStyleClass().addAll("btn-filled", "btn-capsule");
            add.setAccessibleText("Nouvelle mission");
            add.setTooltip(new javafx.scene.control.Tooltip("Nouvelle mission"));
            add.setOnAction(e -> createSheet());
            titleRow.getChildren().add(add);
        }
        VBox header = new VBox(10, titleRow, seg);
        header.getStyleClass().add("split-list-header");
        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        list.setPadding(new Insets(4, 14, 18, 14));
        VBox left = new VBox(header, sp);
        VBox.setVgrow(sp, Priority.ALWAYS);
        left.getStyleClass().add("split-list");
        setLeft(Ui.rounded(left, 22));
        // iOS 26 : colonnes en panneaux arrondis décollés des bords, alignés sur la barre latérale flottante
        setPadding(new Insets(4, 22, 22, 4));
        BorderPane.setMargin(left, new Insets(0, 14, 0, 0));

        ScrollPane right = new ScrollPane(detail);
        right.setFitToWidth(true);
        detail.setPadding(new Insets(26, 30, 30, 30));
        setCenter(right);
        detail.getChildren().add(Ui.statePanel(Icons.ROUTE, "Sélectionnez une mission", "Le tracé et les statistiques apparaissent ici."));

        refresh.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((o, a, s) -> {
            if (s == null) refresh.stop();
        });
        load();
    }

    private void load() {
        Ui.load("missions", () -> shell.gateway().missions(), ms -> {
            missions = ms;
            if (selected == null) ms.stream().filter(m -> m.status() == MissionStatus.ACTIVE).findFirst().ifPresent(m -> selected = m.id());
            renderList();
            if (selected != null) select(selected);
        }, err -> list.getChildren().setAll(Ui.errorBanner(err, this::load)),
                () -> list.getChildren().setAll(Ui.skeleton(-1, 76), Ui.skeleton(-1, 76), Ui.skeleton(-1, 76)));
    }

    private boolean inFilter(Mission m) {
        return switch (filter) {
            case 0 -> m.status() == MissionStatus.ACTIVE;
            case 1 -> m.status() == MissionStatus.AUTHORIZED || m.status() == MissionStatus.DRAFT;
            default -> m.status() == MissionStatus.FINISHED || m.status() == MissionStatus.CANCELLED;
        };
    }

    private void renderList() {
        List<Mission> shown = missions.stream().filter(this::inFilter).toList();
        if (shown.isEmpty()) {
            Label l = Ui.label(filter == 0 ? "Aucune mission en cours." : "Aucune mission.", "muted", "subhead");
            l.setPadding(new Insets(16, 6, 0, 6));
            list.getChildren().setAll(l);
            return;
        }
        list.getChildren().setAll(shown.stream().map(this::card).toList());
        Motion.stagger(list.getChildren());
    }

    private Node card(Mission m) {
        HBox top = new HBox(8, Ui.label(m.reference(), "mission-ref"), Ui.spacer(), MissionPanels.statusPill(m.status()));
        top.setAlignment(Pos.CENTER_LEFT);
        Label purpose = Ui.label(m.purpose(), "headline");
        purpose.setWrapText(true);
        Label route = Ui.label(m.origin() + " → " + m.destination(), "subhead", "muted");
        route.setWrapText(true);
        Label who = new Label(m.registration() + " · " + m.driverName(), Icons.stroke(Icons.CAR, 14, "-nf-muted"));
        who.getStyleClass().addAll("footnote", "muted");
        who.setGraphicTextGap(6);
        VBox c = new VBox(4, top, purpose, route, who);
        c.getStyleClass().add("mission-card");
        if (m.id().equals(selected)) c.getStyleClass().add("selected");
        c.setOnMouseClicked(e -> select(m.id()));
        c.setAccessibleText("Mission " + m.reference() + ", " + m.status().label + ", " + m.purpose());
        Motion.pressable(c);
        return c;
    }

    /** Met une mission au premier plan (depuis la recherche globale). */
    void focus(UUID id) {
        selected = id;
        missions.stream().filter(m -> m.id().equals(id)).findFirst().ifPresent(m -> {
            filter = switch (m.status()) {
                case ACTIVE -> 0;
                case AUTHORIZED, DRAFT -> 1;
                default -> 2;
            };
            if (seg != null) seg.select(filter);
            select(id);
        });
    }

    private void select(UUID id) {
        selected = id;
        renderList();
        Mission m = missions.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (m == null) return;
        Ui.async(() -> shell.gateway().missionTrack(id), fixes -> {
            renderDetail(m, fixes);
            if (m.status() == MissionStatus.ACTIVE) refresh.play();
            else refresh.stop();
        }, err -> detail.getChildren().setAll(Ui.errorBanner(err, () -> select(id))));
    }

    private void reloadSelected() {
        if (selected == null) return;
        UUID id = selected;
        Ui.async(() -> shell.gateway().missionTrack(id), fixes -> missions.stream().filter(x -> x.id().equals(id)).findFirst()
                .ifPresent(m -> updateLive(m, fixes)), err -> { });
    }


    private void updateLive(Mission m, List<GpsFix> fixes) {
        Instant now = Instant.now();
        map.setTrack(fixes, MissionPanels.ANALYZER.statistics(fixes).stops(), MissionPanels.isLive(fixes, m, now),
                MissionPanels.liveText(fixes, m, now));
        if (detail.getChildren().size() > 3) {
            detail.getChildren().set(3, MissionPanels.stats(fixes, m, now));
            detail.getChildren().set(5, MissionPanels.collection(m, fixes.size()));
        }
    }

    private void renderDetail(Mission m, List<GpsFix> fixes) {
        Instant now = Instant.now();
        HBox head = new HBox(10, Ui.label(m.reference(), "mission-ref"), MissionPanels.statusPill(m.status()));
        head.setAlignment(Pos.CENTER_LEFT);
        Label title = Ui.label(m.purpose(), "title1");
        title.setWrapText(true);
        Label route = Ui.label(m.origin() + " → " + m.destination(), "body", "muted");
        Label vehicle = new Label(m.registration() + " · " + m.vehicleLabel(), Icons.stroke(Icons.CAR, 15, "-nf-muted"));
        Label driver = new Label(m.driverName(), Icons.stroke(Icons.USER, 15, "-nf-muted"));
        Label plan = new Label("Prévue " + MissionPanels.DAY_HM.format(m.plannedStart()), Icons.stroke(Icons.CALENDAR, 15, "-nf-muted"));
        for (Label l : List.of(vehicle, driver, plan)) {
            l.getStyleClass().addAll("subhead", "muted");
            l.setGraphicTextGap(6);
        }
        javafx.scene.layout.FlowPane meta = new javafx.scene.layout.FlowPane(18, 6, vehicle, driver, plan);
        for (Label l : List.of(vehicle, driver, plan)) l.setMinWidth(Region.USE_PREF_SIZE);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (m.conversationId() != null && shell.session().can(Permission.CHAT_USE)) {
            Button chat = new Button("Messages de la mission", Icons.stroke(Icons.BUBBLE, 15, "-nf-accent-text"));
            chat.getStyleClass().add("btn-tinted");
            chat.setOnAction(e -> shell.openConversation(m.conversationId()));
            Motion.pressable(chat);
            actions.getChildren().add(chat);
        }
        actions.getChildren().forEach(n -> ((Button) n).setMinWidth(Region.USE_PREF_SIZE));
        if (m.status() == MissionStatus.ACTIVE && shell.session().can(Permission.MISSION_MANAGE)) {
            Button end = new Button("Clôturer la mission…");
            end.setMinWidth(Region.USE_PREF_SIZE);
            end.getStyleClass().add("btn-gray");
            end.setOnAction(e -> confirmFinish(m));
            Motion.pressable(end);
            actions.getChildren().add(end);
        }
        if (shell.session().can(Permission.MISSION_MANAGE) && m.status() == MissionStatus.DRAFT) {
            Button auth = new Button("Autoriser");
            auth.setMinWidth(Region.USE_PREF_SIZE);
            auth.getStyleClass().add("btn-filled");
            auth.setOnAction(e -> Ui.async(() -> shell.gateway().authorizeMission(m.id()), done -> {
                shell.toasts().success("Mission autorisée", m.reference() + " : le conducteur peut la démarrer depuis son téléphone.");
                load();
            }, err -> AlertDialog.show(shell, Ui.symbolTile(Icons.CLOSE, "tile-red"), "Autorisation impossible", err.getMessage(),
                    List.of(AlertDialog.Action.preferred("OK", () -> { })))));
            actions.getChildren().add(auth);
        }
        if (shell.session().can(Permission.MISSION_MANAGE) && (m.status() == MissionStatus.DRAFT || m.status() == MissionStatus.AUTHORIZED)) {
            Button cancel = new Button("Annuler…");
            cancel.setMinWidth(Region.USE_PREF_SIZE);
            cancel.getStyleClass().add("btn-gray");
            cancel.setOnAction(e -> cancelSheet(m));
            actions.getChildren().add(cancel);
        }
        HBox headRow = new HBox(10, head, Ui.spacer(), actions);
        headRow.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(6, headRow, title, route, meta);

        map.setPrefHeight(400);
        map.setTrack(fixes, MissionPanels.ANALYZER.statistics(fixes).stops(), MissionPanels.isLive(fixes, m, now),
                MissionPanels.liveText(fixes, m, now));
        detail.getChildren().setAll(top, map, TrackMap.legend(), MissionPanels.stats(fixes, m, now),
                MissionPanels.privacyNote("Localisation limitée à la mission autorisée : aucune position avant le démarrage ni "
                        + "après la clôture. Les statistiques excluent les points imprécis et les sauts."),
                MissionPanels.collection(m, fixes.size()));
        Motion.stagger(detail.getChildren());
    }

    private void confirmFinish(Mission m) {
        AlertDialog.show(shell, Ui.symbolTile(Icons.STOP, "tile-gray"), "Clôturer " + m.reference() + " ?",
                "La collecte de positions s'arrête immédiatement et l'autorisation de l'émetteur est révoquée.",
                List.of(AlertDialog.Action.cancel("Annuler"), AlertDialog.Action.preferred("Clôturer", () ->
                        Ui.async(() -> shell.gateway().finishMission(m.id()), done -> {
                            shell.toasts().success("Mission clôturée", m.reference() + " : collecte arrêtée.");
                            load();
                        }, err -> AlertDialog.show(shell, Ui.symbolTile(Icons.CLOSE, "tile-red"), "Clôture impossible", err.getMessage(),
                                List.of(AlertDialog.Action.preferred("OK", () -> { })))))));
    }

    private void cancelSheet(Mission m) {
        javafx.scene.control.TextArea reason = FormSheet.area("Ex. Site fermé ce jour-là");
        FormSheet f = new FormSheet("Annuler la mission", "Confirmer", shell::closeSheet).intro(m.reference() + " · " + m.purpose())
                .field("Motif *", reason);
        f.onSubmit(() -> {
            if (reason.getText().strip().length() < 5) throw new IllegalArgumentException("Motif : 5 caractères au moins.");
            return shell.gateway().cancelMission(m.id(), reason.getText().strip());
        }, (Mission done) -> {
            shell.closeSheet();
            shell.toasts().success("Mission annulée", done.reference());
            load();
        });
        shell.openSheet(f, 440);
    }

    private record DriverChoice(cm.camtel.nextfleet.desktop.data.Prod.DriverRef driver,
                                cm.camtel.nextfleet.desktop.data.Prod.DriverAccount account) {
        @Override public String toString() { return account == null ? driver.toString() : account + " · compte existant"; }
    }

    /** Création : la mission est un brouillon jusqu'à son autorisation par une AUTRE personne habilitée. */
    private void createSheet() {
        Ui.async(() -> new Object[]{shell.gateway().vehicles(null, null, 0, 200).items(), shell.gateway().driverRefs(),
                shell.session().can(cm.camtel.nextfleet.domain.security.Permission.DRIVER_WRITE) && !shell.gateway().isDemo()
                        ? shell.gateway().driverAccounts() : List.of()}, r -> {
            @SuppressWarnings("unchecked") List<cm.camtel.nextfleet.desktop.data.Models.Vehicle> vehicles = (List<cm.camtel.nextfleet.desktop.data.Models.Vehicle>) r[0];
            @SuppressWarnings("unchecked") List<cm.camtel.nextfleet.desktop.data.Prod.DriverRef> drivers = (List<cm.camtel.nextfleet.desktop.data.Prod.DriverRef>) r[1];
            @SuppressWarnings("unchecked") List<cm.camtel.nextfleet.desktop.data.Prod.DriverAccount> accounts = (List<cm.camtel.nextfleet.desktop.data.Prod.DriverAccount>) r[2];
            var choices = new java.util.ArrayList<DriverChoice>();
            drivers.forEach(d -> choices.add(new DriverChoice(d, null)));
            accounts.stream().filter(a -> a.driverId() == null).forEach(a -> choices.add(new DriverChoice(null, a)));
            if (vehicles.isEmpty() || choices.isEmpty()) {
                shell.toasts().info("Création impossible", vehicles.isEmpty() ? "Aucun véhicule dans votre périmètre." : "Aucun conducteur enregistré.");
                return;
            }
            var vehicle = FormSheet.combo(vehicles.stream().filter(v -> v.status() != cm.camtel.nextfleet.desktop.data.Models.VehicleStatus.ARCHIVED).toList());
            vehicle.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(cm.camtel.nextfleet.desktop.data.Models.Vehicle v) {
                    return v == null ? "" : v.registration() + " · " + v.label() + " · " + v.status().label;
                }

                @Override
                public cm.camtel.nextfleet.desktop.data.Models.Vehicle fromString(String s) {
                    return null;
                }
            });
            var driver = FormSheet.combo(choices);
            var purpose = FormSheet.text("Ex. Maintenance BTS Mvog-Mbi");
            var origin = FormSheet.text("Ex. Siège CAMTEL, Yaoundé");
            var destination = FormSheet.text("Ex. Obala");
            var day = FormSheet.date(Views.today());
            var start = FormSheet.text("HH:MM");
            start.setText("08:00");
            var endDay = FormSheet.date(Views.today());
            var end = FormSheet.text("HH:MM");
            end.setText("17:00");
            FormSheet f = new FormSheet("Nouvelle mission", "Créer", shell::closeSheet)
                    .intro("La mission est créée en brouillon ; une autre personne habilitée l'autorise. La localisation ne démarre "
                            + "que lorsque le conducteur lance la mission sur son téléphone, après la notice d'information.")
                    .field("Objet *", purpose).field("Véhicule *", vehicle).field("Conducteur *", driver)
                    .row("Départ", origin, "Destination", destination).row("Début *", day, "Heure", start).row("Fin *", endDay, "Heure", end);
            f.onSubmit(() -> {
                java.time.Instant s0 = at(FormSheet.day(day, "Début", true), start.getText());
                java.time.Instant e0 = at(FormSheet.day(endDay, "Fin", true), end.getText());
                if (!e0.isAfter(s0)) throw new IllegalArgumentException("La fin prévue doit suivre le début.");
                var vehicleId = FormSheet.pick(vehicle, "Véhicule").id();
                var text = FormSheet.req(purpose, "Objet");
                var chosen = FormSheet.pick(driver, "Conducteur");
                var driverId = chosen.account() == null ? withAccount(chosen.driver()).id()
                        : shell.gateway().driverFromAccount(chosen.account().id(), null).id();
                return shell.gateway().createMission(new cm.camtel.nextfleet.desktop.data.Prod.MissionDraft(vehicleId,
                        driverId, text, FormSheet.opt(origin), FormSheet.opt(destination), s0, e0));
            }, (Mission created) -> {
                shell.closeSheet();
                shell.toasts().success("Mission créée", created.reference() + " : à faire autoriser par une autre personne habilitée.");
                selected = created.id();
                seg.select(1);
                filter = 1;
                load();
            });
            shell.openSheet(f, 500);
        }, err -> shell.toasts().info("Création impossible", err.getMessage()));
    }

    private static java.time.Instant at(java.time.LocalDate d, String hhmm) {
        try {
            String t = hhmm == null || hhmm.isBlank() ? "08:00" : hhmm.strip();
            if (t.length() == 4) t = "0" + t;
            return d.atTime(java.time.LocalTime.parse(t)).atZone(Views.DOUALA).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Heure : format HH:MM attendu.");
        }
    }

    /** Pour la recette visuelle. */
    void selectReference(String ref) {
        missions.stream().filter(m -> m.reference().equals(ref)).findFirst().ifPresent(m -> {
            selected = m.id();
            if (seg.selected() != inFilterIndex(m)) seg.select(inFilterIndex(m));
            select(m.id());
        });
    }

    private static int inFilterIndex(Mission m) {
        return m.status() == MissionStatus.ACTIVE ? 0 : m.status() == MissionStatus.FINISHED ? 2 : 1;
    }

    /** Un conducteur sans compte ne pourrait jamais démarrer la mission : on l'explique avant même d'appeler le serveur. */
    private static cm.camtel.nextfleet.desktop.data.Prod.DriverRef withAccount(cm.camtel.nextfleet.desktop.data.Prod.DriverRef d) {
        if (Boolean.FALSE.equals(d.hasAccount())) {
            throw new IllegalArgumentException(d.fullName() + " n'a pas de compte NexFleet : il ne pourrait pas démarrer la mission. "
                    + "Ouvrez Conducteurs puis Lier un compte sur sa fiche, ou sélectionnez directement son compte existant.");
        }
        return d;
    }
}
