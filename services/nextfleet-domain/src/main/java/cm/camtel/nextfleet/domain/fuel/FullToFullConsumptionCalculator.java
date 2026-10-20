package cm.camtel.nextfleet.domain.fuel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Méthode plein-à-plein (cahier V4, chap. 10, NF-42, REC-05).
 * <pre>
 *   L/100 km = 100 × (litres ajoutés APRÈS le plein initial, jusqu'au plein final INCLUS)
 *                    / (compteur final − compteur initial)
 * </pre>
 * Le volume du plein initial est exclu ; les pleins partiels intermédiaires et le plein final sont inclus.
 * Un plein partiel ne clôt pas la période. Arrondi : 2 décimales, HALF_UP (RG-06, arrondi documenté).
 */
public final class FullToFullConsumptionCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Calcule la consommation de la dernière période plein-à-plein complète disponible. */
    public ConsumptionResult latest(List<FuelFillRecord> fills) {
        List<ConsumptionResult> all = allPeriods(fills);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).isCalculated()) {
                return all.get(i);
            }
        }
        return all.isEmpty()
                ? ConsumptionResult.insufficient("Données insuffisantes : moins de deux pleins complets avec compteur relevé.")
                : all.get(all.size() - 1);
    }

    /** Une entrée par couple de pleins complets successifs, calculée ou qualifiée insuffisante. */
    public List<ConsumptionResult> allPeriods(List<FuelFillRecord> fills) {
        List<FuelFillRecord> sorted = new ArrayList<>(fills);
        sorted.sort(Comparator.comparing(FuelFillRecord::filledAt).thenComparing(FuelFillRecord::id));

        List<ConsumptionResult> results = new ArrayList<>();
        int startIdx = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (!sorted.get(i).fullTank()) {
                continue;
            }
            if (startIdx >= 0) {
                results.add(period(sorted, startIdx, i));
            }
            startIdx = i;
        }
        return results;
    }

    private ConsumptionResult period(List<FuelFillRecord> sorted, int startIdx, int endIdx) {
        FuelFillRecord start = sorted.get(startIdx);
        FuelFillRecord end = sorted.get(endIdx);
        if (start.odometerKm() == null) {
            return ConsumptionResult.insufficient("Données insuffisantes : compteur du plein initial " + start.id() + " non renseigné.");
        }
        if (end.odometerKm() == null) {
            return ConsumptionResult.insufficient("Données insuffisantes : compteur du plein final " + end.id() + " non renseigné.");
        }
        BigDecimal distance = end.odometerKm().subtract(start.odometerKm());
        if (distance.signum() <= 0) {
            return ConsumptionResult.insufficient("Données insuffisantes : distance nulle ou négative entre "
                    + start.id() + " (" + start.odometerKm() + " km) et " + end.id() + " (" + end.odometerKm()
                    + " km) — compteur incohérent à justifier (CAR-03).");
        }
        BigDecimal liters = BigDecimal.ZERO;
        List<String> counted = new ArrayList<>();
        for (int k = startIdx + 1; k <= endIdx; k++) {
            liters = liters.add(sorted.get(k).liters());
            counted.add(sorted.get(k).id());
        }
        BigDecimal value = liters.multiply(HUNDRED).divide(distance, 2, RoundingMode.HALF_UP);
        String explanation = String.format(
                "100 × %s L (pleins %s, plein initial %s exclu) / %s km (%s → %s km) = %s L/100 km",
                liters.stripTrailingZeros().toPlainString(), String.join(", ", counted), start.id(),
                distance.stripTrailingZeros().toPlainString(),
                start.odometerKm().stripTrailingZeros().toPlainString(),
                end.odometerKm().stripTrailingZeros().toPlainString(), value.toPlainString());
        return new ConsumptionResult(ConsumptionResult.Status.CALCULATED, value, liters, distance,
                start.id(), end.id(), List.copyOf(counted), explanation);
    }
}
