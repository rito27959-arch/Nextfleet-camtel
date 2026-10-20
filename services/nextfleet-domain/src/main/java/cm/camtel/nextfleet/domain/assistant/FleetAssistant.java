package cm.camtel.nextfleet.domain.assistant;

import cm.camtel.nextfleet.domain.assistant.FleetFacts.CostFact;
import cm.camtel.nextfleet.domain.assistant.FleetFacts.VehicleFact;
import cm.camtel.nextfleet.domain.assistant.IntentParser.Intent;
import cm.camtel.nextfleet.domain.assistant.IntentParser.Parsed;
import cm.camtel.nextfleet.domain.fleet.RegistrationNumber;
import cm.camtel.nextfleet.domain.security.Permission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assistant NexFleet en LECTURE SEULE (cahier V5, chap. VIII ; recette REC-IA-02, scénario E).
 * <ul>
 *   <li>Désactivé tant qu'aucune décision d'activation fondée sur une validation de sécurité n'existe.</li>
 *   <li>Permission {@code assistant:use} + permission propre à chaque question (coûts, trajets…).</li>
 *   <li>Seul accès aux données : {@link FleetFacts}, déjà restreint au périmètre ; aucune écriture possible.</li>
 *   <li>Chaque réponse donne chiffres, période, filtres et références des pièces ; chaque interrogation est journalisée.</li>
 *   <li>Aucun verdict : l'assistant constate, il n'accuse ni ne sanctionne.</li>
 * </ul>
 */
public final class FleetAssistant {

    public static final String ENGINE = "RULES_FR_V1";

    public record Figure(String label, String value) { }

    public record Source(String ref, String description) { }

    public record Answer(Intent intent, boolean refused, String text, List<Figure> figures, String period,
                         List<String> filters, List<Source> sources) {
        static Answer refusal(Intent intent, String text) {
            return new Answer(intent, true, text, List.of(), null, List.of(), List.of());
        }
    }

    /** Trace d'une interrogation (table {@code assistant_run}). */
    public record Run(String userId, String question, Intent intent, boolean refused, String refusalReason,
                      List<String> sourceRefs) { }

    public interface RunLog {
        void record(Run run);
    }

    private final IntentParser parser = new IntentParser();
    private final RunLog log;

    public FleetAssistant(RunLog log) {
        this.log = Objects.requireNonNull(log);
    }

    public Answer ask(String userId, Set<Permission> permissions, AssistantActivation.State activation,
                      FleetFacts facts, String question, LocalDate today) {
        if (question == null || question.isBlank() || question.length() > 2000) {
            return trace(userId, String.valueOf(question), Answer.refusal(Intent.AIDE, "Posez une question de 2 000 caractères au plus."));
        }
        if (!activation.enabled()) {
            return trace(userId, question, Answer.refusal(Intent.AIDE,
                    "L'assistant est désactivé. Il sera ouvert après la validation de sécurité (DS / RSSI) et la décision de l'administration."));
        }
        if (!permissions.contains(Permission.ASSISTANT_USE)) {
            return trace(userId, question, Answer.refusal(Intent.AIDE, "L'assistant n'est pas ouvert à votre profil."));
        }
        Parsed p = parser.parse(question, today);
        Answer a = switch (p.intent()) {
            case ECRITURE_INTERDITE -> Answer.refusal(p.intent(),
                    "Je fonctionne en lecture seule : je ne crée, ne modifie, ne valide et ne supprime rien. "
                            + "Faites cette opération depuis l'écran concerné, avec vos droits.");
            case COUT -> require(permissions, Permission.COST_READ, p, () -> costs(p, facts));
            case CONSOMMATION -> require(permissions, Permission.COST_READ, p, () -> consumption(p, facts, today));
            case ECHEANCES -> require(permissions, Permission.VEHICLE_READ, p, () -> dues(p, facts));
            case MISSIONS -> permissions.contains(Permission.GPS_READ) || permissions.contains(Permission.MISSION_MANAGE)
                    ? missions(p, facts) : Answer.refusal(p.intent(), "Les missions ne font pas partie de votre périmètre de consultation.");
            case DISPONIBILITE -> require(permissions, Permission.VEHICLE_READ, p, () -> availability(p, facts));
            case AIDE -> help();
        };
        return trace(userId, question, a);
    }

    private Answer trace(String userId, String question, Answer a) {
        log.record(new Run(userId, question, a.intent(), a.refused(), a.refused() ? a.text() : null,
                a.sources().stream().map(Source::ref).toList()));
        return a;
    }

