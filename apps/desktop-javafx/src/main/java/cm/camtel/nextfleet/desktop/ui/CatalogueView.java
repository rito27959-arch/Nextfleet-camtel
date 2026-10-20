package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.ModelCatalogue;
import cm.camtel.nextfleet.desktop.three.GlbLoader;
import cm.camtel.nextfleet.desktop.three.ModelViewer3D;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * Catalogue 3D : modèles de la flotte (créations originales) et modèle de démonstration CC0. Vue interactive
 * (glisser, molette, double-clic) avec la licence, l'auteur et la source de chaque modèle — obligation d'attribution
 * respectée même quand la licence ne l'exige pas.
 */
public final class CatalogueView extends ScrollPane {

    private final VBox root = new VBox(20);
    private final Map<String, GlbLoader.Model> cache = new HashMap<>();
    private ModelViewer3D viewer;
    private final VBox details = new VBox(10);
    private final StackPane stage = new StackPane();

    public CatalogueView(AppShell shell) {
        setFitToWidth(true);
        root.getStyleClass().add("content");
        setContent(root);
        VBox titles = new VBox(4, Ui.label("Catalogue 3D", "large-title"),
                Ui.label("Silhouettes des catégories de la flotte. Glisser pour tourner, molette pour zoomer, double-clic pour recentrer.",
                        "body", "muted"));
        root.getChildren().add(titles);
        Ui.async(() -> {
            try {
                return ModelCatalogue.load();
            } catch (java.io.IOException e) {
                throw new GatewayException(GatewayException.Kind.SERVER, "Catalogue 3D illisible : " + e.getMessage());
            }
        }, this::render, e -> root.getChildren().add(Ui.errorBanner(e, () -> shell.show(AppShell.Section.CATALOGUE))));
    }

    private void render(ModelCatalogue cat) {
        if (cat.entries().isEmpty()) {
            root.getChildren().add(Ui.statePanel(Icons.CAR, "Aucun modèle", "Le catalogue ne contient aucun modèle redistribuable."));
            return;
        }
        ToggleGroup g = new ToggleGroup();
        VBox list = new VBox(6);
        list.setMinWidth(260);
        list.setPrefWidth(280);
        for (ModelCatalogue.Entry e : cat.entries()) {
            ToggleButton b = new ToggleButton();
            b.getStyleClass().add("catalogue-item");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            VBox text = new VBox(2, Ui.label(e.label(), "headline"), Ui.label(e.category() + " · " + (e.spdx().startsWith("LicenseRef-") ? "création originale" : e.spdx()), "footnote", "muted"));
            HBox row = new HBox(12, Ui.symbolTile(Icons.CAR, "tile-navy"), text);
            row.setAlignment(Pos.CENTER_LEFT);
            b.setGraphic(row);
            b.setToggleGroup(g);
            b.setOnAction(ev -> {
                if (!b.isSelected()) b.setSelected(true);
                select(cat, e);
            });
            list.getChildren().add(b);
        }
        stage.getStyleClass().add("viewer-stage");
        stage.setMinHeight(420);
        HBox.setHgrow(stage, Priority.ALWAYS);
        VBox right = new VBox(16, stage, details);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox body = new HBox(20, list, right);
        root.getChildren().add(body);
        if (!ModelViewer3D.supported()) {
            stage.getChildren().setAll(Ui.statePanel(Icons.CAR, "3D indisponible sur ce poste",
                    "La carte graphique ou le pilote ne prend pas en charge la 3D JavaFX. Les informations de licence restent consultables."));
        } else {
            viewer = new ModelViewer3D();
            viewer.setMinHeight(420);
            stage.getChildren().setAll(viewer);
        }
        ((ToggleButton) list.getChildren().get(0)).setSelected(true);
        select(cat, cat.entries().get(0));
    }

    private void select(ModelCatalogue cat, ModelCatalogue.Entry e) {
        details.getChildren().setAll(detailCard(e, null));
        if (viewer == null) return;
        GlbLoader.Model cached = cache.get(e.id());
        if (cached != null) {
            show(e, cached);
            return;
        }
        Ui.async(() -> {
            try {
                return new GlbLoader().load(cat.bytes(e));
            } catch (java.io.IOException ex) {
                throw new GatewayException(GatewayException.Kind.SERVER, "Modèle illisible : " + ex.getMessage());
            }
        }, m -> {
            cache.put(e.id(), m);
            show(e, m);
        }, ex -> details.getChildren().setAll(detailCard(e, null), Ui.errorBanner(ex, () -> select(cat, e))));
    }

    private void show(ModelCatalogue.Entry e, GlbLoader.Model m) {
        viewer.show(m);
        viewer.reset();
        details.getChildren().setAll(detailCard(e, m));
    }

    private Node detailCard(ModelCatalogue.Entry e, GlbLoader.Model m) {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));
        HBox head = new HBox(10, Ui.label(e.label(), "title3"), Ui.spacer());
        if (viewer != null) {
            Button reset = new Button("Recentrer");
            reset.getStyleClass().addAll("btn-secondary", "btn-small");
            reset.setOnAction(a -> viewer.reset());
            ToggleButton spin = new ToggleButton("Rotation auto");
            spin.getStyleClass().addAll("chip");
            spin.setSelected(true);
            spin.setOnAction(a -> viewer.setAutoRotate(spin.isSelected()));
            head.getChildren().addAll(spin, reset);
        }
        head.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(head);
        card.getChildren().add(row("Catégorie", e.category()));
        if (!e.matches().isEmpty()) card.getChildren().add(row("Véhicules représentés", String.join(", ", e.matches())));
        card.getChildren().add(row("Licence", e.licence() + " (" + e.spdx() + ")"));
        card.getChildren().add(row("Auteur", e.author()));
        if (e.source().startsWith("https://")) {
            Hyperlink link = new Hyperlink(e.source());
            link.setOnAction(a -> {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(e.source()));
                } catch (Exception ignored) {
                    // pas de navigateur : l'adresse reste lisible et copiable
                }
            });
            HBox r = new HBox(12, Ui.label("Source", "footnote", "muted"), link);
            r.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(r);
        } else {
            card.getChildren().add(row("Source", e.source()));
        }
        if (m != null) card.getChildren().add(row("Géométrie", String.format("%,d triangles", m.triangles())));
        return card;
    }

    private static Node row(String k, String v) {
        HBox r = new HBox(12, Ui.label(k, "footnote", "muted"), Ui.label(v, "body"));
        r.setAlignment(Pos.CENTER_LEFT);
        return r;
    }
}
