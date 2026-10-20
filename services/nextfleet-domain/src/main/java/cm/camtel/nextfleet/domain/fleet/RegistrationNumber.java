package cm.camtel.nextfleet.domain.fleet;

import java.util.Locale;
import java.util.Objects;

/**
 * Immatriculation normalisée (NF-10). Même règle que la colonne générée
 * {@code vehicle.registration_normalized} : majuscules, sans espace ni séparateur.
 * « ce-4401 lt » et « CE 4401 LT » désignent le même véhicule.
 */
public record RegistrationNumber(String raw, String normalized) {

    public RegistrationNumber {
        Objects.requireNonNull(raw, "immatriculation obligatoire");
        Objects.requireNonNull(normalized);
    }

    public static RegistrationNumber of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Immatriculation obligatoire");
        }
        String normalized = raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (normalized.length() < 4) {
            throw new IllegalArgumentException("Immatriculation trop courte : « " + raw.strip() + " »");
        }
        return new RegistrationNumber(raw.strip(), normalized);
    }

    public boolean sameVehicleAs(RegistrationNumber other) {
        return other != null && normalized.equals(other.normalized);
    }
}
