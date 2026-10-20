package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cm.camtel.nextfleet.domain.security.Role;
import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.R;
import cm.camtel.nextfleet.mobile.data.Models;
import cm.camtel.nextfleet.mobile.data.Repository;

/**
 * Connexion (NF-01) : mode démonstration (données fictives, identifiants publics) ou serveur NexFleet (HTTPS).
 * Message d'échec générique ; le mot de passe n'est jamais conservé.
 */
public final class LoginActivity extends Activity {

    private EditText server, login, password;
    private TextView error;
    private Button submit, modeDemo, modeServer;
    private boolean demo = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (NexFleetApp.get().session() != null) {
            open();
            return;
        }
        LinearLayout col = Ui.column(this, 14);
        col.setPadding(Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 24));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        icon.setBackground(Ui.rounded(this, Ui.NAVY, 20));
        icon.setClipToOutline(true);
        icon.setElevation(Ui.dp(this, 6));
        col.addView(icon);
        col.addView(Ui.largeTitle(this, "Bienvenue"));
        col.addView(Ui.muted(this, "NexFleet CAMTEL — missions, localisation, messages et catalogue 3D.", 15));

        LinearLayout seg = Ui.row(this, 0);
        seg.setBackground(Ui.rounded(this, Ui.FILL, 999));
        seg.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        modeDemo = segment("Démonstration", v -> setMode(true));
        modeServer = segment("Serveur NexFleet", v -> setMode(false));
        seg.addView(modeDemo, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f));
        seg.addView(modeServer, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f));
        col.addView(seg);

        server = field("Adresse du serveur (https://…)", InputType.TYPE_TEXT_VARIATION_URI);
        login = field("Identifiant", InputType.TYPE_TEXT_VARIATION_NORMAL);
        password = field("Mot de passe", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        password.setOnEditorActionListener((v, id, e) -> {
            attempt();
            return true;
        });
        col.addView(server);
        LinearLayout group = Ui.group(this);
        group.addView(login);
        View hair = new View(this);
        hair.setBackgroundColor(Ui.SEPARATOR);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        hp.leftMargin = Ui.dp(this, 16);
        group.addView(hair, hp);
        group.addView(password);
        col.addView(group);

        error = Ui.text(this, "", 13, Ui.RED, NexFleetApp.get().regular());
        error.setVisibility(View.GONE);
        col.addView(error);
        submit = Ui.button(this, "Se connecter", Ui.Style.FILLED, v -> attempt());
        col.addView(submit);

        TextView demoHeader = Ui.text(this, "COMPTES DE DÉMONSTRATION · MOT DE PASSE « demo »", 13, Ui.MUTED, NexFleetApp.get().regular());
        demoHeader.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), 0, 0);
        col.addView(demoHeader);
        LinearLayout accounts = Ui.group(this);
        String[][] demoRoles = {{"conducteur", "DRIVER"}, {"gestionnaire", "FLEET_MANAGER"}, {"maintenance", "MAINTENANCE"}, {"controleur", "CONTROLLER"}};
        for (String[] a : demoRoles) {
            if (!Models.demoLogins().contains(a[0])) continue;
            Role r = Role.valueOf(a[1]);
            if (accounts.getChildCount() > 0) accounts.addView(Ui.separator(this, 16));
            LinearLayout row = Ui.row(this, 12);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            row.setMinimumHeight(Ui.dp(this, 56));
            LinearLayout txt = Ui.column(this, 2);
            txt.addView(Ui.headline(this, r.label));
            txt.addView(Ui.muted(this, a[0], 12));
            row.addView(txt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.text(this, "›", 22, Ui.TERTIARY, NexFleetApp.get().regular()));
            row.setOnClickListener(v -> {
                setMode(true);
                login.setText(a[0]);
                password.setText("demo");
                attempt();
            });
            row.setContentDescription("Se connecter avec le compte de démonstration " + r.label);
            accounts.addView(row);
        }
        col.addView(accounts);
        col.addView(Ui.muted(this, "Connexion fédérée CAMTEL (OIDC) disponible après accord de la DSIR.", 12));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);
        sv.setBackground(Ui.backdrop());
        Ui.applyInsets(sv, true, true);
        setContentView(sv);
        setMode(true);
    }

    private Button segment(String label, View.OnClickListener l) {
        Button b = Ui.button(this, label, Ui.Style.PLAIN, l);
        b.setTextSize(13);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void setMode(boolean d) {
        demo = d;
        server.setVisibility(d ? View.GONE : View.VISIBLE);
        modeDemo.setBackground(d ? Ui.rounded(this, Ui.SURFACE, 999) : null);
        modeServer.setBackground(!d ? Ui.rounded(this, Ui.SURFACE, 999) : null);
        modeDemo.setElevation(d ? Ui.dp(this, 2) : 0);
        modeServer.setElevation(!d ? Ui.dp(this, 2) : 0);
        modeDemo.setTextColor(Ui.TEXT);
        modeServer.setTextColor(Ui.TEXT);
    }

    private EditText field(String hint, int variation) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setTypeface(NexFleetApp.get().regular());
        e.setTextSize(16);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.TERTIARY);
        e.setBackground(null);
        e.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        e.setMinHeight(Ui.dp(this, 52));
        e.setGravity(Gravity.CENTER_VERTICAL);
        if (variation == InputType.TYPE_TEXT_VARIATION_URI) e.setBackground(Ui.rounded(this, Ui.SURFACE, 16));
        return e;
    }

    private void attempt() {
        String l = login.getText().toString().trim();
        char[] p = password.getText().toString().toCharArray();
        String url = server.getText().toString().trim();
        if (l.isEmpty() || p.length == 0 || (!demo && !url.startsWith("https://") && !url.startsWith("http://10.0.2.2"))) {
            showError(!demo && !url.startsWith("https://") ? "Indiquez l'adresse HTTPS du serveur NexFleet." : "Renseignez l'identifiant et le mot de passe.");
            return;
        }
        error.setVisibility(View.GONE);
        submit.setEnabled(false);
        submit.setText("Connexion…");
        Repository repo = NexFleetApp.get().repository(demo, url);
        Ui.async(() -> repo.login(l, p), s -> {
            NexFleetApp.get().setSession(s);
            password.setText("");
            open();
        }, e -> {
            submit.setEnabled(true);
            submit.setText("Se connecter");
            password.setText("");
            boolean network = e instanceof Repository.RepositoryException
                    && ((Repository.RepositoryException) e).kind == Repository.RepositoryException.Kind.NETWORK;
            showError(network ? e.getMessage() : "Identifiant ou mot de passe incorrect.");
        });
    }

    private void showError(String m) {
        error.setText(m);
        error.setVisibility(View.VISIBLE);
    }

    private void open() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
