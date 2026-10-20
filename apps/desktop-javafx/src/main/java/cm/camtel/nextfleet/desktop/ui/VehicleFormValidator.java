package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.VehicleDraft;
import cm.camtel.nextfleet.domain.fleet.RegistrationNumber;

import java.math.BigDecimal;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation du formulaire d'ajout (écran 04, étape 1). Reprend les contraintes de l'API (Bean Validation)
 * pour signaler tôt ; le serveur reste l'autorité (unicité, droits).
 */
public final class VehicleFormValidator {

    public record Result(VehicleDraft draft, Map<String, String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public Result validate(String registration, String vin, String make, String model, String year,
                           String energy, String tankCapacity, String category, Year currentYear) {
        Map<String, String> errors = new LinkedHashMap<>();
        String reg = registration == null ? "" : registration.strip();
        if (reg.isEmpty()) {
            errors.put("registration", "Immatriculation obligatoire.");
        } else {
            try {
                RegistrationNumber.of(reg);
                if (reg.length() > 20) errors.put("registration", "20 caractères au maximum.");
            } catch (IllegalArgumentException e) {
                errors.put("registration", "Immatriculation trop courte (4 caractères alphanumériques minimum).");
            }
        }
        if (blank(make)) errors.put("make", "Marque obligatoire.");
        if (blank(model)) errors.put("model", "Modèle obligatoire.");
        Integer y = null;
        if (!blank(year)) {
            try {
                y = Integer.parseInt(year.strip());
                if (y < 1950 || y > currentYear.getValue() + 1) {
                    errors.put("year", "Année entre 1950 et " + (currentYear.getValue() + 1) + ".");
                }
            } catch (NumberFormatException e) {
                errors.put("year", "Année invalide.");
            }
        }
        BigDecimal tank = null;
        if (!blank(tankCapacity)) {
            try {
                tank = new BigDecimal(tankCapacity.strip().replace(',', '.'));
                if (tank.compareTo(BigDecimal.ONE) < 0 || tank.compareTo(BigDecimal.valueOf(1000)) > 0) {
                    errors.put("tank", "Capacité entre 1 et 1 000 litres.");
                }
            } catch (NumberFormatException e) {
                errors.put("tank", "Capacité invalide (nombre de litres).");
            }
        }
        String v = blank(vin) ? null : vin.strip().toUpperCase();
        if (v != null && !v.matches("[A-HJ-NPR-Z0-9]{11,17}")) {
            errors.put("vin", "Numéro de châssis : 11 à 17 caractères, sans I, O ni Q.");
        }
        VehicleDraft draft = errors.isEmpty()
                ? new VehicleDraft(reg, v, make.strip(), model.strip(), y, energy, tank, blank(category) ? null : category.strip())
                : null;
        return new Result(draft, errors);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
