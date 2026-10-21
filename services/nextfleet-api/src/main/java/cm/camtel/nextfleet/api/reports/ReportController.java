package cm.camtel.nextfleet.api.reports;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.identity.AccessService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rapports CSV (report:export), dans le périmètre de l'utilisateur : séparateur « ; », UTF-8 avec BOM (ouverture directe
 * dans Excel en français), dates au fuseau Africa/Douala. Les cellules commençant par = + - @ sont neutralisées
 * (injection de formules). Chaque export est journalisé.
 */
@RestController
public class ReportController {

    public record ReportInfo(String code, String title, String description, boolean period) { }

    private record Report(String title, String description, boolean period, String sql) { }

    private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Map<String, Report> REPORTS = new java.util.LinkedHashMap<>();

    static {
        REPORTS.put("vehicules", new Report("Parc automobile", "Véhicules actifs : identité, statut, entité, conducteur attitré, kilométrage.", false, """
                SELECT v.registration AS "Immatriculation", v.make AS "Marque", v.model AS "Modèle", v.model_year AS "Année",
                       v.category AS "Catégorie", v.energy AS "Énergie", v.status AS "Statut", o.name AS "Entité", v.site_label AS "Affectation",
                       (SELECT d.full_name FROM vehicle_assignment a JOIN driver d ON d.id = a.driver_id WHERE a.vehicle_id = v.id AND a.period @> now() LIMIT 1) AS "Conducteur",
                       (SELECT od.value_km FROM vehicle_odometer od WHERE od.vehicle_id = v.id ORDER BY od.read_at DESC LIMIT 1) AS "Kilométrage"
                  FROM vehicle v JOIN organisation_unit o ON o.id = v.org_unit_id
                 WHERE v.org_unit_id IN (:s) AND v.archived_at IS NULL ORDER BY v.registration_normalized"""));
        REPORTS.put("conducteurs", new Report("Conducteurs", "Conducteurs actifs, permis et CNI avec leurs échéances.", false, """
                SELECT d.staff_number AS "Matricule", d.full_name AS "Nom", d.phone AS "Téléphone", o.name AS "Entité",
                       l.license_number AS "Permis", array_to_string(l.categories, ',') AS "Catégories", l.expires_on AS "Fin du permis",
                       d.id_card_expires_on AS "Fin de la CNI"
                  FROM driver d JOIN organisation_unit o ON o.id = d.org_unit_id
                  LEFT JOIN LATERAL (SELECT * FROM driver_license WHERE driver_id = d.id ORDER BY expires_on DESC LIMIT 1) l ON true
                 WHERE d.org_unit_id IN (:s) AND d.archived_at IS NULL ORDER BY d.full_name"""));
        REPORTS.put("pleins", new Report("Pleins de carburant", "Pleins de la période : bon, station, litres, prix, compteur, reçu.", true, """
                SELECT f.filled_at AS "Date", v.registration AS "Véhicule", d.full_name AS "Conducteur", sp.name AS "Station",
                       o.order_number AS "Bon", f.liters AS "Litres", f.unit_price AS "Prix unitaire (XAF)", f.amount AS "Montant (XAF)",
                       f.odometer_km AS "Compteur (km)", CASE WHEN f.full_tank THEN 'oui' ELSE 'non' END AS "Plein complet",
                       f.receipt_reference AS "Reçu", f.exception_reason AS "Exception"
                  FROM fuel_fill f JOIN vehicle v ON v.id = f.vehicle_id JOIN supplier sp ON sp.id = f.supplier_id
                  LEFT JOIN driver d ON d.id = f.driver_id LEFT JOIN fuel_order o ON o.id = f.fuel_order_id
                 WHERE v.org_unit_id IN (:s) AND f.filled_at >= :from AND f.filled_at < :to ORDER BY f.filled_at"""));
        REPORTS.put("bons", new Report("Bons de carburant", "Bons émis sur la période : demandeur, valideur, autorisé et consommé.", true, """
                SELECT o.order_number AS "Bon", o.created_at AS "Créé le", v.registration AS "Véhicule", sp.name AS "Station",
                       o.status AS "Statut", rq.display_name AS "Demandeur", ap.display_name AS "Valideur", o.approved_at AS "Validé le",
                       o.authorized_liters AS "Litres autorisés", o.delivered_liters AS "Litres délivrés",
                       o.authorized_amount AS "Montant autorisé (XAF)", o.delivered_amount AS "Montant délivré (XAF)", o.valid_until AS "Valable jusqu'au"
                  FROM fuel_order o JOIN vehicle v ON v.id = o.vehicle_id JOIN supplier sp ON sp.id = o.supplier_id
                  JOIN app_user rq ON rq.id = o.requested_by LEFT JOIN app_user ap ON ap.id = o.approved_by
                 WHERE o.org_unit_id IN (:s) AND o.created_at >= :from AND o.created_at < :to ORDER BY o.created_at"""));
        REPORTS.put("interventions", new Report("Interventions d'atelier", "Ordres de la période : catégorie, statut, immobilisation, coût, visa.", true, """
                SELECT mo.created_at AS "Demandée le", v.registration AS "Véhicule", c.label AS "Catégorie", mo.title AS "Intervention",
                       mo.status AS "Statut", coalesce(mo.garage_label, sp.name) AS "Garage", mo.immobilised_from AS "Immobilisé du",
                       mo.immobilised_to AS "au", mo.odometer_km AS "Compteur (km)", mo.cost_xaf AS "Coût (XAF)", mo.work_done AS "Travaux",
                       tv.display_name AS "Visa technique"
                  FROM maintenance_order mo JOIN vehicle v ON v.id = mo.vehicle_id JOIN maintenance_category c ON c.code = mo.category_code
                  LEFT JOIN supplier sp ON sp.id = mo.garage_supplier_id LEFT JOIN app_user tv ON tv.id = mo.technical_visa_by
                 WHERE v.org_unit_id IN (:s) AND mo.created_at >= :from AND mo.created_at < :to ORDER BY mo.created_at"""));
        REPORTS.put("missions", new Report("Missions", "Missions de la période : objet, trajet prévu, conducteur, dates réelles.", true, """
                SELECT m.reference AS "Référence", m.purpose AS "Objet", m.origin_label AS "Départ", m.destination_label AS "Destination",
                       v.registration AS "Véhicule", d.full_name AS "Conducteur", m.status AS "Statut", m.planned_start AS "Début prévu",
                       m.planned_end AS "Fin prévue", m.started_at AS "Démarrée le", m.finished_at AS "Terminée le",
                       au.display_name AS "Autorisée par"
                  FROM mission m JOIN vehicle v ON v.id = m.vehicle_id JOIN driver d ON d.id = m.driver_id
                  LEFT JOIN app_user au ON au.id = m.authorized_by
                 WHERE m.org_unit_id IN (:s) AND m.planned_start >= :from AND m.planned_start < :to ORDER BY m.planned_start"""));
        REPORTS.put("couts-vehicules", new Report("Coûts par véhicule", "Carburant, interventions clôturées et vidanges hors ordre, par véhicule.", true, """
                SELECT v.registration AS "Véhicule", v.make || ' ' || v.model AS "Modèle",
                       coalesce((SELECT sum(amount) FROM fuel_fill f WHERE f.vehicle_id = v.id AND f.filled_at >= :from AND f.filled_at < :to), 0) AS "Carburant (XAF)",
                       coalesce((SELECT sum(liters) FROM fuel_fill f WHERE f.vehicle_id = v.id AND f.filled_at >= :from AND f.filled_at < :to), 0) AS "Litres",
                       coalesce((SELECT sum(cost_xaf) FROM maintenance_order mo WHERE mo.vehicle_id = v.id AND mo.status = 'CLOTUREE'
                                  AND coalesce(mo.immobilised_to, mo.created_at) >= :from AND coalesce(mo.immobilised_to, mo.created_at) < :to), 0) AS "Interventions (XAF)",
                       coalesce((SELECT sum(cost_xaf) FROM oil_change oc WHERE oc.vehicle_id = v.id AND oc.maintenance_order_id IS NULL
                                  AND oc.done_on >= CAST(:from AS timestamptz)::date AND oc.done_on < CAST(:to AS timestamptz)::date), 0) AS "Vidanges (XAF)"
                  FROM vehicle v WHERE v.org_unit_id IN (:s) AND v.archived_at IS NULL ORDER BY v.registration_normalized"""));
        REPORTS.put("echeances", new Report("Échéances", "Documents véhicules, permis et CNI expirés ou à échéance sous 90 jours.", false, """
                SELECT dd.subject_kind AS "Objet", dd.subject_label AS "Véhicule ou conducteur", dd.due_kind AS "Document",
                       dd.due_on AS "Échéance", (dd.due_on - (now() AT TIME ZONE 'Africa/Douala')::date) AS "Jours restants"
                  FROM v_due_date dd
                 WHERE dd.org_unit_id IN (:s) AND dd.due_on <= (now() AT TIME ZONE 'Africa/Douala')::date + 90 ORDER BY dd.due_on"""));
        REPORTS.put("anomalies", new Report("Écarts à vérifier", "Écarts détectés sur la période et leur statut d'instruction (jamais une qualification de fraude).", true, """
                SELECT a.detected_at AS "Détecté le", v.registration AS "Véhicule", a.rule_id AS "Règle", a.severity AS "Gravité",
                       a.data_quality AS "Qualité des données", a.summary AS "Constat", a.status AS "Statut"
                  FROM anomaly a LEFT JOIN vehicle v ON v.id = a.vehicle_id
                 WHERE a.org_unit_id IN (:s) AND a.detected_at >= :from AND a.detected_at < :to ORDER BY a.detected_at"""));
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;

    public ReportController(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/api/v1/reports")
    public List<ReportInfo> list() {
        if (access.scopeFor("report:export").isEmpty()) return List.of();
        return REPORTS.entrySet().stream().map(e -> new ReportInfo(e.getKey(), e.getValue().title(), e.getValue().description(),
                e.getValue().period())).toList();
    }

    @GetMapping("/api/v1/reports/{code}.csv")
    @Transactional(noRollbackFor = AccessDeniedException.class)   // l'export est journalisé : transaction en écriture
    public ResponseEntity<byte[]> export(@PathVariable String code, @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to) {
        Report r = REPORTS.get(code);
        if (r == null) throw new cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException("Rapport inconnu.");
        List<UUID> scope = access.scopeFor("report:export");
        UUID me = access.currentUser().userId();
        if (scope.isEmpty()) {
            audit.recordDenied(me, "report", code, "report:export");
            throw new AccessDeniedException("Accès refusé");
        }
        LocalDate today = LocalDate.now(DOUALA);
        LocalDate f = from != null ? from : today.withDayOfMonth(1);
        LocalDate t = to != null ? to : today;
        if (t.isBefore(f)) throw new IllegalArgumentException("La fin de période précède le début.");
        if (f.plusYears(5).isBefore(t)) throw new IllegalArgumentException("Période limitée à 5 ans.");
        MapSqlParameterSource p = new MapSqlParameterSource("s", scope)
                .addValue("from", Timestamp.from(f.atStartOfDay(DOUALA).toInstant()))
                .addValue("to", Timestamp.from(t.plusDays(1).atStartOfDay(DOUALA).toInstant()));
        StringBuilder csv = new StringBuilder("﻿");
        int[] rows = {0};
        jdbc.query(r.sql(), p, rs -> {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            if (rows[0] == 0) {
                for (int c = 1; c <= n; c++) csv.append(c > 1 ? ";" : "").append(cell(md.getColumnLabel(c)));
                csv.append("\r\n");
            }
            for (int c = 1; c <= n; c++) {
                Object v = rs.getObject(c);
                String s;
                boolean number = v instanceof Number;
                if (v == null) s = "";
                else if (v instanceof Timestamp ts) s = DATE_TIME.format(ts.toInstant().atZone(DOUALA));
                else if (v instanceof java.sql.Date d) s = DATE.format(d.toLocalDate());
                else if (v instanceof java.math.BigDecimal b) s = b.stripTrailingZeros().toPlainString().replace('.', ',');
                else s = v.toString();
                csv.append(c > 1 ? ";" : "").append(number ? s : cell(s));
            }
            csv.append("\r\n");
            rows[0]++;
        });
        if (rows[0] == 0) csv.append(cell("Aucune donnée pour cette période et ce périmètre.")).append("\r\n");
        audit.record(me, "EXPORT", "report", code, null, null, Map.of("from", f.toString(), "to", t.toString(), "rows", rows[0]));
        String name = "nexfleet-" + code + "-" + f + (r.period() ? "_" + t : "") + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String cell(String s) {
        String v = s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
