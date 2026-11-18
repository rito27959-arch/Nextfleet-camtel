package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import cm.camtel.nextfleet.mobile.data.ServerConfig;

/**
 * Mise à jour automatique : au démarrage, l'application demande au serveur NexFleet la version attendue
 * ({@code GET /api/v1/app/update}). Si elle est plus récente et qu'un APK est publié, il est téléchargé puis
 * l'installateur d'Android s'ouvre : l'utilisateur confirme d'un appui (Android l'impose hors Play Store).
 * La première fois, Android demande d'autoriser NexFleet à installer des applications.
 */
public final class AppUpdater {

    private static boolean checked;

    private AppUpdater() { }

    public static void check(Activity a) {
        if (checked) return;
        checked = true;
        Context app = a.getApplicationContext();
        String current = currentVersion(app);
        Ui.async(() -> fetch(ServerConfig.url(app)), info -> {
            if (info == null || a.isFinishing() || !newer(info[0], current)) return;
            SharedPreferences p = app.getSharedPreferences("mise_a_jour", Context.MODE_PRIVATE);
            new AlertDialog.Builder(a)
                    .setTitle("NexFleet " + info[0])
                    .setMessage("Une nouvelle version est disponible. Elle se télécharge maintenant ; Android vous demandera ensuite "
                            + "de confirmer l'installation. Vos données restent sur le serveur.")
                    .setPositiveButton("Mettre à jour", (d, w) -> download(a, info[0], info[1]))
                    .setNegativeButton("Plus tard", (d, w) -> p.edit().putString("reporte", info[0]).apply())
                    .show();
        }, e -> { });
    }

    /** [version, url de l'APK] ou {@code null}. */
    static String[] fetch(String server) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(server.replaceAll("/+$", "") + "/api/v1/app/update").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                String apk = o.optString("androidApk", "");
                if (apk.isEmpty() || "null".equals(apk) || !apk.startsWith("https://")) return null;
                return new String[]{o.optString("version", ""), apk};
            }
        } catch (Exception e) {
            return null;
        }
    }

    static boolean newer(String remote, String local) {
        int[] a = parse(remote), b = parse(local);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }

    private static int[] parse(String v) {
        if (v == null) return null;
        String[] p = v.trim().split("[.-]");
        if (p.length < 3) return null;
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    private static void download(Activity a, String version, String url) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a).setTitle("Autorisation nécessaire")
                    .setMessage("Autorisez NexFleet à installer ses mises à jour (une seule fois), puis revenez dans l'application.")
                    .setPositiveButton("Ouvrir les réglages", (d, w) -> {
                        checked = false;   // on vérifiera de nouveau au retour
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                    })
                    .setNegativeButton("Annuler", null).show();
            return;
        }
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView label = Ui.muted(a, "Téléchargement…", 14);
        android.widget.LinearLayout box = Ui.column(a, 10);
        box.setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), 0);
        box.addView(bar);
        box.addView(label);
        AlertDialog dlg = new AlertDialog.Builder(a).setTitle("NexFleet " + version).setView(box).setCancelable(false).show();
        Ui.async(() -> {
            try {
                File dir = new File(a.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Stockage indisponible.");
                File f = new File(dir, "nexfleet-" + version.replaceAll("[^0-9.]", "") + ".apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new IllegalStateException("Paquet introuvable (HTTP " + c.getResponseCode() + ").");
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            a.runOnUiThread(() -> {
                                bar.setProgress(pct);
                                label.setText("Téléchargement… " + pct + " %");
                            });
                        }
                    }
                }
                return f;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage() == null ? "Téléchargement impossible." : e.getMessage(), e);
            }
        }, f -> {
            dlg.dismiss();
            Uri uri = androidx.core.content.FileProvider.getUriForFile(a, a.getPackageName() + ".updates", f);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        }, e -> {
            dlg.dismiss();
            new AlertDialog.Builder(a).setTitle("Mise à jour impossible").setMessage(e.getMessage())
                    .setPositiveButton("OK", null).show();
        });
    }
}
