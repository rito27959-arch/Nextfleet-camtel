package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Ops.ChatLine;
import cm.camtel.nextfleet.desktop.data.Ops.Conversation;
import cm.camtel.nextfleet.domain.chat.Chat;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Messagerie (NF-60 à NF-64), composition iMessage : liste des conversations (non lus en pastille bleue), fil avec
 * bulles regroupées par expéditeur, séparateurs horaires, état discret sous la dernière bulle envoyée (Envoi…, Envoyé,
 * Distribué, Lu), saisie arrondie et bouton d'envoi rond actif seulement avec du texte.
 * « Envoyé » n'apparaît qu'après l'accusé du serveur ; un échec réseau garde le message en « Non envoyé » et la
 * réémission réutilise le même identifiant : aucun doublon.
 */
final class MessagesView extends BorderPane {

    private static final ZoneId DLA = ZoneId.of("Africa/Douala");
    private final AppShell shell;
    private final VBox convList = new VBox(2);
    private final TextField search = new TextField();
    private final BorderPane thread = new BorderPane();
    private final VBox body = new VBox(2);
    private final ScrollPane bodyScroll = new ScrollPane(body);
    private final TextField input = new TextField();
    private final Button send = new Button(null, Icons.stroke(Icons.SEND, 16, "white"));
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(5), e -> reloadThread(false)));
    private List<Conversation> conversations = List.of();
    private List<ChatLine> lastLines = List.of();
    private GatewayException lastError;
    private String selected;

    private record Pending(String clientId, String conversationId, String body, Instant at, boolean failed, java.nio.file.Path file) { }

    private final Button attach = new Button(null, Icons.stroke(Icons.DOC, 16, "-nf-accent"));
    private final Label attachChip = Ui.label("", "attachment-chip");
    private java.nio.file.Path attachment;
    private AutoCloseable realtime;

    MessagesView(AppShell shell, String openConversation) {
        this.shell = shell;
        this.selected = openConversation;

        search.setPromptText("Rechercher");
        search.getStyleClass().add("search-field");
        search.setPrefWidth(Region.USE_COMPUTED_SIZE);
        search.textProperty().addListener((o, a, b) -> renderList());
        StackPane searchBox = new StackPane(search, Icons.stroke(Icons.SEARCH, 15, "-nf-muted"));
        StackPane.setAlignment(searchBox.getChildren().get(1), Pos.CENTER_LEFT);
        searchBox.getChildren().get(1).setTranslateX(12);
        searchBox.getChildren().get(1).setMouseTransparent(true);
        HBox titleRow = new HBox(8, Ui.label("Messages", "large-title"), Ui.spacer());
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (!shell.gateway().isDemo()) {
            Button compose = new Button(null, Icons.stroke(Icons.PLUS, 16, "white"));
            compose.getStyleClass().addAll("btn-filled", "btn-capsule");
            compose.setAccessibleText("Nouvelle conversation");
            compose.setTooltip(new javafx.scene.control.Tooltip("Nouvelle conversation"));
            compose.setOnAction(e -> composeSheet());
            titleRow.getChildren().add(compose);
        }
        VBox header = new VBox(12, titleRow, searchBox);
        header.getStyleClass().add("split-list-header");
        ScrollPane sp = new ScrollPane(convList);
        sp.setFitToWidth(true);
        convList.setPadding(new Insets(2, 10, 16, 10));
        VBox left = new VBox(header, sp);
        VBox.setVgrow(sp, Priority.ALWAYS);
        left.getStyleClass().add("split-list");
        setLeft(Ui.rounded(left, 22));
        // iOS 26 : colonnes en panneaux arrondis décollés des bords, alignés sur la barre latérale flottante
        setPadding(new Insets(4, 22, 22, 4));
        BorderPane.setMargin(left, new Insets(0, 14, 0, 0));

        thread.getStyleClass().add("thread");
        bodyScroll.setFitToWidth(true);
        bodyScroll.getStyleClass().add("thread");
        body.getStyleClass().add("thread-body");
        body.heightProperty().addListener((o, a, b) -> bodyScroll.setVvalue(1.0));
        setCenter(Ui.rounded(thread, 22));
        thread.setCenter(Ui.statePanel(Icons.BUBBLE, "Aucune conversation sélectionnée",
                "Les échanges sont réservés aux participants de chaque conversation."));

        input.setPromptText("Message");
        input.getStyleClass().add("composer-field");
        input.setAccessibleText("Écrire un message");
        HBox.setHgrow(input, Priority.ALWAYS);
        send.getStyleClass().add("send-button");
        send.setAccessibleText("Envoyer");
        send.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> (input.getText() == null || input.getText().isBlank()) && attachChip.getText().isEmpty(), input.textProperty(), attachChip.textProperty()));
        attach.getStyleClass().add("btn-plain");
        attach.setAccessibleText("Joindre un fichier");
        attach.setTooltip(new javafx.scene.control.Tooltip("Joindre une photo ou un PDF (10 Mo maximum)"));
        attach.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Photo ou PDF", "*.pdf", "*.jpg", "*.jpeg", "*.png", "*.webp"));
            java.io.File f = fc.showOpenDialog(getScene().getWindow());
            if (f != null) {
                attachment = f.toPath();
                attachChip.setText("📎 " + f.getName() + "  ✕");
            }
        });
        attachChip.setVisible(false);
        attachChip.setManaged(false);
        attachChip.textProperty().addListener((o, a, b) -> {
            attachChip.setVisible(!b.isEmpty());
            attachChip.setManaged(!b.isEmpty());
        });
        attachChip.setOnMouseClicked(e -> {
            attachment = null;
            attachChip.setText("");
        });
        attach.setVisible(!shell.gateway().isDemo());
        attach.setManaged(!shell.gateway().isDemo());
        send.setOnAction(e -> submit());
        input.setOnAction(e -> {
            if (!send.isDisabled()) submit();
        });
        Motion.pressable(send);

        refresh.setCycleCount(Animation.INDEFINITE);
        // temps réel : signal « nouveau message » du serveur (le contenu est relu par l'API, droits revérifiés)
        realtime = shell.gateway().subscribeMessages(conv -> Ui.later(() -> {
            if (conv.equals(selected)) reloadThread(true);
            else loadConversationsQuiet();
            shell.refreshMessagesBadge();
        }));
        sceneProperty().addListener((o, a, s) -> {
            if (s == null) {
                refresh.stop();
                try {
                    realtime.close();
                } catch (Exception ignored) {
                    // canal déjà fermé
                }
            }
        });
        loadConversations();
    }

    private void loadConversations() {
        Ui.async(() -> shell.gateway().conversations(), list -> {
            conversations = list;
            if (selected == null && !list.isEmpty()) selected = list.get(0).id();
            renderList();
            if (selected != null) open(selected);
        }, err -> convList.getChildren().setAll(Ui.errorBanner(err, this::loadConversations)));
    }

    private void renderList() {
        String q = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        List<Node> rows = new ArrayList<>();
        for (Conversation c : conversations) {
            if (!q.isEmpty() && !(c.title().toLowerCase(Locale.ROOT).contains(q) || (c.lastBody() != null
                    && c.lastBody().toLowerCase(Locale.ROOT).contains(q)))) continue;
            rows.add(row(c));
        }
        if (rows.isEmpty()) rows.add(Ui.label("Aucun résultat.", "muted", "subhead"));
        convList.getChildren().setAll(rows);
    }

    private Node avatar(Conversation c, double size) {
        StackPane a = switch (c.kind()) {
            case MISSION -> Ui.symbolTile(Icons.ROUTE, "tile-mint");
            case SERVICE, VEHICLE -> Ui.symbolTile(Icons.WRENCH, "tile-orange");
            case DIRECT -> Ui.avatar(initials(c.title()), Math.abs(c.title().hashCode()));
        };
        a.setStyle("-fx-min-width: " + size + "; -fx-min-height: " + size + "; -fx-max-width: " + size + "; -fx-max-height: " + size
                + "; -fx-background-radius: " + (c.kind() == Chat.Kind.DIRECT ? 999 : size * 0.26) + ";");
        return a;
    }

    private Node row(Conversation c) {
        Label title = Ui.label(c.title(), "conv-title");
        Label time = Ui.label(c.lastAt() == null ? "" : shortTime(c.lastAt()), "conv-time");
        time.setMinWidth(Region.USE_PREF_SIZE);
        HBox top = new HBox(6, title, Ui.spacer(), time);
        top.setAlignment(Pos.CENTER_LEFT);
        String previewText = c.lastBody() == null ? c.subtitle() : (c.lastSender() == null || c.kind() == Chat.Kind.DIRECT
                && !"Vous".equals(c.lastSender()) ? "" : c.lastSender() + " : ") + c.lastBody();
        Label preview = Ui.label(previewText, "conv-preview");
        preview.setWrapText(true);
        preview.setMaxHeight(36);
        HBox bottom = new HBox(8, preview, Ui.spacer());
        bottom.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(preview, Priority.ALWAYS);
        if (c.unread() > 0 && !c.id().equals(selected)) {
            Label n = Ui.label(String.valueOf(c.unread()), "unread-count");
            n.setMinWidth(Region.USE_PREF_SIZE);
            bottom.getChildren().add(n);
        }
        VBox text = new VBox(2, top, bottom);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        HBox r = new HBox(12, avatar(c, 44), text);
        r.getStyleClass().add("conv-row");
        r.setAlignment(Pos.CENTER_LEFT);
        if (c.id().equals(selected)) r.getStyleClass().add("selected");
        r.setOnMouseClicked(e -> open(c.id()));
        r.setAccessibleText(c.title() + (c.unread() > 0 ? ", " + c.unread() + " non lus" : "") + ". " + previewText);
        return r;
    }

    private void open(String id) {
        if (!id.equals(selected)) lastLines = List.of();
        selected = id;
        renderList();
        Conversation c = conversations.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (c == null) return;
        Label title = Ui.label(c.title(), "headline");
        String sub = c.kind() == Chat.Kind.DIRECT ? c.subtitle()
                : c.participants().size() + " participants · " + c.subtitle();
        VBox t = new VBox(1, title, Ui.label(sub, "footnote", "muted"));
        HBox head = new HBox(12, avatar(c, 36), t, Ui.spacer());
        if (c.kind() == Chat.Kind.MISSION) head.getChildren().add(Ui.label("Mission", "pill", "pill-ok"));
        head.getStyleClass().add("thread-header");
        HBox line = new HBox(10, attach, input, send);
        line.setAlignment(Pos.CENTER_LEFT);
        VBox composer = new VBox(6, attachChip, line);
        composer.getStyleClass().add("composer");
        thread.setTop(head);
        thread.setCenter(bodyScroll);
        thread.setBottom(composer);
        reloadThread(true);
        refresh.play();
        Platform.runLater(input::requestFocus);
    }

    private void reloadThread(boolean markRead) {
        if (selected == null) return;
        String id = selected;
        Ui.async(() -> {
            List<ChatLine> lines = shell.gateway().messages(id);
            if (markRead) shell.gateway().markConversationRead(id);
            return lines;
        }, lines -> {
            if (!id.equals(selected)) return;
            lastLines = lines;
            lastError = null;
            renderThread(lines);
            if (markRead) {
                shell.refreshMessagesBadge();
                conversations = conversations.stream().map(c -> c.id().equals(id) ? new Conversation(c.id(), c.kind(), c.title(),
                        c.subtitle(), c.lastBody(), c.lastSender(), c.lastAt(), 0, c.participants()) : c).toList();
                renderList();
            }
        }, err -> {
            // le fil reste affiché (dernier état connu + messages en attente) ; l'erreur s'affiche au-dessus
            lastError = err;
            renderThread(lastLines);
        });
    }

    private void renderThread(List<ChatLine> lines) {
        List<Node> nodes = new ArrayList<>();
        Conversation c = conversations.stream().filter(x -> x.id().equals(selected)).findFirst().orElse(null);
        boolean group = c != null && c.kind() != Chat.Kind.DIRECT;
        Instant prevAt = null;
        String prevSender = null;
        int lastMine = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).mine()) lastMine = i;
        for (int i = 0; i < lines.size(); i++) {
            ChatLine l = lines.get(i);
            if (prevAt == null || java.time.Duration.between(prevAt, l.at()).toMinutes() >= 30) {
                nodes.add(separator(l.at()));
                prevSender = null;
            }
            if (group && !l.mine() && !java.util.Objects.equals(senderKey(l), prevSender)) nodes.add(Ui.label(l.senderName(), "bubble-sender"));
            nodes.add(bubble(l.body(), l.mine(), false, false));
            if (!l.attachments().isEmpty()) nodes.add(attachments(l));
            if (i == lastMine && l.state() != null) nodes.add(meta(l.state().label, false, null));
            prevAt = l.at();
            prevSender = senderKey(l);
        }
        for (Pending p : pending.values()) {
            if (!p.conversationId().equals(selected)) continue;
            nodes.add(bubble(p.body() + (p.file() != null ? "\n📎 " + p.file().getFileName() : ""), true, !p.failed(), p.failed()));
            nodes.add(p.failed() ? meta(Chat.DeliveryState.ECHEC.label + " · Toucher pour réessayer", true, () -> retry(p))
                    : meta(Chat.DeliveryState.EN_ATTENTE.label, false, null));
        }
        if (nodes.isEmpty()) nodes.add(Ui.statePanel(Icons.BUBBLE, "Aucun message", "Écrivez le premier message de cette conversation."));
        if (lastError != null) nodes.add(0, Ui.errorBanner(lastError, () -> reloadThread(false)));
        body.getChildren().setAll(nodes);
    }

    private static String senderKey(ChatLine l) {
        return l.senderId() != null ? l.senderId() : l.senderName();
    }

    private Node separator(Instant at) {
        var z = at.atZone(DLA);
        LocalDate today = LocalDate.now(DLA);
        String day = z.toLocalDate().equals(today) ? "Aujourd'hui" : z.toLocalDate().equals(today.minusDays(1)) ? "Hier"
                : z.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRANCE));
        Label l = Ui.label(day + " " + z.format(DateTimeFormatter.ofPattern("HH:mm")), "time-separator");
        HBox h = new HBox(l);
        h.setAlignment(Pos.CENTER);
        return h;
    }

    private Node bubble(String text, boolean mine, boolean pendingState, boolean failed) {
        Label b = new Label(text);
        b.getStyleClass().addAll("bubble", failed ? "bubble-failed" : mine ? "bubble-out" : "bubble-in");
        if (pendingState) b.getStyleClass().add("bubble-pending");
        b.maxWidthProperty().bind(bodyScroll.widthProperty().multiply(0.66));
        b.setMinHeight(Region.USE_PREF_SIZE);
        HBox h = new HBox(b);
        h.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        h.setPadding(new Insets(1, 0, 1, 0));
        b.setAccessibleText((mine ? "Vous : " : "") + text);
        return h;
    }

    private Node attachments(ChatLine l) {
        HBox h = new HBox(6);
        h.setAlignment(l.mine() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        for (cm.camtel.nextfleet.desktop.data.Prod.Attachment a : l.attachments()) {
            Label chip = Ui.label("📎 " + a.name() + " · " + Math.max(1, a.sizeBytes() / 1024) + " Ko", "attachment-chip");
            chip.setAccessibleText("Pièce jointe " + a.name() + ", ouvrir");
            chip.setOnMouseClicked(e -> Files.open(shell, a.id(), a.name().replaceAll("\\.[A-Za-z0-9]+$", "")));
            h.getChildren().add(chip);
        }
        return h;
    }

    /** Nouvelle conversation : une personne (conversation directe, réutilisée si elle existe) ou un groupe nommé. */
    private void composeSheet() {
        Ui.async(() -> shell.gateway().contacts(), contacts -> {
            if (contacts.isEmpty()) {
                shell.toasts().info("Aucun contact", "Personne d'autre n'a accès à la messagerie dans votre périmètre.");
                return;
            }
            TextField filter = FormSheet.text("Rechercher une personne");
            VBox list = new VBox(4);
            List<javafx.scene.control.CheckBox> boxes = new ArrayList<>();
            for (cm.camtel.nextfleet.desktop.data.Prod.Contact c : contacts) {
                javafx.scene.control.CheckBox cb = new javafx.scene.control.CheckBox(c.displayName() + " · " + c.role() + " · " + c.orgUnit());
                cb.setUserData(c);
                boxes.add(cb);
                list.getChildren().add(cb);
            }
            filter.textProperty().addListener((o, a, b) -> {
                String q = b == null ? "" : b.strip().toLowerCase(Locale.ROOT);
                for (javafx.scene.control.CheckBox cb : boxes) {
                    boolean show = q.isEmpty() || cb.getText().toLowerCase(Locale.ROOT).contains(q);
                    cb.setVisible(show);
                    cb.setManaged(show);
                }
            });
            ScrollPane sp = new ScrollPane(list);
            sp.setFitToWidth(true);
            sp.setPrefHeight(280);
            TextField title = FormSheet.text("Nom du groupe (plusieurs personnes)");
            FormSheet f = new FormSheet("Nouvelle conversation", "Ouvrir", shell::closeSheet)
                    .intro("Seuls les participants lisent les messages. Une conversation à deux est réutilisée si elle existe déjà.")
                    .field("Destinataires *", filter).node(sp).field("Nom du groupe", title);
            f.onSubmit(() -> {
                List<String> ids = boxes.stream().filter(javafx.scene.control.CheckBox::isSelected)
                        .map(cb -> ((cm.camtel.nextfleet.desktop.data.Prod.Contact) cb.getUserData()).userId()).toList();
                if (ids.isEmpty()) throw new IllegalArgumentException("Choisissez au moins un destinataire.");
                return shell.gateway().openConversation(ids, ids.size() > 1 ? FormSheet.opt(title) : null);
            }, (Conversation c) -> {
                shell.closeSheet();
                selected = c.id();
                loadConversations();
            });
            shell.openSheet(f, 460);
        }, err -> shell.toasts().info("Contacts indisponibles", err.getMessage()));
    }

    private Node meta(String text, boolean failed, Runnable onClick) {
        Label l = Ui.label(text, "bubble-meta");
        if (failed) l.getStyleClass().add("failed");
        if (onClick != null) {
            l.setCursor(javafx.scene.Cursor.HAND);
            l.setOnMouseClicked(e -> onClick.run());
        }
        HBox h = new HBox(l);
        h.setAlignment(Pos.CENTER_RIGHT);
        return h;
    }

    private void submit() {
        String text = input.getText().strip();
        java.nio.file.Path file = attachment;
        if ((text.isEmpty() && file == null) || selected == null) return;
        if (text.isEmpty()) text = "Pièce jointe : " + file.getFileName();
        Pending p = new Pending(UUID.randomUUID().toString(), selected, text, Instant.now(), false, file);
        pending.put(p.clientId(), p);
        input.clear();
        attachment = null;
        attachChip.setText("");
        reloadThreadKeepingPending();
        deliver(p);
    }

    private void deliver(Pending p) {
        Ui.async(() -> {
            List<UUID> files = p.file() == null ? List.of() : List.of(shell.gateway().upload("CHAT", null, p.file()).id());
            return shell.gateway().sendMessage(p.conversationId(), p.clientId(), p.body(), files);
        }, line -> {
            pending.remove(p.clientId());
            loadConversationsQuiet();
            reloadThread(false);
        }, err -> {
            if (err.kind() == GatewayException.Kind.FORBIDDEN || err.kind() == GatewayException.Kind.VALIDATION) {
                pending.remove(p.clientId());
                AlertDialog.show(shell, Ui.symbolTile(Icons.LOCK, "tile-red"), "Message non envoyé", err.getMessage(),
                        List.of(AlertDialog.Action.preferred("OK", () -> { })));
            } else {
                pending.put(p.clientId(), new Pending(p.clientId(), p.conversationId(), p.body(), p.at(), true, p.file()));
            }
            reloadThreadKeepingPending();
        });
    }

    /** Réémission avec le MÊME identifiant client : le serveur reconnaît le message s'il l'avait déjà reçu. */
    private void retry(Pending p) {
        Pending again = new Pending(p.clientId(), p.conversationId(), p.body(), p.at(), false, p.file());
        pending.put(p.clientId(), again);
        reloadThreadKeepingPending();
        deliver(again);
    }

    private void reloadThreadKeepingPending() {
        reloadThread(false);
    }

    private void loadConversationsQuiet() {
        Ui.async(() -> shell.gateway().conversations(), list -> {
            conversations = list;
            renderList();
        }, err -> { });
    }

    private static String shortTime(Instant at) {
        var z = at.atZone(DLA);
        LocalDate today = LocalDate.now(DLA);
        if (z.toLocalDate().equals(today)) return z.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (z.toLocalDate().equals(today.minusDays(1))) return "Hier";
        return z.format(DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE));
    }

    private static String initials(String name) {
        String[] p = name.trim().split("\\s+");
        return ((p.length > 0 ? p[0].substring(0, 1) : "") + (p.length > 1 ? p[p.length - 1].substring(0, 1) : "")).toUpperCase(Locale.FRANCE);
    }

    /** Pour la recette visuelle : saisit et envoie un message. */
    void typeAndSendForTest(String text) {
        input.setText(text);
        submit();
    }
}
