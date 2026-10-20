package cm.camtel.nextfleet.domain.gps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackAnalyzerTest {

    private final TrackAnalyzer analyzer = new TrackAnalyzer(TrackPolicy.demoDefaults());
    private static final Instant T0 = Instant.parse("2026-10-20T08:00:00Z");

    private static GpsFix fix(String id, int minutes, double lat, double lon, double acc) {
        return new GpsFix(id, T0.plus(Duration.ofMinutes(minutes)), lat, lon, acc, null);
    }

    @Test
    @DisplayName("Distance géodésique cohérente avec PostGIS (≈1,57 km entre deux points de Yaoundé)")
    void distanceMatchesPostgis() {
        double m = TrackAnalyzer.distanceM(3.8480, 11.5021, 3.8580, 11.5121);
        assertEquals(1570, m, 30);
    }

    @Test
    @DisplayName("Points imprécis et sauts exclus ; trajet annoté")
    void qualification() {
        List<GpsFix> fixes = List.of(
                fix("a", 0, 3.8480, 11.5021, 8),
                fix("b", 1, 3.8530, 11.5071, 9),
                fix("c", 2, 3.8580, 11.5121, 300),       // imprécis
                fix("d", 3, 4.0500, 9.7000, 5),          // saut Yaoundé → Douala en 2 min
                fix("e", 4, 3.8630, 11.5171, 7));
        var q = analyzer.qualify(fixes);
        assertEquals(TrackAnalyzer.Quality.IMPRECISE, q.get("c"));
        assertEquals(TrackAnalyzer.Quality.JUMP, q.get("d"));
        assertEquals(TrackAnalyzer.Quality.VALID, q.get("e"));
        var stats = analyzer.statistics(fixes);
        assertEquals(3, stats.validPoints());
        assertEquals(2, stats.rejectedPoints());
        assertEquals(TrackAnalyzer.Reliability.ESTIMATION, stats.reliability());
        assertTrue(stats.distanceKm() > 2.2 && stats.distanceKm() < 2.5, "distance " + stats.distanceKm());
    }

    @Test
    @DisplayName("NF-74 : un lot rejoué (mêmes client_fix_id) ne double ni points ni distance")
    void replayIdempotent() {
        List<GpsFix> once = List.of(fix("a", 0, 3.8480, 11.5021, 8), fix("b", 1, 3.8530, 11.5071, 8));
        List<GpsFix> twice = new ArrayList<>(once);
        twice.addAll(once);
        assertEquals(analyzer.statistics(once).distanceKm(), analyzer.statistics(twice).distanceKm(), 1e-9);
        assertEquals(2, analyzer.statistics(twice).validPoints());
    }

    @Test
    @DisplayName("Trou de collecte > 5 min → distance qualifiée ESTIMATION")
    void gap() {
        var s = analyzer.statistics(List.of(fix("a", 0, 3.8480, 11.5021, 8), fix("b", 1, 3.8490, 11.5031, 8),
                fix("c", 20, 3.8600, 11.5150, 8)));
        assertEquals(TrackAnalyzer.Reliability.ESTIMATION, s.reliability());
        assertFalse(s.notes().isEmpty());
    }

    @Test
    @DisplayName("Arrêt prolongé détecté (≥ 5 min dans un rayon de 50 m)")
    void stops() {
        var s = analyzer.statistics(List.of(
                fix("a", 0, 3.8480, 11.5021, 8), fix("b", 2, 3.84802, 11.50212, 8),
                fix("c", 4, 3.84801, 11.50209, 8), fix("d", 6, 3.84803, 11.50211, 8),
                fix("e", 8, 3.8530, 11.5071, 8)));
        assertEquals(1, s.stops().size());
        assertEquals(Duration.ofMinutes(6), s.stops().get(0).duration());
    }

    @Test
    @DisplayName("REC-GPS : faux « direct » impossible — position de 10 min affichée comme DERNIÈRE POSITION")
    void liveState() {
        var fixes = List.of(fix("a", 0, 3.8480, 11.5021, 8));
        assertEquals(TrackAnalyzer.LiveState.DIRECT, analyzer.liveStatus(fixes, T0.plusSeconds(60)).state());
        var stale = analyzer.liveStatus(fixes, T0.plus(Duration.ofMinutes(10)));
        assertEquals(TrackAnalyzer.LiveState.DERNIERE_POSITION, stale.state());
        assertEquals(Duration.ofMinutes(10), stale.age());
        assertEquals(TrackAnalyzer.LiveState.AUCUNE_POSITION, analyzer.liveStatus(List.of(), T0).state());
    }

    @Test
    @DisplayName("Moins de deux points valides → statistiques INSUFFISANTES, pas de vitesse inventée")
    void insufficient() {
        var s = analyzer.statistics(List.of(fix("a", 0, 3.8480, 11.5021, 8)));
        assertEquals(TrackAnalyzer.Reliability.INSUFFISANTE, s.reliability());
        assertNull(s.maxComputedSpeedKmh());
    }
}
