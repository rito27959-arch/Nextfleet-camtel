package cm.camtel.nextfleet.desktop;

import cm.camtel.nextfleet.desktop.data.ModelCatalogue;
import cm.camtel.nextfleet.desktop.three.GlbLoader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogueTest {

    @Test
    void embeddedCatalogueOnlyListsRedistributableModelsThatExist() throws Exception {
        ModelCatalogue c = ModelCatalogue.load();
        assertTrue(c.entries().size() >= 6, "5 silhouettes de flotte + Toy Car CC0");
        for (ModelCatalogue.Entry e : c.entries()) {
            byte[] glb = c.bytes(e);
            assertEquals(0x46546C67, (glb[0] & 0xFF) | (glb[1] & 0xFF) << 8 | (glb[2] & 0xFF) << 16 | (glb[3] & 0xFF) << 24, e.file() + " : en-tête glTF");
            assertFalse(e.licence().isBlank());
            assertFalse(e.author().isBlank());
        }
    }

    @Test
    void fleetSilhouettesParseIntoMeshes() throws Exception {
        ModelCatalogue c = ModelCatalogue.load();
        for (ModelCatalogue.Entry e : c.entries()) {
            if (!e.id().startsWith("nexfleet-")) continue; // sans texture : chargeable sans boîte à outils graphique
            GlbLoader.Model m = new GlbLoader().load(c.bytes(e));
            assertTrue(m.triangles() > 300, e.id());
            assertTrue(m.max()[0] > m.min()[0]);
        }
    }

    @Test
    void nonRedistributableOrUnsafeEntriesAreIgnored() throws Exception {
        String json = """
                {"models":[{"id":"a","file":"models/a.glb","redistributable":false},
                           {"id":"b","file":"../secret.glb","redistributable":true},
                           {"id":"c","label":"C","category":"SUV","file":"models/c.glb","redistributable":true,"matches":["Toyota Prado"]}]}""";
        ModelCatalogue c = ModelCatalogue.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, c.entries().size());
        assertEquals("c", c.forVehicle(null, "TOYOTA PRADO TXL").orElseThrow().id());
        assertEquals("c", c.forVehicle("suv", null).orElseThrow().id());
        assertTrue(c.forVehicle("Moto", "Yamaha").isEmpty());
    }
}
