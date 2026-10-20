package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Ops.Mission;
import cm.camtel.nextfleet.desktop.data.Ops.MissionStatus;
import cm.camtel.nextfleet.domain.gps.GpsFix;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Espace conducteur « Mes missions » : la mission du moment en carte héros, démarrage après information explicite sur
 * la localisation (notice versionnée), suivi du trajet, clôture qui arrête la collecte, historique.
 * Sur le terrain, la collecte se fait sur l'application Android (lot 3) ; en démonstration, le poste simule le téléphone.
 */
final class DriverHomeView extends ScrollPane {

    static final String NOTICE = "NOTICE-GPS-v1";
    private final AppShell shell;
    private final VBox col = new VBox(22);
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(5), e -> load()));
    private final TrackMap map = new TrackMap();
    private Mission current;

    DriverHomeView(AppShell shell) {
        this.shell = shell;
        setFitToWidth(true);
        col.getStyleClass().add("content");
        col.setMaxWidth(980);
        StackPane center = new StackPane(col);
        center.setAlignment(Pos.TOP_CENTER);
        setContent(center);
        refresh.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((o, a, s) -> {
            if (s == null) refresh.stop();
        });
        col.getChildren().setAll(Ui.label("Mes missions", "large-title"), Ui.skeleton(-1, 200));
        load();
    }

    private void load() {
        Ui.async(() -> shell.gateway().missions(), ms -> {
            Mission active = ms.stream().filter(m -> m.status() == MissionStatus.ACTIVE).findFirst().orElse(null);
            Mission next = ms.stream().filter(m -> m.status() == MissionStatus.AUTHORIZED).findFirst().orElse(null);
            List<Mission> past = ms.stream().filter(m -> m.status() == MissionStatus.FINISHED).toList();
            Mission focus = active != null ? active : next;
            boolean changed = current == null || focus == null || !focus.id().equals(current.id()) || focus.status() != current.status();
            current = focus;
            if (active != null) {
                Ui.async(() -> shell.gateway().missionTrack(active.id()), fixes -> render(active, null, past, fixes, changed), err -> { });
                refresh.play();
            } else {
                refresh.stop();
                render(null, next, past, List.of(), true);
            }
        }, err -> col.getChildren().setAll(Ui.label("Mes missions", "large-title"), Ui.errorBanner(err, this::load)));
    }

    private void render(Mission active, Mission next, List<Mission> past, List<GpsFix> fixes, boolean full) {
        Instant now = Instant.now();
        if (!full && active != null && col.getChildren().size() > 4) {   // rafraîchissement en direct sans reconstruire l'écran
            map.setTrack(fixes, MissionPanels.ANALYZER.statistics(fixes).stops(), MissionPanels.isLive(fixes, active, now),
                    MissionPanels.liveText(fixes, active, now));
            col.getChildren().set(1, hero(active, fixes));
            col.getChildren().set(4, MissionPanels.stats(fixes, active, now));
            return;
        }
        List<Node> nodes = new ArrayList<>();
        nodes.add(Ui.label("Mes missions", "large-title"));
        if (active != null) {
            nodes.add(hero(active, fixes));
            map.setPrefHeight(340);
            map.setTrack(fixes, MissionPanels.ANALYZER.statistics(fixes).stops(), MissionPanels.isLive(fixes, active, now),
                    MissionPanels.liveText(fixes, active, now));
            nodes.add(map);
            nodes.add(TrackMap.legend());
            nodes.add(MissionPanels.stats(fixes, active, now));
        } else if (next != null) {
            nodes.add(hero(next, fixes));
        } else {
            nodes.add(Ui.statePanel(Icons.ROUTE, "Aucune mission à venir", "Les missions autorisées par votre gestionnaire apparaîtront ici."));
        }
        nodes.add(MissionPanels.privacyNote(shell.gateway().isDemo()
                ? "Démonstration : le poste simule le téléphone de mission et les positions sont fictives. Sur le terrain, la "
                + "localisation fonctionne dans l'application Android, uniquement entre le démarrage et la clôture de la mission."
                : "La localisation fonctionne uniquement entre le démarrage et la clôture de la mission."));
        if (!past.isEmpty()) {
            List<Node> rows = past.stream().map(m -> (Node) Ui.listRow(Ui.symbolTile(Icons.CHECK, "tile-gray"),
                    m.reference() + " · " + m.destination(), MissionPanels.DAY_HM.format(m.startedAt()), null, null)).toList();
            nodes.add(Ui.insetGroup("Missions terminées", rows, null));
        }
        col.getChildren().setAll(nodes);
        Motion.stagger(col.getChildren());
    }

    private Node hero(Mission m, List<GpsFix> fixes) {
        boolean active = m.status() == MissionStatus.ACTIVE;
        Label over = Ui.label(active ? "MISSION EN COURS" : "PROCHAINE MISSION", "overline");
        over.setStyle("-fx-text-fill: rgba(255,255,255,0.78);");
        Label title = Ui.label(m.purpose(), "title1");
        title.setWrapText(true);
        Label route = Ui.label(m.origin() + " → " + m.destination(), "title3");
        route.setStyle("-fx-font-weight: 500;");
        Label info = Ui.label(m.reference() + " · " + m.registration() + " · " + m.vehicleLabel(), "subhead", "muted");
        VBox text = new VBox(6, over, title, route, info);

        HBox status = new HBox(8);
        status.setAlignment(Pos.CENTER_LEFT);
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_LEFT);
        if (active) {
            Circle dot = new Circle(5);
            dot.setStyle("-fx-fill: white;");
            Motion.pulse(dot, 0.3, Duration.millis(1300));
            status.getChildren().addAll(dot, Ui.label("Localisation active · " + fixes.size() + (fixes.size() > 1 ? " positions" : " position") + " · depuis "
                    + MissionPanels.HM.format(m.startedAt()), "callout"));
            if (m.conversationId() != null) buttons.getChildren().add(glass("Messages", Icons.BUBBLE, () -> shell.openConversation(m.conversationId())));
            Button end = new Button("Terminer la mission", Icons.stroke(Icons.STOP, 14, "-nf-navy"));
            end.getStyleClass().addAll("btn-on-dark", "btn-large");
            end.setOnAction(e -> confirmFinish(m));
            Motion.pressable(end);
            buttons.getChildren().add(0, end);
        } else {
            status.getChildren().add(Ui.label("Prévue " + MissionPanels.DAY_HM.format(m.plannedStart()) + " · localisation inactive", "callout", "muted"));
            Button start = new Button("Démarrer la mission", Icons.stroke(Icons.PLAY, 14, "-nf-navy"));
            start.getStyleClass().addAll("btn-on-dark", "btn-large");
            start.setDefaultButton(true);
            start.setOnAction(e -> confirmStart(m));
            Motion.pressable(start);
            buttons.getChildren().add(start);
            if (m.conversationId() != null) buttons.getChildren().add(glass("Messages", Icons.BUBBLE, () -> shell.openConversation(m.conversationId())));
        }
        VBox card = new VBox(16, text, status, buttons);
        card.getStyleClass().add("hero-card");
        if (active) card.getStyleClass().add("hero-active");
        card.setAccessibleText((active ? "Mission en cours " : "Prochaine mission ") + m.reference() + ", " + m.purpose());
        return card;
    }

    private static Button glass(String text, String icon, Runnable r) {
        Button b = new Button(text, Icons.stroke(icon, 15, "white"));
        b.getStyleClass().addAll("btn-glass", "btn-large");
        b.setOnAction(e -> r.run());
        Motion.pressable(b);
        return b;
    }

    /** Information préalable explicite (NF-70) : titre précis, conséquence, notice ; Annuler à gauche. */
    private void confirmStart(Mission m) {
        AlertDialog.show(shell, Ui.symbolTile(Icons.LOCATION, "tile-blue"), "Activer la localisation pour " + m.reference() + " ?",
                "La position du téléphone de mission sera enregistrée uniquement pendant cette mission, jusqu'à sa clôture. "
                        + "Notice " + NOTICE + ".",
                List.of(AlertDialog.Action.cancel("Annuler"), AlertDialog.Action.preferred("Démarrer", () ->
                        Ui.async(() -> shell.gateway().startMission(m.id(), NOTICE), s -> {
                            shell.toasts().success("Mission démarrée", "Localisation active jusqu'à la clôture.");
                            current = null;
                            load();
                        }, err -> AlertDialog.show(shell, Ui.symbolTile(Icons.CLOSE, "tile-red"), "Démarrage impossible", err.getMessage(),
                                List.of(AlertDialog.Action.preferred("OK", () -> { })))))));
    }

    private void confirmFinish(Mission m) {
        AlertDialog.show(shell, Ui.symbolTile(Icons.STOP, "tile-gray"), "Terminer " + m.reference() + " ?",
                "La localisation s'arrête immédiatement. Plus aucune position ne sera acceptée pour cette mission.",
                List.of(AlertDialog.Action.cancel("Annuler"), AlertDialog.Action.preferred("Terminer", () ->
                        Ui.async(() -> shell.gateway().finishMission(m.id()), s -> {
                            shell.toasts().success("Mission terminée", "La localisation est arrêtée.");
                            current = null;
                            load();
                        }, err -> AlertDialog.show(shell, Ui.symbolTile(Icons.CLOSE, "tile-red"), "Clôture impossible", err.getMessage(),
                                List.of(AlertDialog.Action.preferred("OK", () -> { })))))));
    }

    /** Pour la recette visuelle : ouvre l'alerte de démarrage de la prochaine mission. */
    void startForTest() {
        if (current != null && current.status() == MissionStatus.AUTHORIZED) confirmStart(current);
    }
}
