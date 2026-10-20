package cm.camtel.nextfleet.domain.gps;

import cm.camtel.nextfleet.domain.gps.MissionTrackingGate.Authorization;
import cm.camtel.nextfleet.domain.gps.MissionTrackingGate.Mission;
import cm.camtel.nextfleet.domain.gps.MissionTrackingGate.MissionStatus;
import cm.camtel.nextfleet.domain.gps.MissionTrackingGate.Reason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MissionTrackingGateTest {

    private final MissionTrackingGate gate = new MissionTrackingGate();
    private final UUID mid = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-10-20T08:00:00Z");
    private final Instant end = Instant.parse("2026-10-20T11:00:00Z");

    private Optional<Mission> mission(MissionStatus s, Instant finished) {
        return Optional.of(new Mission(mid, "MIS-2026-014", UUID.randomUUID(), s, start, finished));
    }

    @Test
    void acceptsOnlyDuringActiveMissionWithAuthorization() {
        var auth = List.of(new Authorization(mid, "AND-7F3K", start, null));
        var d = gate.evaluate("AND-7F3K", start.plusSeconds(600), auth, id -> mission(MissionStatus.ACTIVE, null));
        assertTrue(d.accepted());
        assertEquals("MIS-2026-014", d.mission().reference());
    }

    @Test
    void refusesUnknownSourceBeforeStartAndAfterEnd() {
        var auth = List.of(new Authorization(mid, "AND-7F3K", start, end));
        assertEquals(Reason.SOURCE_INCONNUE, gate.evaluate("TRK-999", start.plusSeconds(60), auth, id -> mission(MissionStatus.ACTIVE, null)).reason());
        assertEquals(Reason.HORS_PERIODE, gate.evaluate("AND-7F3K", start.minusSeconds(60), auth, id -> mission(MissionStatus.ACTIVE, null)).reason());
        assertEquals(Reason.HORS_PERIODE, gate.evaluate("AND-7F3K", end.plusSeconds(60), auth, id -> mission(MissionStatus.FINISHED, end)).reason());
    }

    @Test
    void refusesWhenAuthorizationRevokedOrMissionNotStarted() {
        var revoked = List.of(new Authorization(mid, "AND-7F3K", start, start.plusSeconds(300)));
        assertEquals(Reason.AUTORISATION_REVOQUEE,
                gate.evaluate("AND-7F3K", start.plusSeconds(900), revoked, id -> mission(MissionStatus.ACTIVE, null)).reason());
        var auth = List.of(new Authorization(mid, "AND-7F3K", start, null));
        assertEquals(Reason.AUCUNE_MISSION_ACTIVE,
                gate.evaluate("AND-7F3K", start.plusSeconds(60), auth, id -> mission(MissionStatus.AUTHORIZED, null)).reason());
        assertEquals(Reason.DONNEE_INVALIDE, gate.evaluate(" ", start, auth, id -> Optional.empty()).reason());
    }

    @Test
    void lateReplayOfAPointRecordedDuringTheMissionIsStillAccepted() {
        // Tampon hors ligne : le point a été relevé pendant la mission, il arrive après la clôture (NF-74).
        var auth = List.of(new Authorization(mid, "AND-7F3K", start, end));
        assertTrue(gate.evaluate("AND-7F3K", end.minusSeconds(30), auth, id -> mission(MissionStatus.FINISHED, end)).accepted());
    }
}
