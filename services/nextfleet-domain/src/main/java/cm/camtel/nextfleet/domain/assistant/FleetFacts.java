package cm.camtel.nextfleet.domain.assistant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Seul accès de l'assistant aux données : des LECTURES déjà restreintes au périmètre de l'utilisateur
 * (entité et sous-entités). L'interface ne contient aucune méthode d'écriture ; chaque fait porte sa référence
 * de source, citée dans la réponse.
 */
public interface FleetFacts {

    record VehicleFact(String registration, String label, String status, String sourceRef) { }

    /** Dépense rattachée à un véhicule : plein, facture, intervention. */
    record CostFact(String registration, LocalDate date, String category, BigDecimal amountXaf, String sourceRef) { }

    record DueFact(String kind, String subject, LocalDate dueOn, long daysLeft, String level, String sourceRef) { }

    record MissionFact(String reference, String registration, String driver, String status, String sourceRef) { }

    /** Consommation plein-à-plein d'une période fiable ; {@code null} si non calculable. */
    record ConsumptionFact(String registration, YearMonth month, BigDecimal litersPer100Km, String sourceRef) { }

    List<VehicleFact> vehicles();

    List<CostFact> costs(LocalDate fromInclusive, LocalDate toInclusive);

    List<DueFact> dueItems();

    List<MissionFact> missions();

    List<ConsumptionFact> consumption(YearMonth month);
}
