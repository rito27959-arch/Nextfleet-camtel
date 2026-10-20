package cm.camtel.nextfleet.domain.gps;

import java.time.Duration;

/**
 * Seuils de qualification des traces. Ils sont arrêtés avec la DSIR, la DS et le métier
 * (chap. 39) : ils sont paramétrés, jamais codés en dur dans les écrans.
 *
 * @param maxAccuracyM        au-delà, le point est IMPRÉCIS et exclu des statistiques
 * @param maxPlausibleSpeedMps vitesse implicite au-delà de laquelle un point est un SAUT
 * @param maxGap              trou de collecte au-delà duquel la distance devient une ESTIMATION
 * @param stopRadiusM         rayon d'immobilité pour détecter un arrêt
 * @param minStopDuration     durée minimale d'un arrêt
 * @param liveThreshold       âge maximal d'une position pour l'afficher « EN DIRECT »
 */
public record TrackPolicy(double maxAccuracyM, double maxPlausibleSpeedMps, Duration maxGap,
                          double stopRadiusM, Duration minStopDuration, Duration liveThreshold) {

    /** Valeurs de DÉMONSTRATION à faire approuver ; non validées par CAMTEL. */
    public static TrackPolicy demoDefaults() {
        return new TrackPolicy(50, 55.6 /* 200 km/h */, Duration.ofMinutes(5), 50,
                Duration.ofMinutes(5), Duration.ofMinutes(2));
    }
}
