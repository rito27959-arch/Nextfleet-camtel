package cm.camtel.nextfleet.mobile.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Adresse du serveur NexFleet : choix enregistré sur l'appareil, sinon serveur de production livré avec l'application. */
public final class ServerConfig {

    /** Serveur de production (HTTPS). */
    public static final String DEFAULT_URL = "https://api-production-a1d8.up.railway.app";

    private ServerConfig() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("nexfleet.server", Context.MODE_PRIVATE);
    }

    public static String url(Context c) {
        String v = prefs(c).getString("url", "");
        return v == null || v.trim().isEmpty() ? DEFAULT_URL : v.trim();
    }

    public static void save(Context c, String url) {
        prefs(c).edit().putString("url", url.trim()).apply();
    }

    /** HTTPS obligatoire (l'émulateur Android peut joindre le poste de développement en clair via 10.0.2.2). */
    public static boolean valid(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://10.0.2.2")) && url.length() > 10;
    }
}
