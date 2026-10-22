package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Feuille de saisie iOS (HIG « Sheets ») : barre Annuler · titre · action en gras, champs étiquetés et groupés, erreur
 * en ligne, action asynchrone avec état « … en cours ». Échap ou Annuler ferme sans rien enregistrer.
 */
final class FormSheet extends ScrollPane {

    private final VBox box = new VBox(12);
    private final Label error = Ui.label("", "error-text");
    private final Button primary;
    private final String primaryText;
    private Supplier<Object> action;
    private Consumer<Object> onDone;

    FormSheet(String title, String primaryText, Runnable onCancel) {
        this.primaryText = primaryText;
        box.getStyleClass().add("sheet");
        box.setMinWidth(0);
        setContent(box);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        Button cancel = new Button("Annuler");
        cancel.getStyleClass().add("btn-plain");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> onCancel.run());
        primary = new Button(primaryText);
        primary.getStyleClass().add("btn-plain");
        primary.setStyle("-fx-font-weight: bold;");
        primary.setDefaultButton(true);
        primary.setOnAction(e -> submit());
        Label t = Ui.label(title, "headline");
        StackPane bar = new StackPane(t, cancel, primary);
        StackPane.setAlignment(cancel, Pos.CENTER_LEFT);
        StackPane.setAlignment(primary, Pos.CENTER_RIGHT);
        bar.setMinHeight(40);
        error.setWrapText(true);
        error.setManaged(false);
        error.setVisible(false);
        box.getChildren().addAll(bar, error);
    }

    /** Texte d'introduction sous la barre. */
    FormSheet intro(String text) {
        Label l = Ui.label(text, "footnote", "muted");
        l.setWrapText(true);
        box.getChildren().add(box.getChildren().size() - 1, l);
        return this;
    }

    /** Note explicative sous les champs déjà ajoutés. */
    FormSheet footer(String text) {
        return intro(text);
    }

    FormSheet field(String label, Node control) {
        Label l = Ui.label(label, "field-label");
        l.setLabelFor(control);
        if (control instanceof javafx.scene.control.Control c) {
            c.setMaxWidth(Double.MAX_VALUE);
            if (c.getAccessibleText() == null) c.setAccessibleText(label.replace(" *", ""));
        }
        VBox g = new VBox(5, l, control);
        box.getChildren().add(box.getChildren().size() - 1, g);
        return this;
    }

    FormSheet row(String l1, Node c1, String l2, Node c2) {
        VBox a = new VBox(5, Ui.label(l1, "field-label"), c1);
        VBox b = new VBox(5, Ui.label(l2, "field-label"), c2);
        if (c1 instanceof javafx.scene.control.Control c) c.setMaxWidth(Double.MAX_VALUE);
        if (c2 instanceof javafx.scene.control.Control c) c.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(a, Priority.ALWAYS);
        HBox.setHgrow(b, Priority.ALWAYS);
        a.setMinWidth(0);
        b.setMinWidth(0);
        HBox h = new HBox(12, a, b);
        box.getChildren().add(box.getChildren().size() - 1, h);
        return this;
    }

    FormSheet node(Node n) {
        box.getChildren().add(box.getChildren().size() - 1, n);
        return this;
    }

    FormSheet section(String header) {
        Label h = Ui.label(header.toUpperCase(java.util.Locale.FRANCE), "section-header");
        h.setPadding(new Insets(8, 0, 0, 2));
        return node(h);
    }

    /** Action exécutée hors du fil d'interface ; {@code done} reçoit le résultat sur le fil d'interface. */
    @SuppressWarnings("unchecked")
    <T> FormSheet onSubmit(Supplier<T> work, Consumer<T> done) {
        this.action = (Supplier<Object>) work;
        this.onDone = (Consumer<Object>) done;
        return this;
    }

    void fail(String message) {
        error.setText(message);
        error.setManaged(true);
        error.setVisible(true);
        error.setAccessibleText("Erreur : " + message);
    }

    private void submit() {
        error.setManaged(false);
        error.setVisible(false);
        if (action == null) return;
        primary.setDisable(true);
        primary.setText("…");
        Ui.async(() -> {
            try {
                return action.get();
            } catch (IllegalArgumentException e) {
                throw new GatewayException(GatewayException.Kind.VALIDATION, e.getMessage());
            }
        }, r -> {
            primary.setDisable(false);
            primary.setText(primaryText);
            onDone.accept(r);
        }, err -> {
            primary.setDisable(false);
            primary.setText(primaryText);
            fail(err.getMessage());
        });
    }

    // ---------------------------------------------------------------- champs prêts à l'emploi

    static TextField text(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        return f;
    }

    static TextArea area(String prompt) {
        TextArea a = new TextArea();
        a.setPromptText(prompt);
        a.setWrapText(true);
        a.setPrefRowCount(3);
        return a;
    }

    static DatePicker date(LocalDate initial) {
        DatePicker d = new DatePicker(initial);
        d.setEditable(true);
        return d;
    }

    static <T> ComboBox<T> combo(List<T> items) {
        ComboBox<T> c = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(items));
        c.setMaxWidth(Double.MAX_VALUE);
        c.setVisibleRowCount(12);
        if (!items.isEmpty()) c.getSelectionModel().select(0);
        return c;
    }

    static CheckBox check(String label, boolean value) {
        CheckBox c = new CheckBox(label);
        c.setSelected(value);
        return c;
    }

    /** Sélection d'un justificatif (PDF, JPEG, PNG, WebP) ; le nom choisi s'affiche dans le bouton. */
    static final class FilePick extends HBox {
        private Path chosen;
        private final Label name = Ui.label("Aucun fichier", "footnote", "muted");

        FilePick(String buttonText) {
            super(10);
            setAlignment(Pos.CENTER_LEFT);
            Button b = new Button(buttonText, Icons.stroke(Icons.DOC, 14, "-nf-accent"));
            b.getStyleClass().add("btn-tinted");
            b.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                fc.setTitle(buttonText);
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF ou image", "*.pdf", "*.jpg", "*.jpeg", "*.png", "*.webp"));
                java.io.File f = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
                if (f != null) {
                    chosen = f.toPath();
                    name.setText(f.getName() + " · " + Math.max(1, f.length() / 1024) + " Ko");
                }
            });
            getChildren().addAll(b, name);
        }

        Path path() {
            return chosen;
        }
    }

    // ---------------------------------------------------------------- lecture des champs

    static String req(TextField f, String label) {
        String v = f.getText() == null ? "" : f.getText().strip();
        if (v.isEmpty()) throw new IllegalArgumentException(label + " : champ obligatoire.");
        return v;
    }

    static String opt(TextField f) {
        String v = f.getText() == null ? "" : f.getText().strip();
        return v.isEmpty() ? null : v;
    }

    static BigDecimal dec(TextField f, String label, boolean required) {
        String v = f.getText() == null ? "" : f.getText().strip().replace(" ", "").replace(" ", "").replace(',', '.');
        if (v.isEmpty()) {
            if (required) throw new IllegalArgumentException(label + " : champ obligatoire.");
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(v);
            if (d.signum() < 0) throw new IllegalArgumentException(label + " : valeur négative.");
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " : nombre attendu.");
        }
    }

    static Integer integer(TextField f, String label) {
        BigDecimal d = dec(f, label, false);
        return d == null ? null : d.intValue();
    }

    static <T> T pick(ComboBox<T> c, String label) {
        T v = c.getValue();
        if (v == null) throw new IllegalArgumentException(label + " : choisissez une valeur.");
        return v;
    }

    static LocalDate day(DatePicker d, String label, boolean required) {
        LocalDate v = d.getValue();
        if (v == null && required) throw new IllegalArgumentException(label + " : date obligatoire.");
        return v;
    }
}
