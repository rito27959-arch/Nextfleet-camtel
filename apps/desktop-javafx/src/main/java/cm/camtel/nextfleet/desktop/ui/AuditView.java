package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.AuditChain;
import cm.camtel.nextfleet.desktop.data.Ops.AuditEntry;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Journal d'audit (auditeur, lecture seule) : événements chaînés SHA-256, vérification de la chaîne recalculée sur le
 * poste, filtres par domaine. Le contenu des messages n'est jamais journalisé, seulement le fait qu'un message existe.
 */
final class AuditView extends ScrollPane {

    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("d MMM · HH:mm:ss", Locale.FRANCE).withZone(ZoneId.of("Africa/Douala"));
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("LOGIN", "Connexion"), Map.entry("LOGOUT", "Déconnexion"),
            Map.entry("VEHICLE_CREATED", "Véhicule créé"), Map.entry("VEHICLE_UPDATED", "Véhicule modifié"),
            Map.entry("VEHICLE_ARCHIVED", "Véhicule archivé"), Map.entry("MISSION_AUTHORIZED", "Mission autorisée"),
            Map.entry("MISSION_STARTED", "Mission démarrée"), Map.entry("MISSION_FINISHED", "Mission clôturée"),
            Map.entry("MESSAGE_SENT", "Message envoyé"), Map.entry("ASSISTANT_ENABLED", "Assistant activé"),
            Map.entry("ASSISTANT_DISABLED", "Assistant désactivé"), Map.entry("ASSISTANT_ANSWERED", "Question à l'assistant"),
            Map.entry("ASSISTANT_REFUSED", "Question refusée"));

    private final AppShell shell;
    private final VBox col = new VBox(18);
    private final VBox rows = new VBox();
    private List<AuditEntry> entries = List.of();
    private int filter;

    AuditView(AppShell shell) {
        this.shell = shell;
        setFitToWidth(true);
        col.getStyleClass().add("content");
        col.setMaxWidth(1100);
        StackPane center = new StackPane(col);
        center.setAlignment(Pos.TOP_CENTER);
        setContent(center);
        Label sub = Ui.label("Lecture seule. Chaque événement est chaîné au précédent : toute modification casse la chaîne.", "body", "muted");
        sub.setWrapText(true);
        Button export = new Button("Exporter", Icons.stroke(Icons.DOWNLOAD, 15, "-nf-muted"));
        export.getStyleClass().add("btn-gray");
        export.setDisable(true);
        StackPane exportWrap = new StackPane(export);
        Tooltip.install(exportWrap, new Tooltip("Export signé du journal : lot 3 (NF-25)."));
        HBox title = new HBox(new VBox(6, Ui.label("Journal d'audit", "large-title"), sub), Ui.spacer(), exportWrap);
        title.setAlignment(Pos.TOP_LEFT);
        Controls.Segmented seg = new Controls.Segmented(List.of("Tout", "Missions et GPS", "Messagerie", "Accès et IA", "Parc"), 0, i -> {
            filter = i;
            render();
        });
        rows.getStyleClass().add("inset-group");
        col.getChildren().addAll(title, Ui.skeleton(-1, 44), seg, rows);
        Ui.async(() -> shell.gateway().auditTrail(), list -> {
            entries = list;
            long broken = AuditChain.firstBroken(list);
            Label l = Ui.label(broken < 0 ? "Chaîne intègre · " + list.size() + " événements vérifiés (SHA-256)"
                    : "Chaîne rompue à l'événement n° " + broken, broken < 0 ? "" : "error-text");
            HBox banner = new HBox(10, Icons.stroke(broken < 0 ? Icons.CHAIN : Icons.CLOSE, 18, broken < 0 ? "-nf-green-text" : "-nf-red-text"), l);
            banner.getStyleClass().add(broken < 0 ? "chain-ok" : "banner-error");
            col.getChildren().set(1, banner);
            render();
        }, err -> col.getChildren().set(1, Ui.errorBanner(err, () -> shell.show(AppShell.Section.AUDIT))));
    }

    private boolean matches(AuditEntry e) {
        return switch (filter) {
            case 1 -> e.action().startsWith("MISSION");
            case 2 -> e.action().startsWith("MESSAGE");
            case 3 -> e.action().startsWith("ASSISTANT") || e.action().startsWith("LOG");
            case 4 -> e.action().startsWith("VEHICLE");
            default -> true;
        };
    }

    private void render() {
        List<Node> list = new ArrayList<>();
        for (AuditEntry e : entries) {
            if (!matches(e)) continue;
            if (!list.isEmpty()) {
                Region sep = new Region();
                sep.getStyleClass().add("row-separator");
                list.add(sep);
            }
            list.add(row(e));
        }
        if (list.isEmpty()) list.add(Ui.statePanel(Icons.LIST, "Aucun événement", "Aucun événement pour ce filtre."));
        rows.getChildren().setAll(list);
    }

    private Node row(AuditEntry e) {
        String icon = e.action().startsWith("MISSION") ? Icons.ROUTE : e.action().startsWith("MESSAGE") ? Icons.BUBBLE
                : e.action().startsWith("ASSISTANT") ? Icons.SPARK : e.action().startsWith("VEHICLE") ? Icons.CAR : Icons.LOCK;
        String tile = e.action().startsWith("MISSION") ? "tile-blue" : e.action().startsWith("MESSAGE") ? "tile-green"
                : e.action().startsWith("ASSISTANT") ? "tile-mint" : e.action().startsWith("VEHICLE") ? "tile-orange" : "tile-gray";
        Label action = Ui.label(LABELS.getOrDefault(e.action(), e.action()) + " · " + e.object(), "audit-action");
        Label detail = Ui.label(e.actor() + " — " + e.detail(), "footnote", "muted");
        detail.setWrapText(true);
        VBox text = new VBox(2, action, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        Label hash = Ui.label("n° " + e.seq() + " · " + e.hash().substring(0, 8) + "…" + e.hash().substring(56), "mono");
        Tooltip.install(hash, new Tooltip("Empreinte : " + e.hash() + "\nPrécédente : " + e.previousHash()));
        hash.setMinWidth(Region.USE_PREF_SIZE);
        Label time = Ui.label(T.format(e.at()), "footnote", "muted");
        time.setMinWidth(Region.USE_PREF_SIZE);
        VBox right = new VBox(2, time, hash);
        right.setAlignment(Pos.CENTER_RIGHT);
        HBox r = new HBox(14, Ui.symbolTile(icon, tile), text, right);
        r.getStyleClass().add("audit-row");
        r.setAccessibleText(action.getText() + ", " + detail.getText() + ", " + time.getText());
        return r;
    }
}
