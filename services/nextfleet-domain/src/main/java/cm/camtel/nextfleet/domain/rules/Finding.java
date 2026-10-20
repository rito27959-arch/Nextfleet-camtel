package cm.camtel.nextfleet.domain.rules;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Constatation produite par une règle déterministe (NF-43, NF-80).
 * Une constatation est un INDICE à instruire : elle ne qualifie jamais une fraude et
 * n'entraîne aucune sanction (NF-44, NF-83). Elle désigne la règle, sa version, les données,
 * les unités, le seuil et la qualité des données (réception NF-80).
 *
 * @param dedupKey clé stable : une même constatation rejouée n'ouvre pas deux dossiers
 */
public record Finding(String ruleId,
                      int ruleVersion,
                      Severity severity,
                      DataQuality dataQuality,
                      String vehicleId,
                      String summary,
                      Map<String, Object> evidence,
                      String dedupKey) {

    public enum Severity { INFO, LOW, MEDIUM, HIGH }

    public enum DataQuality { FIABLE, PARTIELLE, INSUFFISANTE }

    public Finding {
        Objects.requireNonNull(ruleId);
        Objects.requireNonNull(severity);
        Objects.requireNonNull(dataQuality);
        Objects.requireNonNull(summary);
        Objects.requireNonNull(dedupKey);
        evidence = Map.copyOf(new LinkedHashMap<>(evidence));
    }
}
