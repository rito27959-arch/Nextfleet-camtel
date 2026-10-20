package cm.camtel.nextfleet.desktop.map;

/**
 * Projection Web Mercator (EPSG:3857) en pixels « monde » pour des tuiles de 256 px, comme MapLibre, Leaflet et
 * les serveurs de tuiles standard. Pure et testée sans interface.
 */
public final class WebMercator {

    public static final int TILE = 256;
    public static final double MAX_LAT = 85.05112878;

    private WebMercator() { }

    public static double worldSize(int zoom) {
        return TILE * Math.pow(2, zoom);
    }

    public static double lonToX(double lon, int zoom) {
        return (lon + 180.0) / 360.0 * worldSize(zoom);
    }

    public static double latToY(double lat, int zoom) {
        double l = Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat)));
        return (1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2 * worldSize(zoom);
    }

    public static double xToLon(double x, int zoom) {
        return x / worldSize(zoom) * 360.0 - 180.0;
    }

    public static double yToLat(double y, int zoom) {
        double n = Math.PI - 2 * Math.PI * y / worldSize(zoom);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** Plus grand zoom (≤ maxZoom) pour lequel l'emprise tient dans la vue, avec une marge en pixels. */
    public static int fitZoom(double minLat, double minLon, double maxLat, double maxLon, double width, double height,
                              double padding, int maxZoom) {
        for (int z = maxZoom; z > 1; z--) {
            double w = lonToX(maxLon, z) - lonToX(minLon, z);
            double h = latToY(minLat, z) - latToY(maxLat, z);
            if (w <= width - 2 * padding && h <= height - 2 * padding) return z;
        }
        return 2;
    }

    /** Mètres représentés par un pixel à cette latitude et ce zoom (échelle graphique). */
    public static double metersPerPixel(double lat, int zoom) {
        return 156543.03392 * Math.cos(Math.toRadians(lat)) / Math.pow(2, zoom);
    }
}
