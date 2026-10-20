package cm.camtel.nextfleet.desktop;

import cm.camtel.nextfleet.desktop.map.TileSource;
import cm.camtel.nextfleet.desktop.map.WebMercator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapMathTest {

    @Test
    void webMercatorRoundTripsAndMatchesStandardTiles() {
        // Yaoundé : tuile x=2178 au zoom 12, y=1002 au zoom 11 (formule standard des tuiles)
        assertEquals(2178, (int) Math.floor(WebMercator.lonToX(11.5021, 12) / 256));
        assertEquals(1002, (int) Math.floor(WebMercator.latToY(3.848, 11) / 256));
        double x = WebMercator.lonToX(9.7679, 15), y = WebMercator.latToY(4.0511, 15);
        assertEquals(9.7679, WebMercator.xToLon(x, 15), 1e-9);
        assertEquals(4.0511, WebMercator.yToLat(y, 15), 1e-9);
    }

    @Test
    void fitZoomFramesYaoundeToDoualaInAWindow() {
        int z = WebMercator.fitZoom(3.848, 9.7679, 4.0511, 11.5021, 900, 600, 60, 19);
        assertEquals(9, z, "1,73° de longitude = 631 px au zoom 9 (≤ 780), 1 262 px au zoom 10");
        assertTrue(WebMercator.metersPerPixel(3.85, 15) < 5);
    }

    @Test
    void tileServerMustBeHttps() {
        assertThrows(IllegalArgumentException.class, () -> new TileSource("http://tuiles.exemple/{z}/{x}/{y}.png", "x", 19, Path.of("/tmp")));
        new TileSource("https://tuiles.camtel.invalid/{z}/{x}/{y}.png", "CAMTEL", 19, Path.of("/tmp"));
    }
}
