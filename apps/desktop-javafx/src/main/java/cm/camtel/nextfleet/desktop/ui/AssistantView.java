package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.domain.assistant.AssistantActivation;
import cm.camtel.nextfleet.domain.assistant.FleetAssistant;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Assistant en LECTURE SEULE (scénario E du cahier) : chaque réponse donne chiffres, période, filtres et références
 * des pièces ; un refus est expliqué (droits, désactivation, demande d'écriture). Désactivé tant que l'administration
 * n'a pas enregistré une décision fondée sur une validation de sécurité.
 */
final class AssistantView extends BorderPane {

    private static final List<String> SUGGESTIONS = List.of(
            "Coût des véhicules CE 3205 KJ, LT 5520 CE et CE 4401 LT ce mois-ci ?",
            "Quelles échéances expirent bientôt ?",
            "Quelles missions sont en cours ?",
            "Consommation du mois dernier ?",
            "Combien de véhicules sont disponibles ?");

    private final AppShell shell;
    private final VBox feed = new VBox(14);
    private final ScrollPane scroll = new ScrollPane();
    private final TextField input = new TextField();
    private final Button send = new Button(null, Icons.stroke(Icons.SEND, 16, "white"));

    AssistantView(AppShell shell) {
        this.shell = shell;
        setStyle("-fx-background-color: -nf-bg;");
        Ui.async(() -> shell.gateway().assistantState(), this::build,
                err -> setCenter(Ui.errorBanner(err, () -> shell.show(AppShell.Section.ASSISTANT))));
    }

    private void build(AssistantActivation.State state) {
        Label title = Ui.label("Assistant", "large-title");
        HBox badges = new HBox(8, Ui.label("LECTURE SEULE", "readonly-pill"), Ui.label("Moteur " + FleetAssistant.ENGINE, "meta-chip"));
        badges.setAlignment(Pos.CENTER_LEFT);
        Label sub = Ui.label("Réponses chiffrées, avec période, filtres et sources. L'assistant ne modifie jamais NexFleet.", "body", "muted");
        sub.setWrapText(true);
        VBox head = new VBox(8, title, badges, sub);
        head.setPadding(new Insets(28, 32, 12, 32));
        head.setMaxWidth(820);
        StackPane headWrap = new StackPane(head);
        headWrap.setAlignment(Pos.TOP_CENTER);
        setTop(headWrap);

        if (!state.enabled()) {
            VBox p = Ui.statePanel(Icons.SPARK, "Assistant désactivé",
                    "L'assistant sera ouvert après la validation de sécurité (DS / RSSI) et la décision de l'administration technique. "
                            + "Aucune question n'est traitée d'ici là.");
            setCenter(p);
            return;
        }

        feed.setPadding(new Insets(8, 32, 24, 32));
        feed.setMaxWidth(820);
        StackPane feedWrap = new StackPane(feed);
        feedWrap.setAlignment(Pos.TOP_CENTER);
        scroll.setContent(feedWrap);
        scroll.setFitToWidth(true);
        feed.heightProperty().addListener((o, a, b) -> scroll.setVvalue(1.0));
        setCenter(scroll);

        FlowPane chips = new FlowPane(8, 8);
        for (String s : SUGGESTIONS) {
            Button b = new Button(s);
            b.getStyleClass().add("suggestion");
            b.setOnAction(e -> ask(s));
            Motion.pressable(b);
            chips.getChildren().add(b);
        }
        Label intro = Ui.label("Exemples de questions", "footnote", "muted");
        feed.getChildren().addAll(intro, chips);
        Label activation = Ui.label("Activé sur validation " + state.securityReviewRef() + " (" + state.approvedBy() + ") · "
                + "chaque question est journalisée.", "footnote", "muted");
        activation.setWrapText(true);
        feed.getChildren().add(activation);

        input.setPromptText("Posez une question sur le parc…");
        input.getStyleClass().add("composer-field");
        input.setAccessibleText("Question à l'assistant");
        HBox.setHgrow(input, Priority.ALWAYS);
        send.getStyleClass().add("send-button");
        send.setAccessibleText("Envoyer la question");
        send.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> input.getText() == null || input.getText().isBlank(), input.textProperty()));
        send.setOnAction(e -> ask(input.getText()));
        input.setOnAction(e -> {
            if (!send.isDisabled()) ask(input.getText());
        });
        Motion.pressable(send);
        HBox composer = new HBox(10, input, send);
        composer.getStyleClass().add("composer");
        composer.setMaxWidth(820);
        StackPane cw = new StackPane(composer);
        cw.getStyleClass().add("composer");
        cw.setPadding(Insets.EMPTY);
        composer.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        setBottom(cw);
        Platform.runLater(input::requestFocus);
    }

    private void ask(String question) {
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) return;
        input.clear();
        Label bubble = Ui.label(q, "assistant-q");
        bubble.setWrapText(true);
        bubble.setMaxWidth(560);
        bubble.setMinHeight(Region.USE_PREF_SIZE);
        HBox qRow = new HBox(bubble);
        qRow.setAlignment(Pos.CENTER_RIGHT);
        Region wait = Ui.skeleton(-1, 90);
        feed.getChildren().addAll(qRow, wait);
        Motion.enter(qRow, javafx.util.Duration.ZERO, 8);
        Ui.async(() -> shell.gateway().askAssistant(q), a -> {
            Node card = answer(a);
            feed.getChildren().set(feed.getChildren().indexOf(wait), card);
            Motion.enter(card, javafx.util.Duration.ZERO, 10);
        }, err -> feed.getChildren().set(feed.getChildren().indexOf(wait), Ui.errorBanner(err, null)));
    }

    private Node answer(FleetAssistant.Answer a) {
        StackPane tile = a.refused() ? Ui.symbolTile(Icons.LOCK, "tile-gray") : Ui.symbolTile(Icons.SPARK, "tile-mint");
        Label text = Ui.label(a.text(), "answer-text");
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox top = new HBox(12, tile, text);
        top.setAlignment(Pos.TOP_LEFT);
        VBox card = new VBox(10, top);
        card.getStyleClass().add("answer-card");
        if (a.refused()) card.getStyleClass().add("refused");

        if (!a.figures().isEmpty()) {
            VBox figs = new VBox();
            for (FleetAssistant.Figure f : a.figures()) {
                Label l = Ui.label(f.label(), "figure-label");
                l.setWrapText(true);
                l.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(l, Priority.ALWAYS);
                Label v = Ui.label(f.value(), "figure-value");
                v.setMinWidth(Region.USE_PREF_SIZE);
                if ("Total".equals(f.label())) {
                    l.setStyle("-fx-font-weight: 600;");
                    v.setStyle("-fx-text-fill: -nf-accent-text;");
                }
                HBox r = new HBox(12, l, v);
                r.getStyleClass().add("figure-row");
                figs.getChildren().add(r);
            }
            VBox.setMargin(figs, new Insets(0, 0, 0, 44));
            card.getChildren().add(figs);
        }
        FlowPane meta = new FlowPane(6, 6);
        if (a.period() != null) meta.getChildren().add(Ui.label("Période : " + a.period(), "meta-chip"));
        a.filters().forEach(f -> meta.getChildren().add(Ui.label(f, "meta-chip")));
        if (!meta.getChildren().isEmpty()) {
            VBox.setMargin(meta, new Insets(0, 0, 0, 44));
            card.getChildren().add(meta);
        }
        if (!a.sources().isEmpty()) {
            VBox list = new VBox(3);
            a.sources().forEach(s -> list.getChildren().add(Ui.label(s.ref() + "  ·  " + s.description(), "source-row")));
            list.setManaged(false);
            list.setVisible(false);
            Button toggle = new Button("Sources (" + a.sources().size() + ")", Icons.stroke(Icons.CHEVRON_RIGHT, 11, "-nf-accent-text"));
            toggle.getStyleClass().addAll("btn-plain", "btn-small");
            toggle.setOnAction(e -> {
                boolean show = !list.isVisible();
                list.setVisible(show);
                list.setManaged(show);
                toggle.getGraphic().setRotate(show ? 90 : 0);
                if (show) Motion.enter(list, javafx.util.Duration.ZERO, 4);
            });
            VBox src = new VBox(4, toggle, list);
            VBox.setMargin(src, new Insets(0, 0, 0, 40));
            card.getChildren().add(src);
        }
        return card;
    }

    /** Pour la recette visuelle. */
    void askForTest(String q) {
        ask(q);
    }

    void openSourcesForTest() {
        feed.lookupAll(".btn-small").forEach(n -> ((Button) n).fire());
    }
}
