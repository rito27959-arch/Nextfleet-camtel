package cm.camtel.nextfleet.domain.gps;

import java.time.Instant;
import java.util.Objects;

/**
 * Position reçue d'un émetteur (téléphone Android en phase 1, traceur en phase 2).
 * {@code speedMps} est la vitesse OBSERVÉE par le capteur, distincte de la vitesse calculée.
 */
public record GpsFix(String clientFixId, Instant recordedAt, double latitude, double longitude,
                     double accuracyM, Double speedMps) {

    public GpsFix {
        Objects.requireNonNull(clientFixId);
        Objects.requireNonNull(recordedAt);
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Coordonnées hors limites");
        }
        if (accuracyM < 0) {
            throw new IllegalArgumentException("Précision négative");
        }
    }
}
