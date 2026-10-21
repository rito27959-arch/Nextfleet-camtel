package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.data.Models;
import cm.camtel.nextfleet.mobile.data.Repository;

/**
 * Actions terrain du conducteur : demande de bon carburant, saisie d'un plein avec photo du reçu, signalement de panne,
 * contrôle du véhicule avant départ (12 points). Mode choisi par l'extra « mode ».
 */
public final class FieldActivity extends Activity {

    static final String FUEL = "fuel", FILL = "fill", WORK = "work", CHECK = "check";
    private static final int PICK_RECEIPT = 41;

    private Forms f;
    private Runnable submit = () -> { };
    private byte[] receipt;
    private String receiptName;
    private TextView receiptLabel;
    private final String requestKey = "android-" + UUID.randomUUID();   // renvoi après coupure : aucun doublon

    static Intent open(Activity a, String mode) {
        return new Intent(a, FieldActivity.class).putExtra("mode", mode);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Motion.sheetScreen(this);
        if (NexFleetApp.get().session() == null) {
            finish();
            return;
        }
        String mode = getIntent().getStringExtra("mode");
        String title = FUEL.equals(mode) ? "Bon carburant" : FILL.equals(mode) ? "Plein" : WORK.equals(mode) ? "Signaler une panne" : "Contrôle du véhicule";
        String action = FUEL.equals(mode) || WORK.equals(mode) ? "Envoyer" : "Enregistrer";
        f = new Forms(this, title, action, () -> submit.run());
        TextView loading = Ui.muted(this, "Chargement…", 15);
        f.col.addView(loading, 0);
        Repository repo = NexFleetApp.get().repository();
        Ui.async(() -> {
            List<Object> r = new ArrayList<>();
            r.add(repo.vehicles());
            if (FUEL.equals(mode) || FILL.equals(mode)) r.add(repo.suppliers());
            if (FILL.equals(mode)) r.add(repo.fuelOrders());
            if (WORK.equals(mode)) r.add(repo.maintenanceCategories());
            if (CHECK.equals(mode)) r.add(repo.inspectionPoints());
            return r;
        }, r -> {
            f.col.removeView(loading);
            @SuppressWarnings("unchecked") List<Models.VehicleRef> vehicles = (List<Models.VehicleRef>) r.get(0);
            if (vehicles.isEmpty()) {
                f.fail("Aucun véhicule dans votre périmètre.");
                return;
            }
            switch (mode == null ? "" : mode) {
                case FUEL: {
                    @SuppressWarnings("unchecked") List<Models.Supplier> s = (List<Models.Supplier>) r.get(1);
                    fuel(vehicles, s);
                    break;
                }
                case FILL: {
                    @SuppressWarnings("unchecked") List<Models.Supplier> s = (List<Models.Supplier>) r.get(1);
                    @SuppressWarnings("unchecked") List<Models.FuelOrder> o = (List<Models.FuelOrder>) r.get(2);
                    fill(vehicles, s, o);
                    break;
                }
                case WORK: {
                    @SuppressWarnings("unchecked") List<Models.Category> c = (List<Models.Category>) r.get(1);
                    work(vehicles, c);
                    break;
                }
                default: {
                    @SuppressWarnings("unchecked") List<Models.InspectionPoint> p = (List<Models.InspectionPoint>) r.get(1);
                    check(vehicles, p);
                }
            }
        }, e -> {
            f.col.removeView(loading);
            f.fail(e.getMessage());
        });
    }

    // ---------------------------------------------------------------- bon de carburant

    private void fuel(List<Models.VehicleRef> vehicles, List<Models.Supplier> suppliers) {
        if (suppliers.isEmpty()) {
            f.fail("Aucune station enregistrée : le gestionnaire du parc doit d'abord ajouter les stations-service.");
            return;
        }
        f.intro("Le bon est soumis à validation ; une autre personne habilitée le valide avant le plein.");
        LinearLayout g = f.group();
        Spinner vehicle = f.spinner(g, "Véhicule", vehicles);
        Spinner station = f.spinner(g, "Station", suppliers);
        EditText liters = f.number(g, "Litres", "Ex. 60");
        EditText amount = f.number(g, "Montant (FCFA)", "Ex. 50 400");
        EditText days = f.number(g, "Valable (jours)", "7");
        days.setText("7");
        EditText reason = f.text(g, "Motif", "Ex. Mission Obala");
        submit = () -> {
            try {
                Models.VehicleRef v = (Models.VehicleRef) vehicle.getSelectedItem();
                Models.Supplier s = (Models.Supplier) station.getSelectedItem();
                double l = Forms.num(liters, "Litres", true);
                double m = Forms.num(amount, "Montant", true);
                Double d = Forms.num(days, "Validité", false);
                LocalDate until = LocalDate.now(ZoneId.of("Africa/Douala")).plusDays(d == null ? 7 : Math.max(0, Math.round(d)));
                f.busy();
                Ui.async(() -> NexFleetApp.get().repository().requestFuel(v.id, s.id, l, m, until, Forms.str(reason)), o -> done("Bon " + o.orderNumber
                        + " envoyé pour validation."), e -> f.fail(e.getMessage()));
            } catch (IllegalArgumentException ex) {
                f.fail(ex.getMessage());
            }
        };
    }

