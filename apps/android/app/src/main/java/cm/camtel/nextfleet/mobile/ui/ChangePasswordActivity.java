package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import cm.camtel.nextfleet.mobile.NexFleetApp;

/**
 * Choix du mot de passe : imposé après une connexion avec un mot de passe provisoire (le serveur refuse tout le reste
 * tant qu'il n'est pas remplacé), ou volontaire depuis le profil. Annuler un changement imposé déconnecte.
 */
public final class ChangePasswordActivity extends Activity {

    private boolean forced;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Motion.sheetScreen(this);
        if (NexFleetApp.get().session() == null) {
            finish();
            return;
        }
        forced = getIntent().getBooleanExtra("forced", false);
        EditText[] fields = new EditText[3];
        Forms[] holder = new Forms[1];
        Forms f = new Forms(this, "Mot de passe", "Enregistrer", () -> save(holder[0], fields));
        holder[0] = f;
        f.intro(forced ? "Vous vous êtes connecté avec un mot de passe provisoire remis par l'administration. "
                + "Choisissez un mot de passe personnel que vous seul connaissez."
                : "Votre nouveau mot de passe remplace l'actuel ; vos autres sessions ouvertes seront fermées.");
        LinearLayout g = f.group();
        int pwd = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        fields[0] = f.field(g, forced ? "Provisoire" : "Actuel", "Mot de passe", pwd);
        fields[1] = f.field(g, "Nouveau", "12 caractères au moins", pwd);
        fields[2] = f.field(g, "Confirmation", "Nouveau mot de passe", pwd);
        f.footer("12 caractères au moins, mêlant lettres et chiffres ou symboles, sans votre identifiant.");
    }

    private void save(Forms f, EditText[] fields) {
        String cur = fields[0].getText().toString();
        String next = fields[1].getText().toString();
        String conf = fields[2].getText().toString();
        if (cur.isEmpty()) {
            f.fail("Saisissez votre mot de passe " + (forced ? "provisoire." : "actuel."));
            return;
        }
        boolean letter = false, other = false;
        for (char c : next.toCharArray()) {
            if (Character.isLetter(c)) letter = true;
            else other = true;
        }
        if (next.length() < 12 || !letter || !other) {
            f.fail("Le nouveau mot de passe doit contenir 12 caractères au moins, avec lettres et chiffres ou symboles.");
            return;
        }
        if (!next.equals(conf)) {
            f.fail("Les deux saisies du nouveau mot de passe diffèrent.");
            return;
        }
        f.busy();
        Ui.async(() -> {
            NexFleetApp.get().repository().changePassword(cur.toCharArray(), next.toCharArray());
            return true;
        }, ok -> {
            Motion.success(getWindow().getDecorView());
            android.widget.Toast.makeText(this, "Mot de passe enregistré.", android.widget.Toast.LENGTH_SHORT).show();
            if (forced) startActivity(new Intent(this, MainActivity.class));
            finish();
        }, e -> f.fail(e.getMessage()));
    }

    @Override
    public void finish() {
        if (forced && NexFleetApp.get().session() != null && NexFleetApp.get().session().mustChangePassword) {
            // changement imposé abandonné : la session ne sert à rien, on la ferme proprement
            Ui.async(() -> {
                NexFleetApp.get().logout();
                return true;
            }, ok -> { }, e -> { });
            startActivity(new Intent(this, LoginActivity.class));
        }
        super.finish();
        Motion.sheetClosing(this);
    }
}
