package cm.camtel.nextfleet.mobile.tracking;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Format des positions : en clair dans le lot envoyé (TLS), chiffré au repos dans le tampon local. */
public final class FixCodec {

    public static final class Fix {
        public final String clientFixId;
        public final Instant recordedAt;
        public final double lat, lon, accuracyM;
        public final Double speedMps, bearingDeg;

        public Fix(String clientFixId, Instant recordedAt, double lat, double lon, double accuracyM, Double speedMps, Double bearingDeg) {
            this.clientFixId = clientFixId;
            this.recordedAt = recordedAt;
            this.lat = lat;
            this.lon = lon;
            this.accuracyM = accuracyM;
            this.speedMps = speedMps;
            this.bearingDeg = bearingDeg;
        }
    }

    /** Résultat par point renvoyé par la passerelle (/gps/android/fixes). */
    public static final class Outcome {
        public final String clientFixId, outcome, reason;

        Outcome(String clientFixId, String outcome, String reason) {
            this.clientFixId = clientFixId;
            this.outcome = outcome;
            this.reason = reason;
        }

        /** ACCEPTÉ, DOUBLON ou REFUSÉ : le point est traité, il ne sera jamais renvoyé. */
        public boolean settled() {
            return "ACCEPTED".equals(outcome) || "DUPLICATE".equals(outcome) || "REJECTED".equals(outcome);
        }
    }

    private FixCodec() { }

    public static JSONObject toJson(Fix f) throws JSONException {
        JSONObject o = new JSONObject().put("clientFixId", f.clientFixId).put("recordedAt", f.recordedAt.toString())
                .put("lat", f.lat).put("lon", f.lon).put("accuracyM", f.accuracyM);
        if (f.speedMps != null) o.put("speedMps", f.speedMps);
        if (f.bearingDeg != null) o.put("bearingDeg", f.bearingDeg);
        return o;
    }

    public static Fix fromJson(JSONObject o) throws JSONException {
        return new Fix(o.getString("clientFixId"), Instant.parse(o.getString("recordedAt")), o.getDouble("lat"), o.getDouble("lon"),
                o.getDouble("accuracyM"), o.has("speedMps") ? o.getDouble("speedMps") : null,
                o.has("bearingDeg") ? o.getDouble("bearingDeg") : null);
    }

    public static byte[] encode(Fix f) throws JSONException {
        return toJson(f).toString().getBytes(StandardCharsets.UTF_8);
    }

    public static Fix decode(byte[] b) throws JSONException {
        return fromJson(new JSONObject(new String(b, StandardCharsets.UTF_8)));
    }

    /** Corps du lot conforme au contrat (500 points au plus). */
    public static String batch(List<Fix> fixes) throws JSONException {
        if (fixes.size() > 500) throw new IllegalArgumentException("500 positions au plus par lot");
        JSONArray a = new JSONArray();
        for (Fix f : fixes) a.put(toJson(f));
        return new JSONObject().put("fixes", a).toString();
    }

    public static List<Outcome> outcomes(String response) throws JSONException {
        JSONArray a = new JSONObject(response).getJSONArray("results");
        List<Outcome> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            out.add(new Outcome(r.optString("clientFixId", ""), r.optString("outcome", ""), r.optString("reason", null)));
        }
        return out;
    }
}
