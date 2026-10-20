package cm.camtel.nextfleet.domain.fuel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Ravitaillement tel que saisi (NF-41). Le compteur peut être absent : il est alors
 * « non renseigné » (RG-07) et ne peut jamais servir de borne de calcul.
 *
 * @param odometerKm compteur relevé au moment du plein, {@code null} si non renseigné
 * @param fullTank   {@code true} si le réservoir a été rempli au niveau de référence
 */
public record FuelFillRecord(String id, Instant filledAt, BigDecimal odometerKm,
                             BigDecimal liters, boolean fullTank) {

    public FuelFillRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(filledAt, "filledAt");
        Objects.requireNonNull(liters, "liters");
        if (liters.signum() <= 0) {
            throw new IllegalArgumentException("Le volume d'un plein doit être strictement positif");
        }
        if (odometerKm != null && odometerKm.signum() < 0) {
            throw new IllegalArgumentException("Compteur négatif");
        }
    }
}