    private static Answer require(Set<Permission> perms, Permission needed, Parsed p, java.util.function.Supplier<Answer> ok) {
        return perms.contains(needed) ? ok.get()
                : Answer.refusal(p.intent(), "Cette information n'est pas accessible avec votre profil (" + needed.label.toLowerCase() + ").");
    }

    // ------------------------------------------------------------------ COÛTS (scénario E)

    private Answer costs(Parsed p, FleetFacts facts) {
        Map<String, VehicleFact> fleet = byPlate(facts.vehicles());
        List<String> asked = p.registrations().isEmpty() ? new ArrayList<>(fleet.keySet()) : p.registrations();
        List<String> unknown = asked.stream().filter(r -> !fleet.containsKey(r)).toList();
        List<String> known = asked.stream().filter(fleet::containsKey).toList();
        List<CostFact> rows = facts.costs(p.period().from(), p.period().to()).stream()
                .filter(c -> known.contains(norm(c.registration()))).toList();

        Map<String, BigDecimal> perVehicle = new LinkedHashMap<>();
        known.forEach(r -> perVehicle.put(r, BigDecimal.ZERO));
        rows.forEach(c -> perVehicle.merge(norm(c.registration()), c.amountXaf(), BigDecimal::add));
        BigDecimal total = perVehicle.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Figure> figs = new ArrayList<>();
        perVehicle.forEach((r, v) -> figs.add(new Figure(fleet.get(r).registration() + " · " + fleet.get(r).label(), xaf(v))));
        if (perVehicle.size() > 1) figs.add(new Figure("Total", xaf(total)));
        Map<String, BigDecimal> byCategory = rows.stream().collect(Collectors.groupingBy(CostFact::category,
                LinkedHashMap::new, Collectors.reducing(BigDecimal.ZERO, CostFact::amountXaf, BigDecimal::add)));

        StringBuilder t = new StringBuilder();
        if (known.isEmpty()) {
            t.append("Aucun des véhicules cités ne figure dans votre périmètre.");
        } else {
            t.append(perVehicle.size() == 1 ? "Coût de " + fleet.get(known.get(0)).registration() : "Coût de " + perVehicle.size() + " véhicules")
                    .append(" en ").append(p.period().label()).append(" : ").append(xaf(total)).append('.');
            if (!byCategory.isEmpty()) {
                t.append(" Répartition : ").append(byCategory.entrySet().stream()
                        .map(e -> e.getKey().toLowerCase() + " " + xaf(e.getValue())).collect(Collectors.joining(", "))).append('.');
            }
            if (rows.isEmpty()) t.append(" Aucune dépense enregistrée sur la période.");
        }
        if (!unknown.isEmpty()) {
            t.append(' ').append(String.join(", ", unknown.stream().map(IntentParser::pretty).toList()))
                    .append(" : aucun véhicule correspondant dans votre périmètre.");
        }
        List<String> filters = new ArrayList<>();
        filters.add(p.registrations().isEmpty() ? "tous les véhicules de votre périmètre" : "véhicules : " + String.join(", ",
                known.stream().map(r -> fleet.get(r).registration()).toList()));
        filters.add("dépenses validées (pleins, factures, interventions)");
        if (p.period().byDefault()) filters.add("période par défaut : mois en cours");
        List<Source> sources = rows.stream().map(c -> new Source(c.sourceRef(),
                c.category() + " · " + c.registration() + " · " + c.date() + " · " + xaf(c.amountXaf()))).toList();
        return new Answer(p.intent(), false, t.toString(), figs, p.period().label(), filters, sources);
    }

    // ------------------------------------------------------------------ CONSOMMATION

    private Answer consumption(Parsed p, FleetFacts facts, LocalDate today) {
        YearMonth ym = YearMonth.from(p.period().from());
        Map<String, VehicleFact> fleet = byPlate(facts.vehicles());
        var rows = facts.consumption(ym).stream()
                .filter(c -> p.registrations().isEmpty() || p.registrations().contains(norm(c.registration())))
                .filter(c -> fleet.containsKey(norm(c.registration()))).toList();
        List<Figure> figs = new ArrayList<>();
        List<Source> src = new ArrayList<>();
        int missing = 0;
        for (var c : rows) {
            if (c.litersPer100Km() == null) {
                missing++;
                figs.add(new Figure(c.registration(), "non calculable (période non fiable)"));
            } else {
                figs.add(new Figure(c.registration(), c.litersPer100Km().setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " L/100 km"));
                src.add(new Source(c.sourceRef(), "pleins complets " + c.registration() + " · " + ym));
            }
        }
        String text = rows.isEmpty() ? "Aucune consommation calculable sur " + p.period().label() + "."
                : "Consommation plein-à-plein en " + p.period().label() + " pour " + rows.size() + " véhicule(s)."
                + (missing > 0 ? " " + missing + " véhicule(s) sans période fiable : aucune valeur n'est estimée." : "");
        return new Answer(p.intent(), false, text, figs, p.period().label(),
                List.of("méthode plein-à-plein, périodes fiables uniquement"), src);
    }

