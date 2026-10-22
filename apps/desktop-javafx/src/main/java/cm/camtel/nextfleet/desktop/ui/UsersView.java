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
            if (!shell.gateway().isDemo()) {
                r.getStyleClass().add("clickable");
                r.setOnMouseClicked(e -> accountSheet(u));
            }
            rows.add(r);
        }
        Button create = Views.filled("Nouveau compte", Icons.PLUS, this::createSheet);
        create.setDisable(shell.gateway().isDemo());
        Button unit = Views.tinted("Nouvelle entité", Icons.PLUS, this::orgSheet);
        unit.setDisable(shell.gateway().isDemo() || !shell.session().can(Permission.ADMIN_SETTINGS));
        HBox bar = new HBox(10, Ui.label(users.size() + " comptes", "title3"), Ui.spacer(), unit, create);
        bar.setAlignment(Pos.CENTER_LEFT);
        return List.of(bar, Ui.insetGroup(null, rows,
                "Règles appliquées par la base : aucune auto-attribution de rôle, délégation toujours bornée dans le temps, "
                        + "suspension = révocation immédiate des sessions. Touchez un compte pour gérer ses rôles et son mot de passe."));
    }

    private void reload() {
        Ui.async(() -> shell.gateway().users(), list -> {
            users = list;
            col.getChildren().set(1, roleTiles(list));
            render(current);
        }, err -> shell.toasts().info("Actualisation impossible", err.getMessage()));
    }

    /** Création : mot de passe provisoire affiché une seule fois, changement imposé à la première connexion. */
    private void createSheet() {
        Ui.async(() -> new Object[]{shell.gateway().orgUnits("admin:users"), shell.gateway().roles()}, r -> {
            @SuppressWarnings("unchecked") List<cm.camtel.nextfleet.desktop.data.Prod.OrgUnit> units = (List<cm.camtel.nextfleet.desktop.data.Prod.OrgUnit>) r[0];
            @SuppressWarnings("unchecked") List<cm.camtel.nextfleet.desktop.data.Prod.RoleInfo> roles = (List<cm.camtel.nextfleet.desktop.data.Prod.RoleInfo>) r[1];
            var login = FormSheet.text("Ex. f.kamga");
            var name = FormSheet.text("Prénom et nom");
            var email = FormSheet.text("Facultatif");
            var phone = FormSheet.text("Facultatif");
            var org = FormSheet.combo(units);
            var role = FormSheet.combo(roles);
            var staff = FormSheet.text("Matricule (facultatif) : fiche existante ou créée avec le compte");
            FormSheet f = new FormSheet("Nouveau compte", "Créer", shell::closeSheet)
                    .intro("Un mot de passe provisoire est généré ; il ne s'affiche qu'une fois. La personne le remplace à sa première connexion.")
                    .row("Identifiant *", login, "Nom affiché *", name).row("E-mail", email, "Téléphone", phone)
                    .field("Entité *", org).field("Rôle *", role).field("Matricule conducteur", staff)
                    .footer("Rôle Conducteur : la fiche conducteur est créée avec le compte (ou rattachée si ce matricule existe déjà). "
                            + "Il pourra recevoir des missions immédiatement.");
            f.onSubmit(() -> shell.gateway().createAccount(new cm.camtel.nextfleet.desktop.data.Prod.AccountDraft(FormSheet.req(login, "Identifiant"),
                    FormSheet.req(name, "Nom affiché"), FormSheet.opt(email), FormSheet.opt(phone), FormSheet.pick(org, "Entité").id(),
                    FormSheet.pick(role, "Rôle").code(), FormSheet.opt(staff))), (cm.camtel.nextfleet.desktop.data.Prod.CreatedAccount c) -> {
                shell.closeSheet();
                showTemporaryPassword("Compte créé", c.login(), c.temporaryPassword());
                reload();
            });
            shell.openSheet(f, 500);
        }, err -> shell.toasts().info("Création impossible", err.getMessage()));
    }

    private void showTemporaryPassword(String title, String login, String temp) {
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(temp);
        AlertDialog.show(shell, Ui.symbolTile(Icons.KEY, "tile-green"), title,
                "Identifiant : " + login + "\nMot de passe provisoire : " + temp
                        + "\n\nRemettez-le en main propre ou par un canal sûr ; il ne sera plus affiché.",
                List.of(AlertDialog.Action.normal("Copier le mot de passe", () -> javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc)),
                        AlertDialog.Action.preferred("Terminé", () -> { })));
    }

    private void accountSheet(UserAccount u) {
        Ui.async(() -> shell.gateway().grants(u.id()), grants -> {
            VBox box = new VBox(14);
            box.getStyleClass().add("sheet");
            Button close = new Button("Fermer");
            close.getStyleClass().add("btn-plain");
            close.setCancelButton(true);
            close.setOnAction(e -> shell.closeSheet());
            StackPane bar = new StackPane(Ui.label(u.displayName(), "headline"), close);
            StackPane.setAlignment(close, Pos.CENTER_LEFT);
            bar.setMinHeight(40);
            Label sub = Ui.label(u.login() + " · " + u.orgUnit() + " · " + ("ACTIVE".equals(u.status()) ? "actif" : "suspendu"), "footnote", "muted");
            List<Node> rows = new ArrayList<>();
            for (cm.camtel.nextfleet.desktop.data.Prod.RoleGrant g : grants) {
                String detail = g.scopeName() + (g.validUntil() != null ? " · jusqu'au " + D.format(g.validUntil().atZone(java.time.ZoneId.of("Africa/Douala"))) : "")
                        + (g.revokedAt() != null ? " · retiré" : "");
                Node accessory = null;
                if (g.active()) {
                    Button revoke = new Button("Retirer");
                    revoke.getStyleClass().addAll("btn-plain", "destructive");
                    revoke.setOnAction(e -> revokeSheet(u, g));
                    accessory = revoke;
                }
                rows.add(Views.row(null, g.roleLabel(), detail, accessory, null));
            }
            Button grant = Views.sheetAction("Attribuer un rôle", "btn-tinted", () -> grantSheet(u));
            Button reset = Views.sheetAction("Réinitialiser le mot de passe", "btn-tinted", () -> resetSheet(u));
            boolean active = "ACTIVE".equals(u.status());
            Button status = Views.sheetAction(active ? "Suspendre le compte" : "Réactiver le compte", active ? "btn-destructive" : "btn-tinted",
                    () -> statusSheet(u, !active));
            for (Button b : List.of(grant, reset, status)) b.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().addAll(bar, sub, Ui.insetGroup("Rôles et périmètres", rows.isEmpty() ? List.of(Ui.listRow(null, "Aucun rôle", null, null, null)) : rows,
                    "Un rôle retiré prend effet immédiatement, y compris sur les sessions ouvertes."));
            // fiche conducteur : un compte sans fiche ne peut recevoir aucune mission
            Node driverRow;
            List<Button> driverActions = new ArrayList<>();
            if (u.driverStaffNumber() != null) {
                Button detach = new Button("Détacher");
                detach.getStyleClass().addAll("btn-plain", "destructive");
                detach.setOnAction(e -> detachSheet(u));
                driverRow = Views.row(Ui.symbolTile(Icons.USER, "tile-green"), "Matricule " + u.driverStaffNumber(),
                        "Peut recevoir et démarrer des missions", detach, null);
            } else {
                driverRow = Views.row(Ui.symbolTile(Icons.USER, "tile-orange"), "Aucune fiche conducteur",
                        "Ce compte ne peut pas recevoir de mission", null, null);
                driverActions.add(Views.sheetAction("Créer sa fiche conducteur", "btn-tinted", () -> attachSheet(u, false)));
                driverActions.add(Views.sheetAction("Lier à une fiche existante", "btn-tinted", () -> attachSheet(u, true)));
            }
            box.getChildren().add(Ui.insetGroup("Fiche conducteur", List.of(driverRow),
                    u.driverStaffNumber() != null ? null : "La fiche reprend le nom, le téléphone et l'entité du compte : rien n'est à inventer."));
            for (Button b : driverActions) b.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().addAll(driverActions);
            box.getChildren().addAll(grant, reset, status);
            ScrollPane sp = new ScrollPane(box);
            sp.setFitToWidth(true);
            shell.openSheet(sp, 460);
        }, err -> shell.toasts().info("Compte inaccessible", err.getMessage()));
    }

    private void grantSheet(UserAccount u) {
        Ui.async(() -> new Object[]{shell.gateway().orgUnits("admin:users"), shell.gateway().roles()}, r -> {
            @SuppressWarnings("unchecked") List<cm.camtel.nextfleet.desktop.data.Prod.OrgUnit> units = (List<cm.camtel.nextfleet.desktop.data.Prod.OrgUnit>) r[0];
            @SuppressWarnings("unchecked") List<cm.camtel.nextfleet.desktop.data.Prod.RoleInfo> roles = (List<cm.camtel.nextfleet.desktop.data.Prod.RoleInfo>) r[1];
            var role = FormSheet.combo(roles);
            var org = FormSheet.combo(units);
            var until = FormSheet.date(null);
            FormSheet f = new FormSheet("Attribuer un rôle", "Attribuer", shell::closeSheet)
                    .intro(u.displayName() + ". Une date de fin fait de l'attribution une délégation temporaire.")
                    .field("Rôle *", role).field("Périmètre *", org).field("Jusqu'au (délégation)", until);
            f.onSubmit(() -> {
                java.time.Instant end = until.getValue() == null ? null
                        : until.getValue().plusDays(1).atStartOfDay(java.time.ZoneId.of("Africa/Douala")).toInstant();
                shell.gateway().grantRole(u.id(), FormSheet.pick(role, "Rôle").code(), FormSheet.pick(org, "Périmètre").id(), end);
                return FormSheet.pick(role, "Rôle").label();
            }, (String label) -> {
                shell.closeSheet();
                shell.toasts().success("Rôle attribué", label + " · " + u.displayName());
                reload();
            });
            shell.openSheet(f, 440);
        }, err -> shell.toasts().info("Attribution impossible", err.getMessage()));
    }

    private void revokeSheet(UserAccount u, cm.camtel.nextfleet.desktop.data.Prod.RoleGrant g) {
        var reason = FormSheet.area("Ex. Changement d'affectation");
        FormSheet f = new FormSheet("Retirer le rôle", "Retirer", shell::closeSheet).intro(g.roleLabel() + " · " + g.scopeName() + " · " + u.displayName())
                .field("Motif *", reason);
        f.onSubmit(() -> {
            if (reason.getText().strip().length() < 5) throw new IllegalArgumentException("Motif : 5 caractères au moins.");
            shell.gateway().revokeGrant(u.id(), g.id(), reason.getText().strip());
            return true;
        }, ok -> {
            shell.closeSheet();
            shell.toasts().success("Rôle retiré", g.roleLabel() + " · " + u.displayName());
            reload();
        });
        shell.openSheet(f, 440);
    }

    /**
     * Rattache le compte à une fiche conducteur. « Créer » : fiche faite à partir du compte, seul le matricule CAMTEL
     * est demandé (facultatif). « Lier » : choix parmi les fiches existantes sans compte.
     */
    private void attachSheet(UserAccount u, boolean existing) {
        if (!existing) {
            var staff = FormSheet.text("Matricule CAMTEL (facultatif)");
            FormSheet f = new FormSheet("Fiche conducteur", "Créer", shell::closeSheet)
                    .intro("La fiche de " + u.displayName() + " reprend le nom, le téléphone, l'e-mail et l'entité du compte. "
                            + "Le rôle Conducteur est ajouté s'il manque. Permis et pièce d'identité se complètent ensuite dans Conducteurs.")
                    .field("Matricule", staff)
                    .footer("Sans matricule, l'identifiant du compte sert de matricule provisoire (modifiable ensuite).");
            f.onSubmit(() -> shell.gateway().attachDriver(u.id(), null, FormSheet.opt(staff)), (cm.camtel.nextfleet.desktop.data.Prod.DriverLink l) -> {
                shell.closeSheet();
                shell.toasts().success("Fiche conducteur créée", l.fullName() + " · matricule " + l.staffNumber() + " : peut recevoir des missions.");
                reload();
            });
            shell.openSheet(f, 460);
            return;
        }
        Ui.async(() -> shell.gateway().drivers(""), drivers -> {
            List<cm.camtel.nextfleet.desktop.data.Prod.DriverRef> free = drivers.stream().filter(d -> d.userLogin() == null)
                    .map(d -> new cm.camtel.nextfleet.desktop.data.Prod.DriverRef(d.id(), d.fullName(), d.staffNumber(), false)).toList();
            if (free.isEmpty()) {
                shell.toasts().info("Aucune fiche libre", "Toutes les fiches conducteur ont déjà un compte. Créez plutôt la fiche à partir du compte.");
                return;
            }
            var pick = FormSheet.combo(free);
            FormSheet f = new FormSheet("Lier à une fiche", "Lier", shell::closeSheet)
                    .intro("Fiches conducteur existantes sans compte. Une fois liée, " + u.displayName() + " verra ses missions sur son téléphone.")
                    .field("Fiche *", pick);
            f.onSubmit(() -> shell.gateway().attachDriver(u.id(), FormSheet.pick(pick, "Fiche").id(), null),
                    (cm.camtel.nextfleet.desktop.data.Prod.DriverLink l) -> {
                        shell.closeSheet();
                        shell.toasts().success("Fiche liée", l.fullName() + " · " + u.login());
                        reload();
                    });
            shell.openSheet(f, 460);
        }, err -> shell.toasts().info("Fiches inaccessibles", err.getMessage()));
    }

    private void detachSheet(UserAccount u) {
        var reason = FormSheet.area("Ex. Mauvaise personne rattachée");
        FormSheet f = new FormSheet("Détacher la fiche", "Détacher", shell::closeSheet)
                .intro("La fiche " + u.driverStaffNumber() + " est conservée mais ne sera plus liée à " + u.displayName()
                        + ". Impossible si une mission autorisée ou en cours lui est confiée.")
                .field("Motif *", reason);
        f.onSubmit(() -> {
            if (reason.getText().strip().length() < 5) throw new IllegalArgumentException("Motif : 5 caractères au moins.");
            shell.gateway().detachDriver(u.id(), reason.getText().strip());
            return true;
        }, ok -> {
            shell.closeSheet();
            shell.toasts().success("Fiche détachée", u.displayName());
            reload();
        });
        shell.openSheet(f, 440);
    }

    private void resetSheet(UserAccount u) {
        var reason = FormSheet.area("Ex. Oubli signalé au support le 21/10");
        FormSheet f = new FormSheet("Réinitialiser", "Réinitialiser", shell::closeSheet)
                .intro(u.displayName() + " : ses sessions ouvertes sont fermées et un nouveau mot de passe provisoire est généré.")
                .field("Motif *", reason);
        f.onSubmit(() -> {
            if (reason.getText().strip().length() < 5) throw new IllegalArgumentException("Motif : 5 caractères au moins.");
            return shell.gateway().resetPassword(u.id(), reason.getText().strip());
        }, (String temp) -> {
            shell.closeSheet();
            showTemporaryPassword("Mot de passe réinitialisé", u.login(), temp);
        });
        shell.openSheet(f, 440);
    }

    private void statusSheet(UserAccount u, boolean activate) {
        var reason = FormSheet.area(activate ? "Ex. Retour de congé" : "Ex. Départ de l'entreprise");
        FormSheet f = new FormSheet(activate ? "Réactiver le compte" : "Suspendre le compte", "Confirmer", shell::closeSheet)
                .intro(u.displayName() + (activate ? "" : " : ses sessions sont révoquées immédiatement."))
                .field("Motif *", reason);
        f.onSubmit(() -> {
            if (reason.getText().strip().length() < 5) throw new IllegalArgumentException("Motif : 5 caractères au moins.");
            shell.gateway().setAccountStatus(u.id(), activate, reason.getText().strip());
            return true;
        }, ok -> {
            shell.closeSheet();
            shell.toasts().success(activate ? "Compte réactivé" : "Compte suspendu", u.displayName());
            reload();
        });
        shell.openSheet(f, 440);
    }

    private void orgSheet() {
        Ui.async(() -> shell.gateway().orgUnits("admin:settings"), units -> {
            var name = FormSheet.text("Ex. Direction régionale du Littoral");
            var code = FormSheet.text("Ex. DR-LITTORAL");
            var parent = FormSheet.combo(units);
            FormSheet f = new FormSheet("Nouvelle entité", "Créer", shell::closeSheet)
                    .intro("Directions, agences ou sites : les droits sont attribués par entité, et s'étendent à ses sous-entités.")
                    .field("Nom *", name).field("Code *", code).field("Rattachée à *", parent);
            f.onSubmit(() -> {
                String c = FormSheet.req(code, "Code").toUpperCase(Locale.ROOT);
                if (!c.matches("^[A-Z0-9][A-Z0-9_-]{1,29}$")) throw new IllegalArgumentException("Code : majuscules, chiffres, tiret (2 à 30 caractères).");
                return shell.gateway().createOrgUnit(c, FormSheet.req(name, "Nom"), FormSheet.pick(parent, "Rattachement").id());
            }, (cm.camtel.nextfleet.desktop.data.Prod.OrgUnit o) -> {
                shell.closeSheet();
                shell.toasts().success("Entité créée", o.name());
            });
            shell.openSheet(f, 440);
        }, err -> shell.toasts().info("Création impossible", err.getMessage()));
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
