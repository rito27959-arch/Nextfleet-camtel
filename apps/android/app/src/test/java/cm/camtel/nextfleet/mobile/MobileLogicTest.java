package cm.camtel.nextfleet.mobile;

import cm.camtel.nextfleet.domain.chat.Chat;
import cm.camtel.nextfleet.mobile.data.Catalogue;
import cm.camtel.nextfleet.mobile.data.DemoRepository;
import cm.camtel.nextfleet.mobile.data.Models;
import cm.camtel.nextfleet.mobile.data.Repository;
import cm.camtel.nextfleet.mobile.tracking.FixCodec;
import cm.camtel.nextfleet.mobile.tracking.Uploader;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Logique de l'application Android exécutée sur la JVM (sans appareil). */
public class MobileLogicTest {

    @Test
    public void fixRoundTripAndBatchMatchTheGatewayContract() throws Exception {
        FixCodec.Fix f = new FixCodec.Fix("f-1", Instant.parse("2026-10-20T08:15:00Z"), 3.5172, 11.5003, 6, 11.1, 184.0);
        FixCodec.Fix back = FixCodec.decode(FixCodec.encode(f));
        assertEquals("f-1", back.clientFixId);
        assertEquals(3.5172, back.lat, 1e-9);
        assertEquals(184.0, back.bearingDeg, 1e-9);
        JSONObject batch = new JSONObject(FixCodec.batch(List.of(f)));
        JSONObject first = batch.getJSONArray("fixes").getJSONObject(0);
        assertEquals("2026-10-20T08:15:00Z", first.getString("recordedAt"));
        assertTrue(first.has("accuracyM") && first.has("speedMps") && first.has("bearingDeg"));
        List<FixCodec.Fix> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) tooMany.add(f);
        try {
            FixCodec.batch(tooMany);
            fail("501 positions refusées");
        } catch (IllegalArgumentException expected) {
            // contrat : 500 au plus
        }
    }

    @Test
    public void gatewayOutcomesSettleAcceptedDuplicateAndRejectedOnly() throws Exception {
        Uploader.Result r = Uploader.parse("{\"results\":[{\"clientFixId\":\"a\",\"outcome\":\"ACCEPTED\"},"
                + "{\"clientFixId\":\"b\",\"outcome\":\"DUPLICATE\"},{\"clientFixId\":\"c\",\"outcome\":\"REJECTED\",\"reason\":\"HORS_PERIODE\"},"
                + "{\"clientFixId\":\"d\",\"outcome\":\"\"}],\"relayedToTraccar\":1,\"relayFailures\":0}");
        assertEquals(1, r.accepted);
        assertEquals(1, r.duplicates);
        assertEquals(1, r.rejected);
        assertEquals(List.of("a", "b", "c"), r.settled);   // « d » sans décision : renvoyé plus tard
    }

    @Test
    public void embeddedCatalogueOnlyContainsRedistributableModels() throws Exception {
        Path cat = Paths.get("../../../shared/assets3d/catalogue.json");
        String json = new String(Files.readAllBytes(cat), StandardCharsets.UTF_8);
        Catalogue c = Catalogue.parse(json);
        assertTrue(c.entries.size() >= 6);
        assertEquals("nexfleet-pickup", c.forCategory("Pick-up").id);
        assertEquals("nexfleet-minibus", c.forCategory("minibus").id);
        for (Catalogue.Entry e : c.entries) {
            assertTrue(e.file, Files.exists(Paths.get("../../../shared/assets3d").resolve(e.file)));
            assertFalse(e.licence.isEmpty());
        }
        Catalogue filtered = Catalogue.parse("{\"models\":[{\"id\":\"x\",\"label\":\"X\",\"file\":\"x.glb\",\"redistributable\":false}]}");
        assertTrue("modèle non redistribuable jamais embarqué", filtered.entries.isEmpty());
    }

    @Test
    public void demoRepositoryAppliesTheSameRulesAsTheServer() {
        DemoRepository repo = new DemoRepository();
        repo.login("conducteur", "demo".toCharArray());
        List<Models.Mission> mine = repo.missions();
        for (Models.Mission m : mine) assertEquals("Félix Kamga", m.driverName);
        Models.Mission next = null;
        for (Models.Mission m : mine) if (m.status == Models.MissionStatus.AUTHORIZED) next = m;
        assertNotNull(next);
        try {
            repo.start(next.id, "NOTICE-INCONNUE", "AND-TEST01");
            fail("notice inconnue refusée");
        } catch (Repository.RepositoryException e) {
            assertEquals(Repository.RepositoryException.Kind.VALIDATION, e.kind);
        }
        Models.StartResult r = repo.start(next.id, "NOTICE-GPS-v1", "AND-TEST01");
        assertEquals(Models.MissionStatus.ACTIVE, r.mission.status);
        assertEquals("AND-TEST01", r.mission.deviceId);
        assertEquals(Models.MissionStatus.FINISHED, repo.finish(next.id).status);

        int before = repo.messages("conv-015").size();
        Models.ChatLine sent = repo.send("conv-015", "client-1", "Je pars.");
        repo.send("conv-015", "client-1", "Je pars.");
        assertEquals("réémission sans doublon", before + 1, repo.messages("conv-015").size());
        assertEquals(Chat.DeliveryState.ENVOYE, sent.state);
        try {
            repo.messages("conv-014");
            fail("conversation d'une autre mission");
        } catch (Repository.RepositoryException e) {
            assertEquals(Repository.RepositoryException.Kind.FORBIDDEN, e.kind);
        }

        DemoRepository admin = new DemoRepository();
        admin.login("admin", "demo".toCharArray());
        try {
            admin.missions();
            fail("administrateur sans données métier");
        } catch (Repository.RepositoryException e) {
            assertEquals(Repository.RepositoryException.Kind.FORBIDDEN, e.kind);
        }
    }
}