    // ------------------------------------------------------------------ ÉCHÉANCES

    private Answer dues(Parsed p, FleetFacts facts) {
        var all = facts.dueItems();
        var expired = all.stream().filter(d -> "EXPIRE".equals(d.level())).toList();
        var soon = all.stream().filter(d -> "A_ECHEANCE".equals(d.level())).toList();
        List<Figure> figs = new ArrayList<>();
        expired.forEach(d -> figs.add(new Figure(d.kind() + " · " + d.subject(), "expiré depuis " + (-d.daysLeft()) + " j")));
        soon.forEach(d -> figs.add(new Figure(d.kind() + " · " + d.subject(), d.daysLeft() == 0 ? "aujourd'hui" : "dans " + d.daysLeft() + " j")));
        String text = expired.isEmpty() && soon.isEmpty() ? "Aucune échéance expirée ni proche dans votre périmètre."
                : expired.size() + " échéance(s) expirée(s) et " + soon.size() + " à traiter dans les 30 jours.";
        List<Source> src = new ArrayList<>();
        expired.forEach(d -> src.add(new Source(d.sourceRef(), d.kind() + " " + d.subject() + " · " + d.dueOn())));
        soon.forEach(d -> src.add(new Source(d.sourceRef(), d.kind() + " " + d.subject() + " · " + d.dueOn())));
        return new Answer(p.intent(), false, text, figs, "à ce jour", List.of("seuil d'alerte : 30 jours"), src);
    }

    // ------------------------------------------------------------------ MISSIONS

    private Answer missions(Parsed p, FleetFacts facts) {
        var active = facts.missions().stream().filter(m -> "ACTIVE".equals(m.status())).toList();
        List<Figure> figs = active.stream().map(m -> new Figure(m.reference(), m.registration() + " · " + m.driver())).toList();
        String text = active.isEmpty() ? "Aucune mission en cours dans votre périmètre."
                : active.size() + " mission(s) en cours. Les positions ne sont collectées que pendant ces missions.";
        return new Answer(p.intent(), false, text, figs, "maintenant", List.of("missions au statut « en cours »"),
                active.stream().map(m -> new Source(m.sourceRef(), "mission " + m.reference())).toList());
    }

    // ------------------------------------------------------------------ DISPONIBILITÉ

    private Answer availability(Parsed p, FleetFacts facts) {
        Map<String, Long> byStatus = facts.vehicles().stream()
                .collect(Collectors.groupingBy(VehicleFact::status, LinkedHashMap::new, Collectors.counting()));
        List<Figure> figs = byStatus.entrySet().stream().map(e -> new Figure(e.getKey(), e.getValue() + " véhicule(s)")).toList();
        return new Answer(p.intent(), false, facts.vehicles().size() + " véhicules actifs dans votre périmètre.", figs,
                "maintenant", List.of("véhicules non archivés"), List.of(new Source("vehicle:*", "registre du parc")));
    }

    private static Answer help() {
        return new Answer(Intent.AIDE, false, "Je réponds en lecture seule, avec mes sources, à des questions comme :",
                List.of(new Figure("Coûts", "« Coût des véhicules CE 3205 KJ et LT 4471 AB ce mois-ci ? »"),
                        new Figure("Échéances", "« Quelles assurances expirent bientôt ? »"),
                        new Figure("Missions", "« Quelles missions sont en cours ? »"),
                        new Figure("Consommation", "« Consommation du mois dernier ? »"),
                        new Figure("Parc", "« Combien de véhicules sont disponibles ? »")),
                null, List.of(), List.of());
    }

    // ------------------------------------------------------------------ utilitaires

    private static Map<String, VehicleFact> byPlate(Collection<VehicleFact> vehicles) {
        Map<String, VehicleFact> m = new LinkedHashMap<>();
        vehicles.forEach(v -> m.put(norm(v.registration()), v));
        return m;
    }

    private static String norm(String plate) {
        return Optional.ofNullable(plate).map(s -> RegistrationNumber.of(s).normalized()).orElse("");
    }

    /** « 1 245 000 XAF » avec espaces simples (lisible et sans caractère invisible à la copie). */
    public static String xaf(BigDecimal v) {
        String digits = v.setScale(0, RoundingMode.HALF_UP).abs().toPlainString();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) b.append(' ');
            b.append(digits.charAt(i));
        }
        return (v.signum() < 0 ? "-" : "") + b + " XAF";
    }
}
