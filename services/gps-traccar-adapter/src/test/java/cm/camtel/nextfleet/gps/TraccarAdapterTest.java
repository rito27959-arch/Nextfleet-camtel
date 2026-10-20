package cm.camtel.nextfleet.gps;

import cm.camtel.nextfleet.domain.gps.GpsFix;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TraccarAdapterTest {

    /**
     * Corps tel que l'émet PositionForwarderJson : PositionData{position, device} sérialisé par l'ObjectMapper de
     * Traccar (dates ISO avec décalage, champs nuls omis, vitesse en nœuds, attributs libres).
     */
    static final String FORWARD = """
            {"position":{"id":81234,"attributes":{"batteryLevel":87.0,"distance":12.4,"totalDistance":15032.1,"motion":true},
             "deviceId":7,"protocol":"osmand","serverTime":"2026-10-20T08:15:02.120+00:00",
             "deviceTime":"2026-10-20T08:15:00.000+00:00","fixTime":"2026-10-20T08:15:00.000+00:00","valid":true,
             "latitude":3.5172,"longitude":11.5003,"altitude":712.0,"speed":21.6,"course":184.0,"accuracy":6.0,
             "network":null,"geofenceIds":null},
             "device":{"id":7,"attributes":{},"groupId":0,"calendarId":0,"name":"Téléphone mission","uniqueId":"AND-7F3K",
             "status":"online","lastUpdate":"2026-10-20T08:15:02.120+00:00","positionId":81233,"phone":null,"model":null,
             "contact":null,"category":null,"disabled":false,"expirationTime":null}}""";

    @Test
    void parsesTraccarJsonForwardWithKnotsAndOffsetDates() {
        var p = new TraccarForwardParser().parse(FORWARD);
        assertEquals("AND-7F3K", p.uniqueId());
        assertEquals("traccar:81234", p.fix().clientFixId());
        assertEquals(Instant.parse("2026-10-20T08:15:00Z"), p.fix().recordedAt());
        assertEquals(11.112, p.fix().speedMps(), 0.001, "21,6 nœuds ≈ 40 km/h");
        assertEquals(184.0, p.courseDeg());
        assertEquals("osmand", p.protocol());
    }

    @Test
    void rejectsIncompletePayloads() {
        var parser = new TraccarForwardParser();
        assertThrows(TraccarForwardParser.InvalidPayload.class, () -> parser.parse("{}"));
        assertThrows(TraccarForwardParser.InvalidPayload.class, () -> parser.parse("pas du json"));
        assertThrows(TraccarForwardParser.InvalidPayload.class, () -> parser.parse(FORWARD.replace("\"uniqueId\":\"AND-7F3K\"", "\"uniqueId\":\"\"")));
        assertThrows(TraccarForwardParser.InvalidPayload.class, () -> parser.parse(FORWARD.replace("\"latitude\":3.5172", "\"latitude\":93.5")));
    }

    @Test
    void osmAndRelayUsesTheParametersTraccarDecodes() {
        var fix = new GpsFix("f-1", Instant.parse("2026-10-20T08:15:00Z"), 3.5172, 11.5003, 6, 11.112);
        URI u = new OsmAndRelay(URI.create("http://traccar.interne:5055")).uriFor("AND-7F3K", fix, 184.0);
        assertEquals("http://traccar.interne:5055/?id=AND-7F3K&timestamp=1792484100&lat=3.5172&lon=11.5003&speed=21.600019&bearing=184&accuracy=6",
                u.toString());
    }

    @Test
    void missionTokenIsBoundSignedAndExpires() {
        var tokens = new MissionToken("cle-de-test-uniquement-32-octets!!".getBytes());
        UUID mission = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-20T08:00:00Z");
        String t = tokens.issue(mission, "AND-7F3K", now, Duration.ofHours(8));
        assertEquals(mission, tokens.verify(t, now.plusSeconds(60)).missionId());
        assertThrows(MissionToken.InvalidToken.class, () -> tokens.verify(t, now.plus(Duration.ofHours(8))));
        String forged = t.substring(0, t.indexOf('.') - 2) + "AA" + t.substring(t.indexOf('.'));
        assertThrows(MissionToken.InvalidToken.class, () -> tokens.verify(forged, now));
        var other = new MissionToken("autre-cle-de-test-32-octets-min!!!".getBytes());
        assertThrows(MissionToken.InvalidToken.class, () -> other.verify(t, now));
    }
}
