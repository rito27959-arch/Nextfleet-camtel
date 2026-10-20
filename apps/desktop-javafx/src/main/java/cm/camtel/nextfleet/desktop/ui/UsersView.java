package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Ops.Grant;
import cm.camtel.nextfleet.desktop.data.Ops.UserAccount;
import cm.camtel.nextfleet.domain.assistant.AssistantActivation;
import cm.camtel.nextfleet.domain.security.Permission;
import cm.camtel.nextfleet.domain.security.Role;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Espace de l'administrateur technique : comptes, rôles et périmètres, matrice des habilitations, activation de
 * l'assistant. Aucune donnée métier (parc, coûts, trajets, messages) n'est accessible depuis ce profil.
 */
final class UsersView extends ScrollPane {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRANCE);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.FRANCE)
            .withZone(java.time.ZoneId.of("Africa/Douala"));
    private final AppShell shell;
    private final VBox col = new VBox(22);
    private final VBox tab = new VBox(18);
    private List<UserAccount> users = List.of();
    private int current;
    private Controls.Segmented seg;

    UsersView(AppShell shell) {
        this.shell = shell;
        setFitToWidth(true);
        col.getStyleClass().add("content");
        col.setMaxWidth(1100);
        StackPane center = new StackPane(col);
        center.setAlignment(Pos.TOP_CENTER);
        setContent(center);

        Label sub = Ui.label("Administration technique : comptes, rôles et périmètres. Ce profil n'a accès à aucune donnée métier.", "body", "muted");
        sub.setWrapText(true);
        seg = new Controls.Segmented(List.of("Comptes", "Matrice des habilitations", "Assistant IA"), 0, this::render);
        col.getChildren().addAll(new VBox(6, Ui.label("Comptes et rôles", "large-title"), sub), Ui.skeleton(-1, 110), seg, tab);
        Ui.async(() -> shell.gateway().users(), list -> {
            users = list;
            col.getChildren().set(1, roleTiles(list));
            render(current);
        }, err -> col.getChildren().set(1, Ui.errorBanner(err, () -> shell.show(AppShell.Section.USERS))));
    }

    /** Pour la recette visuelle : comme un clic sur l'onglet. */
    public void showTab(int i) {
        seg.select(i);
    }

    private void render(int i) {
        current = i;
        switch (i) {
            case 0 -> tab.getChildren().setAll(accounts());
            case 1 -> tab.getChildren().setAll(matrix());
            default -> assistantTab();
        }
        Motion.stagger(tab.getChildren());
    }

    private Node roleTiles(List<UserAccount> list) {
        HBox flow = new HBox(12);
        for (Role r : Role.values()) {
            long n = list.stream().filter(u -> u.grants().stream().anyMatch(g -> g.role() == r)).count();
            Label count = Ui.label(String.valueOf(n), "stat-value");
            Label name = Ui.label(r.label, "headline");
            name.setWrapText(true);
            HBox top = new HBox(10, RoleUi.symbol(r), Ui.spacer(), count);
            top.setAlignment(Pos.CENTER_LEFT);
            VBox t = new VBox(8, top, name, Ui.label(n > 1 ? "comptes" : "compte", "footnote", "muted"));
            t.getStyleClass().add("role-tile");
            t.setMinWidth(0);
            t.setPrefWidth(0);
            t.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(t, Priority.ALWAYS);
            Tooltip.install(t, new Tooltip(r.summary));
            flow.getChildren().add(t);
        }
        return flow;
    }

    private List<Node> accounts() {
        List<Node> rows = new ArrayList<>();
        int i = 0;
        for (UserAccount u : users) {
            StackPane av = Ui.avatar(initials(u.displayName()), i++);
            VBox who = new VBox(1, Ui.label(u.displayName(), "headline"), Ui.label(u.login() + " · " + u.orgUnit(), "footnote", "muted"));
            who.setMinWidth(220);
            FlowPane pills = new FlowPane(6, 4);
            for (Grant g : u.grants()) {
                Label p = Ui.label(g.role().label + (g.delegation() ? " · délégation jusqu'au " + D.format(g.until()) : ""), "role-pill");
                if (g.delegation()) p.getStyleClass().add("delegation");
                Tooltip.install(p, new Tooltip("Périmètre : " + g.scope()));
                pills.getChildren().add(p);
            }
            HBox.setHgrow(pills, Priority.ALWAYS);
            Label status = "ACTIVE".equals(u.status()) ? Ui.label("Actif", "pill", "pill-ok") : Ui.label("Suspendu", "pill", "pill-warning");
            status.setMinWidth(Region.USE_PREF_SIZE);
            Label seen = Ui.label(Ui.relativeTime(u.lastSeen(), Instant.now()), "footnote", "muted");
            seen.setMinWidth(90);
            pills.setAlignment(Pos.CENTER_LEFT);
            HBox r = new HBox(14, av, who, pills, status, seen);
            r.setAlignment(Pos.CENTER_LEFT);
            r.setFillHeight(false);
            r.getStyleClass().add("list-row");
            r.setStyle("-fx-min-height: 64;");
            r.setAccessibleText(u.displayName() + ", " + u.grants().stream().map(g -> g.role().label).toList() + ", " + status.getText());
            rows.add(r);
        }
        Button create = new Button("Nouveau compte", Icons.stroke(Icons.PLUS, 15, "white"));
        create.getStyleClass().add("btn-filled");
        create.setDisable(true);
        Tooltip.install(create, new Tooltip("Création de compte : non exposée par l'API (fédération OIDC / RH prévue) ; suspension et réactivation disponibles."));
        StackPane wrap = new StackPane(create);   // infobulle visible même bouton inactif
        Tooltip.install(wrap, new Tooltip("Création de compte : non exposée par l'API (fédération OIDC / RH prévue) ; suspension et réactivation disponibles."));
        HBox bar = new HBox(Ui.label(users.size() + " comptes", "title3"), Ui.spacer(), wrap);
        bar.setAlignment(Pos.CENTER_LEFT);
        return List.of(bar, Ui.insetGroup(null, rows,
                "Règles appliquées par la base : aucune auto-attribution de rôle, délégation toujours bornée dans le temps, "
                        + "suspension = révocation immédiate des sessions. Création et modification : API du lot 2."));
    }

    /** Matrice dérivée du noyau métier (identique à la base, vérifiée par test). */
    private List<Node> matrix() {
        GridPane g = new GridPane();
        g.setHgap(0);
        g.setVgap(0);
        ColumnConstraints first = new ColumnConstraints(280);
        g.getColumnConstraints().add(first);
        Role[] roles = Role.values();
        for (int c = 0; c < roles.length; c++) {
            ColumnConstraints cc = new ColumnConstraints(110);
            cc.setHalignment(HPos.CENTER);
            g.getColumnConstraints().add(cc);
            Label head = Ui.label(roles[c].label, "matrix-head");
            head.setWrapText(true);
            head.setMaxWidth(100);
            head.setStyle("-fx-text-alignment: center;");
            VBox h = new VBox(6, RoleUi.symbol(roles[c]), head);
            h.setAlignment(Pos.CENTER);
            h.setPadding(new Insets(12, 4, 12, 4));
            g.add(h, c + 1, 0);
        }
        int row = 1;
        for (Permission p : Permission.values()) {
            VBox label = new VBox(1, Ui.label(p.label, "matrix-perm"), Ui.label(p.code, "mono"));
            label.setPadding(new Insets(6, 14, 6, 14));
            g.add(label, 0, row);
            for (int c = 0; c < roles.length; c++) {
                StackPane cell = new StackPane();
                cell.getStyleClass().add("matrix-cell");
                if (roles[c].has(p)) cell.getChildren().add(Icons.stroke(Icons.CHECK, 16, "-nf-accent-text"));
                cell.setAccessibleText(roles[c].label + (roles[c].has(p) ? " peut " : " ne peut pas ") + p.label.toLowerCase(Locale.FRANCE));
                g.add(cell, c + 1, row);
            }
            if (row % 2 == 0) {
                Region band = new Region();
                band.setStyle("-fx-background-color: -nf-surface-2;");
                g.add(band, 0, row, roles.length + 1, 1);
                band.toBack();
            }
            row++;
        }
        VBox box = new VBox(g);
        box.getStyleClass().add("inset-group");
        box.setPadding(new Insets(0, 0, 8, 0));
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToHeight(true);
        sp.setVbarPolicy(ScrollBarPolicy.NEVER);
        Label foot = Ui.label("Matrice du cahier V4 (chap. 03). L'interface masque ce qui n'est pas permis ; le serveur et la base "
                + "refusent quand même toute action hors droits.", "section-footer");
        foot.setWrapText(true);
        return List.of(sp, foot);
    }

    private void assistantTab() {
        tab.getChildren().setAll(Ui.skeleton(-1, 120));
        Ui.async(() -> shell.gateway().assistantDecisions(), decisions -> {
            AssistantActivation.State st = decisions.isEmpty() ? AssistantActivation.State.disabledByDefault()
                    : decisions.get(decisions.size() - 1);
            List<Node> nodes = new ArrayList<>();
            Label state = st.enabled() ? Ui.label("Activé", "pill", "pill-ok") : Ui.label("Désactivé", "pill", "pill-neutral");
            nodes.add(Ui.insetGroup("État actuel", List.of(
                    Ui.listRow(Ui.symbolTile(Icons.SPARK, st.enabled() ? "tile-mint" : "tile-gray"), "Assistant IA (lecture seule)", null, state, null),
                    Ui.listRow(Ui.symbolTile(Icons.SHIELD, "tile-indigo"), "Validation de sécurité",
                            st.securityReviewRef() == null ? "—" : st.securityReviewRef() + " · " + st.approvedBy(), null, null),
                    Ui.listRow(Ui.symbolTile(Icons.CLOCK, "tile-gray"), "Dernière décision",
                            st.decidedAt() == null ? "Aucune" : DT.format(st.decidedAt()), null, null)),
                    "Désactivé par défaut. Activer exige une référence de validation et un signataire distinct de l'administrateur."));

            Controls.Switch enable = new Controls.Switch(!st.enabled(), "Activer l'assistant", on -> { });
            TextField ref = new TextField();
            ref.setPromptText("Ex. DS-2026-041");
            TextField approver = new TextField();
            approver.setPromptText("Nom du signataire (DS ou RSSI)");
            TextField reason = new TextField();
            reason.setPromptText("Motif de la décision");
            Label error = Ui.label("", "error-text");
            error.setWrapText(true);
            Button save = new Button("Enregistrer la décision");
            save.getStyleClass().add("btn-filled");
            save.setOnAction(e -> {
                error.setText("");
                save.setDisable(true);
                Ui.async(() -> shell.gateway().decideAssistant(enable.isOn(), ref.getText(), approver.getText(), reason.getText()), s -> {
                    shell.toasts().success(s.enabled() ? "Assistant activé" : "Assistant désactivé", "Décision journalisée.");
                    assistantTab();
                }, err -> {
                    save.setDisable(false);
                    error.setText(err.getMessage());
                });
            });
            Motion.pressable(save);
            VBox form = new VBox(10,
                    row("Activer", enable), field("Référence de validation", ref), field("Signataire", approver), field("Motif", reason),
                    error, save);
            form.getStyleClass().add("card");
            nodes.add(new VBox(6, Ui.label("NOUVELLE DÉCISION", "section-header"), form));

            List<Node> hist = new ArrayList<>();
            for (int i = decisions.size() - 1; i >= 0; i--) {
                AssistantActivation.State d = decisions.get(i);
                hist.add(Ui.listRow(Ui.symbolTile(d.enabled() ? Icons.CHECK : Icons.CLOSE, d.enabled() ? "tile-green" : "tile-gray"),
                        (d.enabled() ? "Activation · " + d.securityReviewRef() : "Désactivation") + " — " + d.reason(),
                        DT.format(d.decidedAt()), null, null));
            }
            if (!hist.isEmpty()) nodes.add(Ui.insetGroup("Historique des décisions (ajout seul)", hist, null));
            tab.getChildren().setAll(nodes);
            Motion.stagger(tab.getChildren());
        }, err -> tab.getChildren().setAll(Ui.errorBanner(err, this::assistantTab)));
    }

    private static Node row(String label, Node control) {
        HBox h = new HBox(Ui.label(label, "row-title"), Ui.spacer(), control);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Node field(String label, TextField f) {
        f.setAccessibleText(label);
        f.setMaxWidth(Double.MAX_VALUE);
        Label l = Ui.label(label, "field-label");
        l.setLabelFor(f);
        return new VBox(4, l, f);
    }

    private static String initials(String name) {
        String[] p = name.trim().split("\\s+");
        return ((p.length > 0 ? p[0].substring(0, 1) : "") + (p.length > 1 ? p[p.length - 1].substring(0, 1) : "")).toUpperCase(Locale.FRANCE);
    }
}
