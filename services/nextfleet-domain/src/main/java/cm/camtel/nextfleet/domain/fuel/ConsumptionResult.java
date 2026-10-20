package cm.camtel.nextfleet.domain.fuel;

import java.math.BigDecimal;
import java.util.List;

/**
 * Résultat d'un calcul plein-à-plein. Le calcul expose toujours ses entrées (NF-42) ;
 * en l'absence de bornes fiables, le statut est {@link Status#INSUFFICIENT_DATA} et aucune
 * valeur n'est inventée (NF-23, RG-07).
 */
public record ConsumptionResult(Status status,
                                BigDecimal litersPer100Km,
                                BigDecimal litersCounted,
                                BigDecimal distanceKm,
                                String startFillId,
                                String endFillId,
                                List<String> countedFillIds,
                                String explanation) {

    public enum Status { CALCULATED, INSUFFICIENT_DATA }

    public static ConsumptionResult insufficient(String explanation) {
        return new ConsumptionResult(Status.INSUFFICIENT_DATA, null, null, null, null, null, List.of(), explanation);
    }

    public boolean isCalculated() {
        return status == Status.CALCULATED;
    }
}
