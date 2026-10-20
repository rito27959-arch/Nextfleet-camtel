package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway;
import cm.camtel.nextfleet.desktop.data.Models.NotificationItem;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Centre de notifications (panneau latéral, à la manière du Centre de notifications macOS) :
 * groupes « Aujourd'hui », « Hier », « Plus tôt » ; filtre Toutes / Non lues ; « Tout marquer comme lu » ;
 * un clic ouvre le contenu lié et marque la notification comme lue. Les catégories désactivées dans Réglages sont masquées.
 */
final class NotificationCenter extends VBox {

    private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    private final FleetGateway gateway;
    private final Consumer<NotificationItem> onOpen;
    private final Runnable onChanged;
    private final VBox list = new VBox(8);
    private List<NotificationItem> items = List.of();
    private boolean unreadOnly;

    NotificationCenter(FleetGateway gateway, Runnable onClose, Consumer<NotificationItem> onOpen, Runnable onChanged) {
        this.gateway = gateway;
        this.onOpen = onOpen;
        this.onChanged = onChanged;
        getStyleClass().add("notification-center");
        setPrefWidth(400);

        Button close = new Button(null, Icons.stroke(Icons.CLOSE, 12, "-nf-muted"));
        close.getStyleClass().setAll("button", "sheet-close");
        close.setAccessibleText("Fermer le centre de notifications");
        close.setOnAction(e -> onClose.run());
        HBox head = new HBox(10, Ui.label("Notifications", "title1"), Ui.spacer(), close);
        head.setAlignment(Pos.CENTER_LEFT);

        Controls.Segmented filter = new Controls.Segmented(List.of("Toutes", "Non lues"), 0, i -> {
            unreadOnly = i == 1;
            render();
        });
        Button markAll = new Button("Tout marquer comme lu");
        markAll.getStyleClass().add("btn-plain");
        markAll.setOnAction(e -> {
            gateway.markNotificationsRead(items.stream().map(NotificationItem::id).toList());
            reload();
        });
        HBox tools = new HBox(10, filter, Ui.spacer(), markAll);
        tools.setAlignment(Pos.CENTER_LEFT);

        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(sp, Priority.ALWAYS);
        getChildren().addAll(head, tools, sp);
        reload();
    }

    private void reload() {
        list.getChildren().setAll(Ui.skeleton(-1, 64), Ui.skeleton(-1, 64), Ui.skeleton(-1, 64));
        Ui.async(gateway::notifications, n -> {
            items = n.stream().filter(x -> Preferences.notificationsEnabled(x.kind())).toList();
            render();
            onChanged.run();
        }, err -> list.getChildren().setAll(Ui.errorBanner(err, this::reload)));
    }

    private void render() {
        list.getChildren().clear();
        List<NotificationItem> shown = items.stream().filter(n -> !unreadOnly || !n.read()).toList();
        if (shown.isEmpty()) {
            list.getChildren().add(Ui.statePanel(Icons.BELL, unreadOnly ? "Tout est lu" : "Aucune notification",
                    "Les échéances et alertes du parc apparaîtront ici."));
            return;
        }
        LocalDate today = LocalDate.now(DOUALA);
        String[] titles = {"Aujourd'hui", "Hier", "Plus tôt"};
        List<Node> cards = new ArrayList<>();
        for (int g = 0; g < 3; g++) {
            int group = g;
            List<NotificationItem> part = shown.stream().filter(n -> {
                LocalDate d = n.at().atZone(DOUALA).toLocalDate();
                int k = d.equals(today) ? 0 : d.equals(today.minusDays(1)) ? 1 : 2;
                return k == group;
            }).toList();
            if (part.isEmpty()) continue;
            Label t = Ui.label(titles[g], "title3");
            t.setStyle("-fx-padding: 8 0 2 4;");
            list.getChildren().add(t);
            for (NotificationItem n : part) {
                Node c = card(n);
                cards.add(c);
                list.getChildren().add(c);
            }
        }
        Motion.stagger(cards);
    }

    private Node card(NotificationItem n) {
        String[] look = switch (n.kind()) {
            case ECHEANCE -> new String[]{Icons.CALENDAR, "tile-orange"};
            case ENTRETIEN -> new String[]{Icons.WRENCH, "tile-red"};
            case CARBURANT -> new String[]{Icons.FUEL, "tile-blue"};
            case SYSTEME -> new String[]{Icons.SHIELD, "tile-gray"};
        };
        Label title = Ui.label(n.title(), "headline");
        Label time = Ui.label(Ui.relativeTime(n.at(), Instant.now()), "nc-time");
        time.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox head = new HBox(8, title, Ui.spacer(), time);
        head.setAlignment(Pos.BASELINE_LEFT);
        if (!n.read()) {
            Circle dot = new Circle(4);
            dot.getStyleClass().add("unread-dot");
            head.getChildren().add(dot);
        }
        Label body = Ui.label(n.body(), "subhead", "muted");
        body.setWrapText(true);
        VBox text = new VBox(3, head, body);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox card = new HBox(12, Ui.symbolTile(look[0], look[1]), text);
        card.getStyleClass().add("nc-card");
        card.setAccessibleText((n.read() ? "" : "Non lue. ") + n.title() + ". " + n.body());
        card.setOnMouseClicked(e -> {
            gateway.markNotificationsRead(List.of(n.id()));
            onChanged.run();
            onOpen.accept(n);
        });
        Motion.pressable(card);
        return card;
    }

    static long unread(List<NotificationItem> items) {
        return items.stream().filter(n -> !n.read() && Preferences.notificationsEnabled(n.kind())).count();
    }

}
