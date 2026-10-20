package cm.camtel.nextfleet.domain.assistant;

import cm.camtel.nextfleet.domain.assistant.FleetFacts.ConsumptionFact;
import cm.camtel.nextfleet.domain.assistant.FleetFacts.CostFact;
import cm.camtel.nextfleet.domain.assistant.FleetFacts.DueFact;
import cm.camtel.nextfleet.domain.assistant.FleetFacts.MissionFact;
import cm.camtel.nextfleet.domain.assistant.FleetFacts.VehicleFact;
import cm.camtel.nextfleet.domain.assistant.IntentParser.Intent;
import cm.camtel.nextfleet.domain.security.Permission;
import cm.camtel.nextfleet.domain.security.Role;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FleetAssistantTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private final List<FleetAssistant.Run> runs = new ArrayList<>();
    private final FleetAssistant assistant = new FleetAssistant(runs::add);
    private final AssistantActivation.State on = AssistantActivation.decide(true, "DS-2026-041", "RSSI fictif",
            "Pilote BUT Yaoundé", "Admin technique", Role.ADMIN.permissions(), Instant.parse("2026-10-20T07:00:00Z"));

    /** Faits FICTIFS, déjà restreints au périmètre de l'utilisateur. */
    private final FleetFacts facts = new FleetFacts() {
        public List<VehicleFact> vehicles() {
            return List.of(new VehicleFact("CE 3205 KJ", "Toyota Hilux", "En service", "vehicle:b1"),
                    new VehicleFact("LT 4471 AB", "Mitsubishi L200", "En mission", "vehicle:b2"),
                    new VehicleFact("CE 1180 HN", "Toyota Land Cruiser", "En maintenance", "vehicle:b3"));
        }

        public List<CostFact> costs(LocalDate from, LocalDate to) {
            return List.of(
                    new CostFact("CE 3205 KJ", LocalDate.of(2026, 10, 3), "Carburant", new BigDecimal("84000"), "fuel_fill:F-1021"),
                    new CostFact("CE 3205 KJ", LocalDate.of(2026, 10, 14), "Entretien", new BigDecimal("125000"), "maintenance:M-311"),
                    new CostFact("LT 4471 AB", LocalDate.of(2026, 10, 9), "Carburant", new BigDecimal("61500"), "fuel_fill:F-1033"),
                    new CostFact("LT 4471 AB", LocalDate.of(2026, 9, 28), "Carburant", new BigDecimal("70000"), "fuel_fill:F-0990"))
                    .stream().filter(c -> !c.date().isBefore(from) && !c.date().isAfter(to)).toList();
        }

        public List<DueFact> dueItems() {
            return List.of(new DueFact("Assurance", "CE 3205 KJ", TODAY.plusDays(10), 10, "A_ECHEANCE", "vehicle_document:D-7"),
                    new DueFact("Vignette", "LT 4471 AB", TODAY.minusDays(5), -5, "EXPIRE", "vehicle_document:D-9"));
        }

        public List<MissionFact> missions() {
            return List.of(new MissionFact("MIS-2026-014", "LT 4471 AB", "A. Nkoulou", "ACTIVE", "mission:MIS-2026-014"),
                    new MissionFact("MIS-2026-012", "CE 3205 KJ", "B. Fouda", "FINISHED", "mission:MIS-2026-012"));
        }

        public List<ConsumptionFact> consumption(YearMonth month) {
            return List.of(new ConsumptionFact("CE 3205 KJ", month, new BigDecimal("11.43"), "fuel_period:CE3205KJ:" + month),
                    new ConsumptionFact("LT 4471 AB", month, null, "fuel_period:LT4471AB:" + month));
        }
    };

    @Test
    void scenarioE_costOfNamedVehiclesThisMonthWithFiguresPeriodFiltersAndSources() {
        var a = assistant.ask("gestionnaire", Role.FLEET_MANAGER.permissions(), on, facts,
                "Coût des véhicules CE-3205-KJ, LT 4471 AB et CE 9999 ZZ ce mois-ci ?", TODAY);
        assertFalse(a.refused());
        assertEquals(Intent.COUT, a.intent());
        assertEquals("octobre 2026", a.period());
        assertTrue(a.text().contains("270 500 XAF"), a.text());                       // 84 000 + 125 000 + 61 500
        assertTrue(a.text().contains("CE 9999 ZZ : aucun véhicule correspondant dans votre périmètre"), a.text());
        assertEquals("209 000 XAF", a.figures().get(0).value());
        assertEquals("Total", a.figures().get(2).label());
        assertEquals(List.of("fuel_fill:F-1021", "maintenance:M-311", "fuel_fill:F-1033"),
                a.sources().stream().map(FleetAssistant.Source::ref).toList(), "septembre exclu");
        assertTrue(a.filters().get(0).contains("CE 3205 KJ"));
        assertEquals(1, runs.size());
        assertEquals(3, runs.get(0).sourceRefs().size(), "interrogation journalisée avec ses sources");
    }

    @Test
    void refusesWhenDisabledOrWithoutPermissionAndStillLogs() {
        var off = assistant.ask("gestionnaire", Role.FLEET_MANAGER.permissions(), AssistantActivation.State.disabledByDefault(),
                facts, "Coût ce mois ?", TODAY);
        assertTrue(off.refused());
        assertTrue(off.text().contains("désactivé"));
        assertTrue(assistant.ask("auditeur", Role.AUDITOR.permissions(), on, facts, "Coût ce mois ?", TODAY).refused());
        var noCost = assistant.ask("x", Set.of(Permission.ASSISTANT_USE, Permission.VEHICLE_READ), on, facts, "Coût de CE 3205 KJ ?", TODAY);
        assertTrue(noCost.refused());
        assertTrue(noCost.text().contains("coûts"));
        assertEquals(3, runs.size());
        assertTrue(runs.stream().allMatch(FleetAssistant.Run::refused));
    }

    @Test
    void neverWrites() {
        for (String q : List.of("Supprime le véhicule CE 3205 KJ", "Peux-tu valider le bon BON-2026-118 ?",
                "Merci de modifier le kilométrage", "archiver LT 4471 AB")) {
            var a = assistant.ask("gestionnaire", Role.FLEET_MANAGER.permissions(), on, facts, q, TODAY);
            assertTrue(a.refused(), q);
            assertEquals(Intent.ECRITURE_INTERDITE, a.intent(), q);
        }
        assertEquals(Intent.ECHEANCES, new IntentParser().parse("Mon assurance est-elle valide ?", TODAY).intent(),
                "« valide » adjectif n'est pas une demande d'action");
    }

    @Test
    void otherIntentsAndPeriods() {
        var dues = assistant.ask("g", Role.FLEET_MANAGER.permissions(), on, facts, "Quelles échéances expirent ?", TODAY);
        assertEquals("1 échéance(s) expirée(s) et 1 à traiter dans les 30 jours.", dues.text());
        var missions = assistant.ask("c", Role.CONTROLLER.permissions(), on, facts, "Quelles missions sont en cours ?", TODAY);
        assertEquals(1, missions.figures().size());
        var conso = assistant.ask("g", Role.FLEET_MANAGER.permissions(), on, facts, "Consommation du mois dernier ?", TODAY);
        assertEquals("septembre 2026", conso.period());
        assertEquals("11,4 L/100 km", conso.figures().get(0).value());
        assertTrue(conso.figures().get(1).value().contains("non calculable"), "jamais d'estimation inventée");
        var sept = new IntentParser().parse("Dépenses de juillet ?", TODAY).period();
        assertEquals(LocalDate.of(2026, 7, 1), sept.from());
        assertEquals(LocalDate.of(2025, 12, 31), new IntentParser().parse("coût en décembre", TODAY).period().to());
        assertEquals(Intent.AIDE, assistant.ask("g", Role.FLEET_MANAGER.permissions(), on, facts, "Bonjour", TODAY).intent());
    }

    @Test
    void activationRules() {
        assertThrows(AssistantActivation.Refused.class, () -> AssistantActivation.decide(true, null, "RSSI", "Pilote", "Admin",
                Role.ADMIN.permissions(), Instant.now()));
        assertThrows(AssistantActivation.Refused.class, () -> AssistantActivation.decide(true, "DS-1", "RSSI", "Pilote", "Gest.",
                Role.FLEET_MANAGER.permissions(), Instant.now()), "profil métier");
        assertThrows(AssistantActivation.Refused.class, () -> AssistantActivation.decide(true, "DS-1", "admin", "Pilote", "Admin",
                Role.ADMIN.permissions(), Instant.now()), "auto-validation");
        assertFalse(AssistantActivation.decide(false, null, null, "Fin du pilote", "Admin", Role.ADMIN.permissions(), Instant.now()).enabled());
    }
}