    // ---------------------------------------------------------------- plein

    private void fill(List<Models.VehicleRef> vehicles, List<Models.Supplier> suppliers, List<Models.FuelOrder> orders) {
        if (suppliers.isEmpty()) {
            f.fail("Aucune station enregistrée : le gestionnaire du parc doit d'abord ajouter les stations-service.");
            return;
        }
        f.intro("Le compteur et le plein complet permettent de calculer la consommation réelle. Joignez la photo du reçu.");
        LinearLayout g = f.group();
        Spinner vehicle = f.spinner(g, "Véhicule", vehicles);
        List<Object> usable = new ArrayList<>();
        for (Models.FuelOrder o : orders) if (o.usable()) usable.add(o);
        usable.add("Sans bon (exception)");
        Spinner order = f.spinner(g, "Bon", usable);
        Spinner station = f.spinner(g, "Station", suppliers);
        LinearLayout g2 = f.group();
        EditText liters = f.number(g2, "Litres", "Ex. 45,5");
        EditText price = f.number(g2, "Prix au litre", "Ex. 840");
        EditText km = f.number(g2, "Compteur (km)", "Ex. 12700");
        CheckBox full = f.check(g2, "Plein complet (réservoir rempli)", true);
        EditText ref = f.text(g2, "N° du reçu", "Ticket");
        EditText exception = f.text(g2, "Motif d'exception", "Obligatoire sans bon");
        receiptPicker();
        submit = () -> {
            try {
                Models.VehicleRef v = (Models.VehicleRef) vehicle.getSelectedItem();
                Object o = order.getSelectedItem();
                String orderId = o instanceof Models.FuelOrder ? ((Models.FuelOrder) o).id : null;
                if (orderId == null && Forms.str(exception).length() < 5) throw new IllegalArgumentException("Sans bon, le motif d'exception est obligatoire.");
                if (Forms.str(ref).isEmpty()) throw new IllegalArgumentException("N° du reçu : champ obligatoire.");
                double l = Forms.num(liters, "Litres", true);
                double p = Forms.num(price, "Prix au litre", true);
                Double odo = Forms.num(km, "Compteur", false);
                Models.Supplier s = (Models.Supplier) station.getSelectedItem();
                byte[] photo = receipt;
                String photoName = receiptName;
                f.busy();
                Ui.async(() -> {
                    Repository repo = NexFleetApp.get().repository();
                    String fileId = photo == null ? null : repo.upload("FUEL_RECEIPT", v.id, photoName, photo);
                    repo.recordFill(requestKey, orderId, orderId == null ? Forms.str(exception) : null, v.id, s.id, Instant.now(), odo, l, p,
                            full.isChecked(), Forms.str(ref), fileId);
                    return v.registration;
                }, reg -> done("Plein enregistré pour " + reg + "."), e -> f.fail(e.getMessage()));
            } catch (IllegalArgumentException ex) {
                f.fail(ex.getMessage());
            }
        };
    }

