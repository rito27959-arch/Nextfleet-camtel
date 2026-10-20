package cm.camtel.nextfleet.mobile.tracking;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * État de collecte persistant (stockage privé de l'application, exclu des sauvegardes) : mission active, jeton de
 * mission éphémère, passerelle, identifiant d'émetteur. Sans mission active, aucune collecte n'est possible.
 */
public final class TrackingState {

    private static final String FILE = "nexfleet_tracking";
    private final SharedPreferences p;

    private TrackingState(Context c) {
        p = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static TrackingState of(Context c) {
        return new TrackingState(c);
    }

    /** Identifiant d'émetteur de l'appareil (uniqueId Traccar), créé une seule fois : « AND-XXXXXX ». */
    public String deviceId() {
        String id = p.getString("deviceId", null);
        if (id == null) {
            String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder b = new StringBuilder("AND-");
            for (int i = 0; i < 6; i++) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
            id = b.toString();
            p.edit().putString("deviceId", id).apply();
        }
        return id;
    }

    public void begin(String missionId, String reference, Instant startedAt, String token, String gatewayUrl, boolean demo) {
        p.edit().putString("missionId", missionId).putString("reference", reference).putLong("startedAt", startedAt.toEpochMilli())
                .putString("token", token).putString("gateway", gatewayUrl).putBoolean("demo", demo).apply();
    }

    /**
     * Fin de mission : la collecte s'arrête. Le jeton reste conservé à part (« vidange ») le temps d'envoyer les
     * points relevés PENDANT la mission et encore en tampon ; la passerelle les accepte car relevés dans la période.
     */
    public void end() {
        SharedPreferences.Editor e = p.edit();
        if (!demo() && token() != null) {
            e.putString("drainMission", missionId()).putString("drainToken", token()).putString("drainGateway", gateway());
        }
        e.remove("missionId").remove("reference").remove("startedAt").remove("token").remove("gateway").apply();
    }

    public String drainMission() {
        return p.getString("drainMission", null);
    }

    public String drainToken() {
        return p.getString("drainToken", null);
    }

    public String drainGateway() {
        return p.getString("drainGateway", null);
    }

    public void drained() {
        p.edit().remove("drainMission").remove("drainToken").remove("drainGateway").apply();
    }

    public boolean active() {
        return p.getString("missionId", null) != null;
    }

    public String missionId() {
        return p.getString("missionId", null);
    }

    public String reference() {
        return p.getString("reference", "");
    }

    public Instant startedAt() {
        long v = p.getLong("startedAt", 0);
        return v == 0 ? null : Instant.ofEpochMilli(v);
    }

    public String token() {
        return p.getString("token", null);
    }

    public String gateway() {
        return p.getString("gateway", null);
    }

    public boolean demo() {
        return p.getBoolean("demo", true);
    }

    /** À la déconnexion : la mission en cours est conservée (elle se termine par sa clôture), le reste est effacé. */
    public void clearSession() {
        if (!active()) end();
    }
}
