package cm.camtel.nextfleet.domain.gps;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qualification des points et statistiques de mission (NF-71, NF-73, chap. VII du V5).
 * Les distances sont calculées uniquement sur les points VALIDES ; un trajet troué ou trop imprécis
 * est qualifié « ESTIMATION » ; une position périmée n'est jamais affichée « EN DIRECT ».
 */
public final class TrackAnalyzer {

    public enum Quality { VALID, IMPRECISE, JUMP }

    public enum Reliability { FIABLE, ESTIMATION, INSUFFISANTE }

    public enum LiveState { DIRECT, DERNIERE_POSITION, AUCUNE_POSITION }

    public record Stop(Instant from, Instant to, double latitude, double longitude) {
        public Duration duration() { return Duration.between(from, to); }
    }

    public record TripStatistics(double distanceKm, Double maxObservedSpeedKmh, Double maxComputedSpeedKmh,
                                 Duration movingDuration, List<Stop> stops, int validPoints, int rejectedPoints,
                                 Reliability reliability, List<String> notes) { }

    public record LiveStatus(LiveState state, Instant lastRecordedAt, Duration age, Double accuracyM) { }

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private final TrackPolicy policy;

    public TrackAnalyzer(TrackPolicy policy) {
        this.policy = policy;
    }

    /** Distance géodésique (haversine, sphère moyenne) ; la base utilise PostGIS geography pour les rapports. */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Qualifie chaque point dans l'ordre chronologique. Les doublons de {@code clientFixId} sont ignorés. */
    public Map<String, Quality> qualify(List<GpsFix> fixes) {
        List<GpsFix> sorted = dedupSorted(fixes);
        Map<String, Quality> q = new LinkedHashMap<>();
        GpsFix lastValid = null;
        for (GpsFix f : sorted) {
            if (f.accuracyM() > policy.maxAccuracyM()) {
                q.put(f.clientFixId(), Quality.IMPRECISE);
                continue;
            }
            if (lastValid != null) {
                double seconds = Duration.between(lastValid.recordedAt(), f.recordedAt()).toMillis() / 1000.0;
                double d = distanceM(lastValid.latitude(), lastValid.longitude(), f.latitude(), f.longitude());
                if (seconds <= 0 ? d > policy.stopRadiusM() : d / seconds > policy.maxPlausibleSpeedMps()) {
                    q.put(f.clientFixId(), Quality.JUMP);
                    continue;
                }
            }
            q.put(f.clientFixId(), Quality.VALID);
            lastValid = f;
        }
        return q;
    }

    public TripStatistics statistics(List<GpsFix> fixes) {
        List<GpsFix> sorted = dedupSorted(fixes);
        Map<String, Quality> q = qualify(sorted);
        List<GpsFix> valid = sorted.stream().filter(f -> q.get(f.clientFixId()) == Quality.VALID).toList();
        List<String> notes = new ArrayList<>();
        int rejected = sorted.size() - valid.size();
        if (valid.size() < 2) {
            notes.add("Moins de deux points valides : distance et vitesse non calculables.");
            return new TripStatistics(0, null, null, Duration.ZERO, List.of(), valid.size(), rejected,
                    Reliability.INSUFFISANTE, notes);
        }
        double distance = 0;
        double maxComputed = 0;
        Duration moving = Duration.ZERO;
        int gaps = 0;
        for (int i = 1; i < valid.size(); i++) {
            GpsFix a = valid.get(i - 1), b = valid.get(i);
            double d = distanceM(a.latitude(), a.longitude(), b.latitude(), b.longitude());
            Duration dt = Duration.between(a.recordedAt(), b.recordedAt());
            distance += d;
            if (dt.compareTo(policy.maxGap()) > 0) {
                gaps++;
            } else if (!dt.isZero()) {
                maxComputed = Math.max(maxComputed, d / (dt.toMillis() / 1000.0));
                if (d > policy.stopRadiusM() / 5) moving = moving.plus(dt);
            }
        }
        Double maxObserved = valid.stream().map(GpsFix::speedMps).filter(s -> s != null)
                .max(Double::compare).map(s -> s * 3.6).orElse(null);
        Reliability reliability = Reliability.FIABLE;
        if (gaps > 0) {
            reliability = Reliability.ESTIMATION;
            notes.add(gaps + " interruption(s) de collecte > " + policy.maxGap().toMinutes()
                    + " min : distance estimée en ligne droite sur ces segments.");
        }
        if (rejected > 0) {
            notes.add(rejected + " point(s) exclu(s) (imprécis ou saut).");
            if (rejected * 4 > sorted.size()) reliability = Reliability.ESTIMATION;
        }
        return new TripStatistics(distance / 1000.0, maxObserved, maxComputed * 3.6, moving, stops(valid),
                valid.size(), rejected, reliability, notes);
    }

    /** Arrêts : points consécutifs restés dans le rayon pendant au moins la durée minimale. */
    List<Stop> stops(List<GpsFix> valid) {
        List<Stop> out = new ArrayList<>();
        int i = 0;
        while (i < valid.size()) {
            GpsFix anchor = valid.get(i);
            int j = i;
            while (j + 1 < valid.size() && distanceM(anchor.latitude(), anchor.longitude(),
                    valid.get(j + 1).latitude(), valid.get(j + 1).longitude()) <= policy.stopRadiusM()) {
                j++;
            }
            Duration d = Duration.between(anchor.recordedAt(), valid.get(j).recordedAt());
            if (j > i && d.compareTo(policy.minStopDuration()) >= 0) {
                out.add(new Stop(anchor.recordedAt(), valid.get(j).recordedAt(), anchor.latitude(), anchor.longitude()));
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    /** Statut affiché sur la carte : « EN DIRECT » seulement si la dernière position est récente. */
    public LiveStatus liveStatus(List<GpsFix> fixes, Instant now) {
        GpsFix last = dedupSorted(fixes).stream()
                .filter(f -> f.accuracyM() <= policy.maxAccuracyM())
                .reduce((a, b) -> b).orElse(null);
        if (last == null) return new LiveStatus(LiveState.AUCUNE_POSITION, null, null, null);
        Duration age = Duration.between(last.recordedAt(), now);
        LiveState s = !age.isNegative() && age.compareTo(policy.liveThreshold()) <= 0
                ? LiveState.DIRECT : LiveState.DERNIERE_POSITION;
        return new LiveStatus(s, last.recordedAt(), age, last.accuracyM());
    }

    private static List<GpsFix> dedupSorted(List<GpsFix> fixes) {
        Map<String, GpsFix> unique = new LinkedHashMap<>();
        for (GpsFix f : fixes) unique.putIfAbsent(f.clientFixId(), f);
        List<GpsFix> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(GpsFix::recordedAt).thenComparing(GpsFix::clientFixId));
        return sorted;
    }
}