    private void receiptPicker() {
        LinearLayout g = f.group();
        LinearLayout row = Ui.row(this, 10);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        receiptLabel = Ui.muted(this, "Aucune photo du reçu", 14);
        row.addView(receiptLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button pick = Ui.button(this, "Photo du reçu", Ui.Style.TINTED, v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp", "application/pdf"});
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Photo ou PDF du reçu"), PICK_RECEIPT);
        });
        row.addView(pick);
        g.addView(row);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_RECEIPT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("illisible");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 10 * 1024 * 1024) throw new IllegalArgumentException("Fichier trop volumineux (10 Mo maximum).");
            }
            receipt = out.toByteArray();
            receiptName = name(uri);
            receiptLabel.setText(receiptName + " · " + Math.max(1, receipt.length / 1024) + " Ko");
            receiptLabel.setTextColor(Ui.TEXT);
        } catch (java.io.IOException | IllegalArgumentException e) {
            receipt = null;
            f.fail(e instanceof IllegalArgumentException ? e.getMessage() : "Fichier illisible.");
        }
    }

    private String name(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // nom par défaut
        }
        return "recu.jpg";
    }

    // ---------------------------------------------------------------- panne

    private void work(List<Models.VehicleRef> vehicles, List<Models.Category> categories) {
        f.intro("L'atelier reçoit la demande et la qualifie. Décrivez ce que vous constatez (bruit, voyant, fuite…).");
        LinearLayout g = f.group();
        Spinner vehicle = f.spinner(g, "Véhicule", vehicles);
        Spinner cat = f.spinner(g, "Catégorie", categories);
        EditText what = f.field(g, "Description", "Ex. Bruit au freinage",
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        what.setMinLines(2);
        submit = () -> {
            Models.VehicleRef v = (Models.VehicleRef) vehicle.getSelectedItem();
            Models.Category c = (Models.Category) cat.getSelectedItem();
            if (Forms.str(what).length() < 3) {
                f.fail("Décrivez la panne (3 caractères au moins).");
                return;
            }
            f.busy();
            Ui.async(() -> NexFleetApp.get().repository().requestWork(v.id, c.code, Forms.str(what)),
                    id -> done("Demande envoyée à l'atelier pour " + v.registration + "."), e -> f.fail(e.getMessage()));
        };
    }

    // ---------------------------------------------------------------- contrôle 12 points

    private void check(List<Models.VehicleRef> vehicles, List<Models.InspectionPoint> points) {
        f.intro("Pour chaque point : OK, à surveiller ou défaut. Un défaut se décrit ; un défaut critique ouvre automatiquement une demande à l'atelier.");
        LinearLayout g = f.group();
        Spinner vehicle = f.spinner(g, "Véhicule", vehicles);
        EditText km = f.number(g, "Compteur (km)", "Facultatif");
        Map<String, RadioGroup> states = new LinkedHashMap<>();
        Map<String, EditText> comments = new LinkedHashMap<>();
        Map<String, CheckBox> critical = new LinkedHashMap<>();
        for (Models.InspectionPoint p : points) {
            LinearLayout box = f.group();
            LinearLayout inner = Ui.column(this, 4);
            inner.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            inner.addView(Ui.headline(this, p.label));
            inner.addView(Ui.muted(this, p.hint, 13));
            RadioGroup rg = new RadioGroup(this);
            rg.setOrientation(RadioGroup.HORIZONTAL);
            String[][] opts = {{"OK", "OK"}, {"A_SURVEILLER", "À surveiller"}, {"DEFAUT", "Défaut"}};
            for (String[] o : opts) {
                RadioButton rb = new RadioButton(this);
                rb.setText(o[1]);
                rb.setTag(o[0]);
                rb.setId(View.generateViewId());
                rb.setTypeface(NexFleetApp.get().regular());
                rg.addView(rb);
                if ("OK".equals(o[0])) rb.setChecked(true);
            }
            EditText comment = new EditText(this);
            comment.setHint("Décrivez le défaut");
            comment.setVisibility(View.GONE);
            comment.setTypeface(NexFleetApp.get().regular());
            CheckBox crit = new CheckBox(this);
            crit.setText("Critique : véhicule à ne pas utiliser");
            crit.setVisibility(View.GONE);
            rg.setOnCheckedChangeListener((group, id) -> {
                View sel = group.findViewById(id);
                boolean defect = sel != null && "DEFAUT".equals(sel.getTag());
                comment.setVisibility(defect ? View.VISIBLE : View.GONE);
                crit.setVisibility(defect ? View.VISIBLE : View.GONE);
            });
            inner.addView(rg);
            inner.addView(comment);
            inner.addView(crit);
            box.addView(inner);
            states.put(p.code, rg);
            comments.put(p.code, comment);
            critical.put(p.code, crit);
        }
        submit = () -> {
            try {
                Models.VehicleRef v = (Models.VehicleRef) vehicle.getSelectedItem();
                Map<String, String[]> items = new LinkedHashMap<>();
                for (Map.Entry<String, RadioGroup> e : states.entrySet()) {
                    View sel = e.getValue().findViewById(e.getValue().getCheckedRadioButtonId());
                    String st = sel == null ? "OK" : (String) sel.getTag();
                    String comment = Forms.str(comments.get(e.getKey()));
                    if ("DEFAUT".equals(st) && comment.isEmpty()) throw new IllegalArgumentException("Décrivez chaque défaut constaté.");
                    items.put(e.getKey(), new String[]{st, comment, "DEFAUT".equals(st) && critical.get(e.getKey()).isChecked() ? "1" : "0"});
                }
                Double odo = Forms.num(km, "Compteur", false);
                f.busy();
                Ui.async(() -> NexFleetApp.get().repository().inspect(v.id, "AVANT_MISSION", odo, items, null), result -> {
                    String label = "NON_CONFORME".equals(result) ? "non conforme : l'atelier est prévenu" : "RESERVES".equals(result)
                            ? "conforme avec réserves" : "conforme";
                    done("Contrôle enregistré : " + label + ".");
                }, e -> f.fail(e.getMessage()));
            } catch (IllegalArgumentException ex) {
                f.fail(ex.getMessage());
            }
        };
    }

    private void done(String message) {
        Motion.success(getWindow().getDecorView());
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        Motion.sheetClosing(this);
    }
}
