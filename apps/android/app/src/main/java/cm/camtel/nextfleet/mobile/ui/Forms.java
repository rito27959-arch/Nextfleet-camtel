package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

import cm.camtel.nextfleet.mobile.NexFleetApp;

/**
 * Formulaires terrain au style iOS : barre Annuler · titre · action, champs étiquetés dans des groupes arrondis,
 * erreur en ligne. Toutes les saisies partent au serveur, qui revérifie droits et règles.
 */
final class Forms {

    final Activity a;
    final LinearLayout col;
    final TextView error;
    final TextView action;

    Forms(Activity a, String title, String actionLabel, Runnable onAction) {
        this.a = a;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.backdrop());

        LinearLayout bar = Ui.row(a, 8);
        bar.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        TextView cancel = Ui.text(a, "Annuler", 17, Ui.ACCENT_TEXT, NexFleetApp.get().regular());
        cancel.setPadding(Ui.dp(a, 6), Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 10));
        cancel.setOnClickListener(v -> a.finish());
        TextView t = Ui.headline(a, title);
        t.setGravity(Gravity.CENTER);
        action = Ui.text(a, actionLabel, 17, Ui.ACCENT_TEXT, NexFleetApp.get().semibold());
        action.setPadding(Ui.dp(a, 6), Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 10));
        action.setOnClickListener(v -> onAction.run());
        Motion.pressable(action);
        Motion.pressable(cancel);
        action.setContentDescription(actionLabel);
        bar.addView(cancel);
        bar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(action);
        root.addView(bar);
        Ui.applyInsets(bar, true, false);

        col = Ui.column(a, 10);
        col.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 32));
        error = Ui.text(a, "", 14, Ui.RED, NexFleetApp.get().regular());
        error.setVisibility(View.GONE);
        col.addView(error);
        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        Ui.applyInsets(sv, false, true);
        a.setContentView(root);
    }

    void intro(String text) {
        TextView t = Ui.muted(a, text, 14);
        t.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), Ui.dp(a, 4));
        col.addView(t, col.getChildCount() - 1);
    }

    void header(String text) {
        TextView t = Ui.text(a, text.toUpperCase(java.util.Locale.FRANCE), 13, Ui.MUTED, NexFleetApp.get().regular());
        t.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), 0, 0);
        col.addView(t, col.getChildCount() - 1);
    }

    LinearLayout group() {
        LinearLayout g = Ui.group(a);
        col.addView(g, col.getChildCount() - 1);
        return g;
    }

    /** Champ étiqueté dans un groupe : libellé à gauche, saisie à droite (Réglages iOS). */
    EditText field(LinearLayout group, String label, String hint, int inputType) {
        if (group.getChildCount() > 0) group.addView(Ui.separator(a, 16));
        LinearLayout row = Ui.row(a, 10);
        row.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 12), 0);
        row.setMinimumHeight(Ui.dp(a, 52));
        TextView l = Ui.body(a, label);
        row.addView(l, new LinearLayout.LayoutParams(Ui.dp(a, 128), LinearLayout.LayoutParams.WRAP_CONTENT));
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        e.setTypeface(NexFleetApp.get().regular());
        e.setTextSize(16);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.TERTIARY);
        e.setBackground(null);
        e.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        e.setContentDescription(label);
        row.addView(e, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        group.addView(row);
        return e;
    }

    EditText text(LinearLayout g, String label, String hint) {
        return field(g, label, hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    }

    EditText number(LinearLayout g, String label, String hint) {
        return field(g, label, hint, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    }

    <T> Spinner spinner(LinearLayout group, String label, List<T> items) {
        if (group.getChildCount() > 0) group.addView(Ui.separator(a, 16));
        LinearLayout row = Ui.row(a, 10);
        row.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 4), 0);
        row.setMinimumHeight(Ui.dp(a, 52));
        row.addView(Ui.body(a, label), new LinearLayout.LayoutParams(Ui.dp(a, 128), LinearLayout.LayoutParams.WRAP_CONTENT));
        Spinner s = new Spinner(a);
        ArrayAdapter<T> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        s.setContentDescription(label);
        row.addView(s, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        group.addView(row);
        return s;
    }

    CheckBox check(LinearLayout group, String label, boolean value) {
        if (group.getChildCount() > 0) group.addView(Ui.separator(a, 16));
        CheckBox c = new CheckBox(a);
        c.setText(label);
        c.setChecked(value);
        c.setTypeface(NexFleetApp.get().regular());
        c.setTextSize(16);
        c.setTextColor(Ui.TEXT);
        c.setMinHeight(Ui.dp(a, 52));
        c.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(a, 8);
        group.addView(c, lp);
        return c;
    }

    void footer(String text) {
        TextView t = Ui.muted(a, text, 13);
        t.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 4));
        col.addView(t, col.getChildCount() - 1);
    }

    void fail(String m) {
        error.setText(m);
        error.setVisibility(View.VISIBLE);
        Motion.reject(error);
        Motion.shake(error);
        action.setEnabled(true);
        action.setAlpha(1f);
    }

    void busy() {
        error.setVisibility(View.GONE);
        action.setEnabled(false);
        action.setAlpha(0.4f);
    }

    static String str(EditText e) {
        return e.getText().toString().trim();
    }

    static Double num(EditText e, String label, boolean required) {
        String v = str(e).replace(" ", "").replace(',', '.');
        if (v.isEmpty()) {
            if (required) throw new IllegalArgumentException(label + " : champ obligatoire.");
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            if (d < 0) throw new IllegalArgumentException(label + " : valeur négative.");
            return d;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " : nombre attendu.");
        }
    }
}
