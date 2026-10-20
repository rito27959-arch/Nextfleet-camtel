package cm.camtel.nextfleet.domain.assistant;

import cm.camtel.nextfleet.domain.fleet.RegistrationNumber;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compréhension des questions en français par règles explicites (moteur {@code RULES_FR_V1}).
 * Déterministe et testable : la même question donne toujours la même interprétation, affichée à l'utilisateur.
 * Un modèle de langage local (LangChain4j) pourra remplacer cette étape après validation de sécurité ;
 * il restera limité aux mêmes outils en lecture seule.
 */
public final class IntentParser {

    public enum Intent { COUT, ECHEANCES, MISSIONS, CONSOMMATION, DISPONIBILITE, ECRITURE_INTERDITE, AIDE }

    /** Période d'analyse, bornes incluses, avec son libellé lisible. */
    public record Period(LocalDate from, LocalDate to, String label, boolean byDefault) { }

    public record Parsed(Intent intent, List<String> registrations, Period period, String normalizedQuestion) { }

    private static final Pattern PLATE = Pattern.compile("\\b([A-Z]{2})[\\s-]?(\\d{3,4})[\\s-]?([A-Z]{1,2})\\b");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1), Map.entry("fevrier", 2), Map.entry("mars", 3), Map.entry("avril", 4),
            Map.entry("mai", 5), Map.entry("juin", 6), Map.entry("juillet", 7), Map.entry("aout", 8),
            Map.entry("septembre", 9), Map.entry("octobre", 10), Map.entry("novembre", 11), Map.entry("decembre", 12));
    private static final Pattern MONTH = Pattern.compile("\\b(janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)(?:\\s+(20\\d\\d))?\\b");
    /** Demande d'action : infinitif n'importe où, ou impératif en tête (« valide le bon… »), jamais un adjectif. */
    private static final String VERBS = "supprim|effac|modifi|cre|ajout|valid|approuv|archiv|envoi|envoy|chang|annul|attribu|affect|desactiv|enregistr";
    private static final Pattern WRITE = Pattern.compile(
            "\\b(" + VERBS + ")\\w*er\\b|^(peux-tu |pouvez-vous |merci de |veuillez |je veux |)(" + VERBS + ")\\w*\\b");

    public Parsed parse(String question, LocalDate today) {
        String q = normalize(question);
        List<String> plates = registrations(question);
        Intent intent;
        if (WRITE.matcher(q).find()) intent = Intent.ECRITURE_INTERDITE;
        else if (q.matches(".*\\b(couts?|coute\\w*|depens\\w*|montant|budget consomme|combien .*(paye|depense))\\b.*")) intent = Intent.COUT;
        else if (q.matches(".*\\b(consommation|consomme|l/100|litres aux 100)\\b.*")) intent = Intent.CONSOMMATION;
        else if (q.matches(".*\\b(echeances?|expir\\w*|assurances?|vignettes?|controle technique|carte grise|permis)\\b.*")) intent = Intent.ECHEANCES;
        else if (q.matches(".*\\b(missions?|en route|qui roule|deplacements? en cours)\\b.*")) intent = Intent.MISSIONS;
        else if (q.matches(".*\\b(disponibles?|disponibilite|immobilises?|en panne|en maintenance|statut|etat du parc)\\b.*")) intent = Intent.DISPONIBILITE;
        else intent = Intent.AIDE;
        return new Parsed(intent, plates, period(q, today), q);
    }

    static Period period(String q, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        if (q.contains("mois dernier") || q.contains("mois precedent")) return month(current.minusMonths(1), false);
        if (q.contains("cette annee") || q.contains("depuis janvier")) {
            return new Period(LocalDate.of(today.getYear(), 1, 1), today, "du 1er janvier au " + day(today), false);
        }
        Matcher m = MONTH.matcher(q);
        if (m.find()) {
            int month = MONTHS.get(m.group(1));
            int year = m.group(2) != null ? Integer.parseInt(m.group(2))
                    : (month > today.getMonthValue() ? today.getYear() - 1 : today.getYear());
            return month(YearMonth.of(year, month), false);
        }
        boolean explicit = q.contains("ce mois") || q.contains("mois en cours");
        return month(current, !explicit);
    }

    private static Period month(YearMonth ym, boolean byDefault) {
        String label = ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.FRANCE) + " " + ym.getYear();
        return new Period(ym.atDay(1), ym.atEndOfMonth(), label, byDefault);
    }

    private static String day(LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE));
    }

    /** Immatriculations citées, normalisées (CE 3205 KJ, CE-3205-KJ, ce3205kj → même véhicule). */
    static List<String> registrations(String question) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLATE.matcher(question.toUpperCase(Locale.ROOT));
        while (m.find()) out.add(RegistrationNumber.of(m.group(1) + " " + m.group(2) + " " + m.group(3)).normalized());
        return new ArrayList<>(out);
    }

    /** CE3205KJ → « CE 3205 KJ ». */
    public static String pretty(String normalizedPlate) {
        return normalizedPlate.replaceFirst("^([A-Z]{2})(\\d{3,4})([A-Z]{1,2})$", "$1 $2 $3");
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").strip();
    }
}
