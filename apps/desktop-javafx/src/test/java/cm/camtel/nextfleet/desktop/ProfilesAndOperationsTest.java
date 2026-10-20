package cm.camtel.nextfleet.desktop;

import cm.camtel.nextfleet.desktop.data.AuditChain;
import cm.camtel.nextfleet.desktop.data.DemoFleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import cm.camtel.nextfleet.desktop.data.Ops;
import cm.camtel.nextfleet.desktop.ui.AppShell;
import cm.camtel.nextfleet.desktop.ui.AppShell.Section;
import cm.camtel.nextfleet.domain.chat.Chat;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 2 côté bureau, sur la passerelle de démonstration (mêmes règles que le serveur et la base). */
class ProfilesAndOperationsTest {

    private final DemoFleetGateway gw = new DemoFleetGateway(Clock.systemUTC(), Duration.ZERO);

    private Session as(String login) {
        return gw.login(login, "demo".toCharArray());
    }

    @Test
    void sixProfilesEachWithItsOwnNavigation() {
        assertEquals(Section.OVERVIEW, AppShell.sectionsFor(as("gestionnaire"), false).get(0));
        assertEquals(Section.MY_MISSIONS, AppShell.sectionsFor(as("conducteur"), false).get(0));
        assertEquals(Section.WORKSHOP, AppShell.sectionsFor(as("maintenance"), false).get(0));
        assertEquals(Section.OVERVIEW, AppShell.sectionsFor(as("controleur"), false).get(0));
        assertEquals(List.of(Section.USERS), AppShell.sectionsFor(as("admin"), false), "admin : aucun écran métier");
        Session auditor = as("auditeur");
        assertTrue(AppShell.visibleFor(Section.AUDIT, auditor));
        assertFalse(AppShell.visibleFor(Section.MESSAGES, auditor), "auditeur : pas de messagerie");
        assertFalse(AppShell.visibleFor(Section.ASSISTANT, auditor));
        Session driver = as("conducteur");
        assertFalse(AppShell.visibleFor(Section.MISSIONS, driver), "conducteur : seulement ses missions");
        assertFalse(AppShell.visibleFor(Section.OVERVIEW, driver), "conducteur : pas de coûts");
        assertTrue(AppShell.visibleFor(Section.MESSAGES, driver));
    }

    @Test
    void serverSideRefusalsMatchTheProfile() {
        as("admin");
        assertEquals(GatewayException.Kind.FORBIDDEN, assertThrows(GatewayException.class, () -> gw.vehicles(null, null, 0, 10)).kind());
        assertEquals(GatewayException.Kind.FORBIDDEN, assertThrows(GatewayException.class, gw::conversations).kind());
        as("conducteur");
        assertTrue(gw.dueDates(30).stream().allMatch(d -> d.vehicle()), "permis et CNI des collègues jamais exposés au conducteur");
        assertEquals(GatewayException.Kind.FORBIDDEN, assertThrows(GatewayException.class, gw::dashboard).kind());
        as("gestionnaire");
        assertEquals(GatewayException.Kind.FORBIDDEN, assertThrows(GatewayException.class, gw::users).kind());
        assertEquals(GatewayException.Kind.FORBIDDEN, assertThrows(GatewayException.class, gw::auditTrail).kind());
    }

    @Test
    void missionLifecycleLimitsCollectionToTheMission() {
        as("conducteur");
        List<Ops.Mission> mine = gw.missions();
        assertTrue(mine.stream().allMatch(m -> m.driverName().equals("Félix Kamga")), "le conducteur ne voit que ses missions");
        Ops.Mission next = mine.stream().filter(m -> m.status() == Ops.MissionStatus.AUTHORIZED).findFirst().orElseThrow();
        assertTrue(gw.missionTrack(next.id()).isEmpty(), "aucune position avant le démarrage");
        assertThrows(GatewayException.class, () -> gw.startMission(next.id(), "NOTICE-INCONNUE"));
        Ops.Mission started = gw.startMission(next.id(), "NOTICE-GPS-v1");
        assertEquals(Ops.MissionStatus.ACTIVE, started.status());
        assertEquals("AND-DEMO", started.deviceId());
        Ops.Mission done = gw.finishMission(next.id());
        int points = gw.missionTrack(next.id()).size();
        assertTrue(gw.missionTrack(next.id()).stream().noneMatch(f -> f.recordedAt().isAfter(done.finishedAt())));
        assertEquals(points, gw.missionTrack(next.id()).size(), "trace figée après la clôture");

        as("gestionnaire");
        Ops.Mission active = gw.missions().stream().filter(m -> m.reference().equals("MIS-2026-014")).findFirst().orElseThrow();
        assertTrue(gw.missionTrack(active.id()).size() > 100, "trace de la mission en cours");
        assertEquals(GatewayException.Kind.FORBIDDEN,
                assertThrows(GatewayException.class, () -> gw.startMission(active.id(), "NOTICE-GPS-v1")).kind());
    }

