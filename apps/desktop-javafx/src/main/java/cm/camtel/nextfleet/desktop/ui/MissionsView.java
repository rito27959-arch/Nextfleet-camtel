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
        VBox header = new VBox(10, Ui.label("Missions", "large-title"), seg);
        header.getStyleClass().add("split-list-header");
        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        list.setPadding(new Insets(4, 14, 18, 14));
        VBox left = new VBox(header, sp);
        VBox.setVgrow(sp, Priority.ALWAYS);
        left.getStyleClass().add("split-list");
        setLeft(left);

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
        list.getChildren().setAll(Ui.skeleton(-1, 76), Ui.skeleton(-1, 76), Ui.skeleton(-1, 76));
        Ui.async(() -> shell.gateway().missions(), ms -> {
            missions = ms;
            if (selected == null) ms.stream().filter(m -> m.status() == MissionStatus.ACTIVE).findFirst().ifPresent(m -> selected = m.id());
            renderList();
            if (selected != null) select(selected);
        }, err -> list.getChildren().setAll(Ui.errorBanner(err, this::load)));
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
}