    @Test
    void messagingIsConfidentialIdempotentAndHonestAboutDelivery() {
        as("conducteur");
        assertEquals(Set.of("conv-015"), Set.copyOf(gw.conversations().stream().map(Ops.Conversation::id).toList()));
        assertThrows(GatewayException.class, () -> gw.messages("conv-014"), "conversation d'une autre mission");
        long before = gw.messages("conv-015").size();
        Ops.ChatLine sent = gw.sendMessage("conv-015", "client-42", "Je pars dans dix minutes.");
        gw.sendMessage("conv-015", "client-42", "Je pars dans dix minutes.");
        assertEquals(before + 1, gw.messages("conv-015").size(), "réémission sans doublon");
        assertEquals(Chat.DeliveryState.ENVOYE, sent.state(), "pas de « Lu » inventé : le gestionnaire ne l'a pas lu");
        as("gestionnaire");
        assertTrue(gw.conversations().stream().filter(c -> c.id().equals("conv-015")).findFirst().orElseThrow().unread() >= 1);
        gw.markConversationRead("conv-015");
        as("conducteur");
        Ops.ChatLine last = gw.messages("conv-015").get((int) before);
        assertEquals(Chat.DeliveryState.LU, last.state());
    }

    @Test
    void assistantReadOnlyWithSourcesAndControlledActivation() {
        as("gestionnaire");
        var a = gw.askAssistant("Coût des véhicules CE 3205 KJ et LT 5520 CE ce mois-ci ?");
        assertFalse(a.refused(), a.text());
        assertFalse(a.sources().isEmpty());
        assertTrue(gw.askAssistant("Supprime le véhicule CE 3205 KJ").refused());
        as("controleur");
        assertEquals(GatewayException.Kind.FORBIDDEN,
                assertThrows(GatewayException.class, () -> gw.decideAssistant(false, null, null, "Fin du pilote")).kind());
        as("admin");
        assertEquals(GatewayException.Kind.VALIDATION,
                assertThrows(GatewayException.class, () -> gw.decideAssistant(true, "", "RSSI", "Pilote")).kind());
        gw.decideAssistant(false, null, null, "Suspension pour revue");
        as("gestionnaire");
        assertTrue(gw.askAssistant("Coût ce mois ?").text().contains("désactivé"));
    }

    @Test
    void auditTrailIsChainedAndTamperEvident() {
        as("gestionnaire");
        gw.askAssistant("Quelles missions sont en cours ?");
        as("auditeur");
        List<Ops.AuditEntry> trail = gw.auditTrail();
        assertEquals(-1, AuditChain.firstBroken(trail), "chaîne intègre");
        assertTrue(trail.stream().anyMatch(e -> e.action().equals("ASSISTANT_ANSWERED")));
        assertTrue(trail.stream().filter(e -> e.action().equals("MESSAGE_SENT")).allMatch(e -> e.detail().contains("contenu non journalisé")));
        var tampered = new java.util.ArrayList<>(trail);
        Ops.AuditEntry e = tampered.get(tampered.size() / 2);
        tampered.set(tampered.size() / 2, new Ops.AuditEntry(e.seq(), e.at(), "Quelqu'un d'autre", e.action(), e.object(), e.detail(),
                e.hash(), e.previousHash()));
        assertEquals(e.seq(), AuditChain.firstBroken(tampered), "modification détectée");
    }
}
