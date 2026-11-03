package cm.camtel.nextfleet.api.reports;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import cm.camtel.nextfleet.domain.report.PdfReport;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rapports (report:export), dans le périmètre de l'utilisateur, en CSV ou en PDF à partir de la même requête.
 * CSV : séparateur « ; », UTF-8 avec BOM (ouverture directe dans Excel en français), cellules commençant par = + - @
 * neutralisées (injection de formules). PDF : A4 paysage, en-tête (période, direction, auteur, date d'édition),
 * total des montants et des litres, pagination. Filtre facultatif par direction. Dates au fuseau Africa/Douala.
 * Chaque export est journalisé avec son format.
 */
@RestController
public class ReportController {

    public record ReportInfo(String code, String title, String description, boolean period, boolean byDirection) { }

    private record Report(String title, String description, boolean period, String sql) {
        boolean byDirection() {
            return sql.contains(DIR) || sql.contains("/*DIR-SQL*/");
        }
    }

    /** Données d'un rapport : libellés de colonnes et valeurs brutes (nombres, dates, textes). */
    record Table(List<String> labels, List<Object[]> rows) { }

    private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String DIR = "/*DIR*/";
    private static final String DIR_V = " AND (CAST(:dir AS text) IS NULL OR v.direction = CAST(:dir AS text))"
            + " AND (CAST(:veh AS uuid) IS NULL OR v.id = CAST(:veh AS uuid))";
    private static final String TODAY = "(now() AT TIME ZONE 'Africa/Douala')::date";

    /** Libellés français des statuts (colonne « Statut »), identiques en CSV et en PDF. */
    private static final Map<String, String> STATUS = Map.ofEntries(
            Map.entry("AVAILABLE", "Disponible"), Map.entry("IN_SERVICE", "En service"), Map.entry("ON_MISSION", "En mission"),
            Map.entry("MAINTENANCE", "En entretien"), Map.entry("OUT_OF_SERVICE", "Hors service"), Map.entry("ARCHIVED", "Archivé"),
            Map.entry("DRAFT", "Brouillon"), Map.entry("SUBMITTED", "Soumis"), Map.entry("APPROVED", "Validé"),
            Map.entry("PARTIALLY_CONSUMED", "Partiellement consommé"), Map.entry("SETTLED", "Soldé"), Map.entry("CANCELLED", "Annulé"),
            Map.entry("EXPIRED", "Expiré"), Map.entry("AUTHORIZED", "Autorisée"), Map.entry("ACTIVE", "En cours"),
            Map.entry("FINISHED", "Terminée"), Map.entry("DEMANDE", "Demandée"), Map.entry("QUALIFIEE", "Qualifiée"),
            Map.entry("PLANIFIEE", "Planifiée"), Map.entry("EN_COURS", "En cours"), Map.entry("CLOTUREE", "Clôturée"),
            Map.entry("ANNULEE", "Annulée"), Map.entry("A_VERIFIER", "À vérifier"), Map.entry("EN_ANALYSE", "En analyse"),
            Map.entry("JUSTIFIE", "Justifié"), Map.entry("ERREUR_CORRIGEE", "Erreur corrigée"), Map.entry("ANOMALIE_CONFIRMEE", "Anomalie confirmée"));

    private static final Map<String, Report> REPORTS = new java.util.LinkedHashMap<>();

    static {
        REPORTS.put("vehicules", new Report("Parc automobile", "Véhicules actifs : identité, statut, direction, affectation, conducteur attitré, kilométrage.", false, """
                SELECT v.registration AS "Immatriculation", v.make AS "Marque", v.model AS "Modèle", v.model_year AS "Année",
                       v.category AS "Catégorie", v.energy AS "Énergie", v.status AS "Statut", v.direction AS "Direction",
                       v.site_label AS "Affectation", o.name AS "Entité",
                       (SELECT d.full_name FROM vehicle_assignment a JOIN driver d ON d.id = a.driver_id WHERE a.vehicle_id = v.id AND a.period @> now() LIMIT 1) AS "Conducteur",
                       (SELECT od.value_km FROM vehicle_odometer od WHERE od.vehicle_id = v.id ORDER BY od.read_at DESC LIMIT 1) AS "Kilométrage"
                  FROM vehicle v JOIN organisation_unit o ON o.id = v.org_unit_id
                 WHERE v.org_unit_id IN (:s) AND v.archived_at IS NULL /*DIR*/ ORDER BY v.direction NULLS LAST, v.registration_normalized"""));
        REPORTS.put("conducteurs", new Report("Conducteurs", "Conducteurs actifs, permis et CNI avec leurs échéances.", false, """
                SELECT d.staff_number AS "Matricule", d.full_name AS "Nom", d.phone AS "Téléphone", o.name AS "Entité",
                       l.license_number AS "Permis", array_to_string(l.categories, ',') AS "Catégories", l.expires_on AS "Fin du permis",
                       d.id_card_expires_on AS "Fin de la CNI"
                  FROM driver d JOIN organisation_unit o ON o.id = d.org_unit_id
                  LEFT JOIN LATERAL (SELECT * FROM driver_license WHERE driver_id = d.id ORDER BY expires_on DESC LIMIT 1) l ON true
                 WHERE d.org_unit_id IN (:s) AND d.archived_at IS NULL ORDER BY d.full_name"""));
        REPORTS.put("pleins", new Report("Pleins de carburant", "Pleins de la période : bon, station, litres, prix, compteur, reçu.", true, """
                SELECT f.filled_at AS "Date", v.registration AS "Véhicule", v.direction AS "Direction", d.full_name AS "Conducteur", sp.name AS "Station",
                       o.order_number AS "Bon", f.liters AS "Litres", f.unit_price AS "Prix unitaire (XAF)", f.amount AS "Montant (XAF)",
                       f.odometer_km AS "Compteur (km)", CASE WHEN f.full_tank THEN 'oui' ELSE 'non' END AS "Plein complet",
                       f.receipt_reference AS "Reçu", f.exception_reason AS "Exception"
                  FROM fuel_fill f JOIN vehicle v ON v.id = f.vehicle_id JOIN supplier sp ON sp.id = f.supplier_id
                  LEFT JOIN driver d ON d.id = f.driver_id LEFT JOIN fuel_order o ON o.id = f.fuel_order_id
                 WHERE v.org_unit_id IN (:s) AND f.filled_at >= :from AND f.filled_at < :to /*DIR*/ ORDER BY f.filled_at"""));
        REPORTS.put("bons", new Report("Bons de carburant", "Bons émis sur la période : demandeur, valideur, autorisé et consommé.", true, """
                SELECT o.order_number AS "Bon", o.created_at AS "Créé le", v.registration AS "Véhicule", v.direction AS "Direction", sp.name AS "Station",
                       o.status AS "Statut", rq.display_name AS "Demandeur", ap.display_name AS "Valideur", o.approved_at AS "Validé le",
                       o.authorized_liters AS "Litres autorisés", o.delivered_liters AS "Litres délivrés",
                       o.authorized_amount AS "Montant autorisé (XAF)", o.delivered_amount AS "Montant délivré (XAF)", o.valid_until AS "Valable jusqu'au"
                  FROM fuel_order o JOIN vehicle v ON v.id = o.vehicle_id JOIN supplier sp ON sp.id = o.supplier_id
                  JOIN app_user rq ON rq.id = o.requested_by LEFT JOIN app_user ap ON ap.id = o.approved_by
                 WHERE o.org_unit_id IN (:s) AND o.created_at >= :from AND o.created_at < :to /*DIR*/ ORDER BY o.created_at"""));
        REPORTS.put("interventions", new Report("Interventions d'atelier", "Ordres de la période : catégorie, statut, immobilisation, coût, visa.", true, """
                SELECT mo.created_at AS "Demandée le", v.registration AS "Véhicule", v.direction AS "Direction", c.label AS "Catégorie", mo.title AS "Intervention",
                       mo.status AS "Statut", coalesce(mo.garage_label, sp.name) AS "Garage", mo.immobilised_from AS "Immobilisé du",
                       mo.immobilised_to AS "au", mo.odometer_km AS "Compteur (km)", mo.cost_xaf AS "Coût (XAF)", mo.work_done AS "Travaux",
                       tv.display_name AS "Visa technique"
                  FROM maintenance_order mo JOIN vehicle v ON v.id = mo.vehicle_id JOIN maintenance_category c ON c.code = mo.category_code
                  LEFT JOIN supplier sp ON sp.id = mo.garage_supplier_id LEFT JOIN app_user tv ON tv.id = mo.technical_visa_by
                 WHERE v.org_unit_id IN (:s) AND mo.created_at >= :from AND mo.created_at < :to /*DIR*/ ORDER BY mo.created_at"""));
        REPORTS.put("vidanges", new Report("Vidanges", "Vidanges de la période : compteur, huile, éléments remplacés, prochaine échéance, coût et garage.", true, """
                SELECT oc.done_on AS "Date", v.registration AS "Véhicule", v.direction AS "Direction", v.site_label AS "Affectation",
                       oc.odometer_km AS "Compteur (km)", oc.oil_label AS "Huile", oc.oil_liters AS "Litres d'huile",
                       concat_ws(', ', CASE WHEN oc.oil_filter THEN 'filtre à huile' END, CASE WHEN oc.fuel_filter THEN 'filtre à carburant' END,
                                 CASE WHEN oc.air_filter THEN 'filtre à air' END, CASE WHEN oc.transmission_oil THEN 'huile de boîte' END,
                                 CASE WHEN oc.coolant THEN 'liquide de refroidissement' END, CASE WHEN oc.washer_fluid THEN 'lave-glace' END) AS "Éléments remplacés",
                       oc.next_due_km AS "Prochaine (km)",
                       CASE WHEN oc.interval_months IS NULL THEN NULL ELSE (oc.done_on + make_interval(months => oc.interval_months))::date END AS "Prochaine (date)",
                       CASE WHEN oc.maintenance_order_id IS NOT NULL THEN 'compté dans l''intervention' END AS "Note",
                       oc.cost_xaf AS "Coût (XAF)", oc.garage_label AS "Garage", a.display_name AS "Saisie par"
                  FROM oil_change oc JOIN vehicle v ON v.id = oc.vehicle_id JOIN app_user a ON a.id = oc.author_id
                 WHERE v.org_unit_id IN (:s) AND oc.done_on >= :dfrom AND oc.done_on < :dto /*DIR*/ ORDER BY oc.done_on, v.registration_normalized"""));
        REPORTS.put("etat-vidanges", new Report("État des vidanges", "Pour chaque véhicule : dernière vidange, prochaine échéance (km et date), kilomètres restants et état.", false, """
                WITH s AS (
                  SELECT v.registration_normalized AS k, v.registration, v.make || ' ' || v.model AS modele, v.direction, v.site_label,
                         km.value_km, oc.id AS oc_id, oc.done_on, oc.odometer_km, oc.next_due_km, nd.d AS next_on,
                         CASE WHEN oc.id IS NULL THEN 3
                              WHEN (oc.next_due_km IS NOT NULL AND km.value_km > oc.next_due_km) OR (nd.d IS NOT NULL AND nd.d < """ + TODAY + """
                ) THEN 0
                              WHEN (oc.next_due_km IS NOT NULL AND oc.next_due_km - km.value_km <= 1000) OR (nd.d IS NOT NULL AND nd.d <= """ + TODAY + """
                 + 30) THEN 1
                              WHEN oc.next_due_km IS NULL AND nd.d IS NULL THEN 4
                              ELSE 2 END AS lvl
                    FROM vehicle v
                    LEFT JOIN LATERAL (SELECT value_km FROM vehicle_odometer WHERE vehicle_id = v.id ORDER BY read_at DESC LIMIT 1) km ON true
                    LEFT JOIN LATERAL (SELECT * FROM oil_change WHERE vehicle_id = v.id ORDER BY done_on DESC, created_at DESC LIMIT 1) oc ON true
                    LEFT JOIN LATERAL (SELECT CASE WHEN oc.interval_months IS NULL THEN NULL
                                                   ELSE (oc.done_on + make_interval(months => oc.interval_months))::date END AS d) nd ON true
                   WHERE v.org_unit_id IN (:s) AND v.archived_at IS NULL /*DIR*/)
                SELECT registration AS "Véhicule", modele AS "Modèle", direction AS "Direction", site_label AS "Affectation",
                       value_km AS "Compteur actuel (km)", done_on AS "Dernière vidange", odometer_km AS "Compteur à la vidange (km)",
                       next_due_km AS "Prochaine (km)", next_due_km - value_km AS "Reste (km)", next_on AS "Prochaine (date)",
                       CASE lvl WHEN 0 THEN 'En retard' WHEN 1 THEN 'À prévoir' WHEN 2 THEN 'À jour'
                                 WHEN 3 THEN 'Aucune vidange enregistrée' ELSE 'Intervalle non renseigné' END AS "État"
                  FROM s ORDER BY lvl, direction NULLS LAST, k"""));
        REPORTS.put("missions", new Report("Missions", "Missions de la période : objet, trajet prévu, conducteur, dates réelles.", true, """
                SELECT m.reference AS "Référence", m.purpose AS "Objet", m.origin_label AS "Départ", m.destination_label AS "Destination",
                       v.registration AS "Véhicule", v.direction AS "Direction", d.full_name AS "Conducteur", m.status AS "Statut", m.planned_start AS "Début prévu",
                       m.planned_end AS "Fin prévue", m.started_at AS "Démarrée le", m.finished_at AS "Terminée le",
                       au.display_name AS "Autorisée par"
                  FROM mission m JOIN vehicle v ON v.id = m.vehicle_id JOIN driver d ON d.id = m.driver_id
                  LEFT JOIN app_user au ON au.id = m.authorized_by
                 WHERE m.org_unit_id IN (:s) AND m.planned_start >= :from AND m.planned_start < :to /*DIR*/ ORDER BY m.planned_start"""));
        REPORTS.put("couts-vehicules", new Report("Coûts par véhicule", "Carburant, interventions clôturées et vidanges hors ordre, par véhicule.", true, """
                SELECT v.registration AS "Véhicule", v.make || ' ' || v.model AS "Modèle", v.direction AS "Direction",
                       coalesce((SELECT sum(amount) FROM fuel_fill f WHERE f.vehicle_id = v.id AND f.filled_at >= :from AND f.filled_at < :to), 0) AS "Carburant (XAF)",
                       coalesce((SELECT sum(liters) FROM fuel_fill f WHERE f.vehicle_id = v.id AND f.filled_at >= :from AND f.filled_at < :to), 0) AS "Litres",
                       coalesce((SELECT sum(cost_xaf) FROM maintenance_order mo WHERE mo.vehicle_id = v.id AND mo.status = 'CLOTUREE'
                                  AND coalesce(mo.immobilised_to, mo.created_at) >= :from AND coalesce(mo.immobilised_to, mo.created_at) < :to), 0) AS "Interventions (XAF)",
                       coalesce((SELECT sum(cost_xaf) FROM oil_change oc WHERE oc.vehicle_id = v.id AND oc.maintenance_order_id IS NULL
                                  AND oc.done_on >= :dfrom AND oc.done_on < :dto), 0) AS "Vidanges (XAF)"
                  FROM vehicle v WHERE v.org_unit_id IN (:s) AND v.archived_at IS NULL /*DIR*/ ORDER BY v.direction NULLS LAST, v.registration_normalized"""));
        REPORTS.put("echeances", new Report("Échéances", "Documents véhicules, permis et CNI expirés ou à échéance sous 90 jours.", false, """
                SELECT dd.subject_kind AS "Objet", dd.subject_label AS "Véhicule ou conducteur",
                       (SELECT vv.direction FROM vehicle vv WHERE dd.subject_kind = 'VEHICULE' AND vv.id = dd.subject_id) AS "Direction",
                       dd.due_kind AS "Document", dd.due_on AS "Échéance", (dd.due_on - """ + TODAY + """
                ) AS "Jours restants"
                  FROM v_due_date dd
                 WHERE dd.org_unit_id IN (:s) AND dd.due_on <= """ + TODAY + """
                 + 90
                   AND (CAST(:dir AS text) IS NULL OR EXISTS (SELECT 1 FROM vehicle vv WHERE dd.subject_kind = 'VEHICULE'
                        AND vv.id = dd.subject_id AND vv.direction = CAST(:dir AS text)))
                   AND (CAST(:veh AS uuid) IS NULL OR (dd.subject_kind = 'VEHICULE' AND dd.subject_id = CAST(:veh AS uuid))) /*DIR-SQL*/
                 ORDER BY dd.due_on"""));
        REPORTS.put("anomalies", new Report("Écarts à vérifier", "Écarts détectés sur la période et leur statut d'instruction (jamais une qualification de fraude).", true, """
                SELECT a.detected_at AS "Détecté le", v.registration AS "Véhicule", v.direction AS "Direction", a.rule_id AS "Règle", a.severity AS "Gravité",
                       a.data_quality AS "Qualité des données", a.summary AS "Constat", a.status AS "Statut"
                  FROM anomaly a LEFT JOIN vehicle v ON v.id = a.vehicle_id
                 WHERE a.org_unit_id IN (:s) AND a.detected_at >= :from AND a.detected_at < :to /*DIR*/ ORDER BY a.detected_at"""));
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
                e.getValue().period(), e.getValue().byDirection())).toList();
    }

    @GetMapping("/api/v1/reports/{code}.csv")
    @Transactional(noRollbackFor = AccessDeniedException.class)   // l'export est journalisé : transaction en écriture
    public ResponseEntity<byte[]> csv(@PathVariable String code, @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String direction,
                                      @RequestParam(required = false) UUID vehicleId) {
        Export x = run(code, from, to, direction, vehicleId, "csv");
        StringBuilder csv = new StringBuilder("﻿");
        for (int c = 0; c < x.table.labels().size(); c++) csv.append(c > 0 ? ";" : "").append(cell(x.table.labels().get(c)));
        csv.append("\r\n");
        for (Object[] r : x.table.rows()) {
            for (int c = 0; c < r.length; c++) {
                Object v = r[c];
                String s = csvValue(x.table.labels().get(c), v);
                csv.append(c > 0 ? ";" : "").append(v instanceof Number ? s : cell(s));
            }
            csv.append("\r\n");
        }
        if (x.table.rows().isEmpty()) csv.append(cell("Aucune donnée pour cette période et ce périmètre.")).append("\r\n");
        return file(x.fileName + ".csv", new MediaType("text", "csv", StandardCharsets.UTF_8), csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/v1/reports/{code}.pdf")
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public ResponseEntity<byte[]> pdf(@PathVariable String code, @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String direction,
                                      @RequestParam(required = false) UUID vehicleId) {
        Export x = run(code, from, to, direction, vehicleId, "pdf");
        List<String> labels = x.table.labels();
        int n = labels.size();
        boolean[] numeric = new boolean[n];
        for (Object[] r : x.table.rows()) for (int c = 0; c < n; c++) if (r[c] instanceof Number) numeric[c] = true;
        List<PdfReport.Column> cols = new ArrayList<>();
        BigDecimal[] sums = new BigDecimal[n];
        for (int c = 0; c < n; c++) {
            boolean total = numeric[c] && totalled(labels.get(c));
            cols.add(new PdfReport.Column(labels.get(c), numeric[c], total));
            if (total) sums[c] = BigDecimal.ZERO;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Object[] r : x.table.rows()) {
            List<String> line = new ArrayList<>(n);
            for (int c = 0; c < n; c++) {
                line.add(pdfValue(labels.get(c), r[c]));
                if (sums[c] != null && r[c] instanceof Number num) sums[c] = sums[c].add(new BigDecimal(num.toString()));
            }
            rows.add(line);
        }
        List<String> totals = new ArrayList<>();
        for (int c = 0; c < n; c++) totals.add(sums[c] == null ? null : number(sums[c]));
        ZonedDateTime now = ZonedDateTime.now(DOUALA);
        List<String> context = new ArrayList<>();
        Report r = REPORTS.get(code);
        context.add((r.period() ? "Période : du " + DATE.format(x.from) + " au " + DATE.format(x.to) : "Situation au " + DATE.format(now.toLocalDate()))
                + "   ·   " + (x.vehicle != null ? "Véhicule : " + x.vehicle : x.direction == null ? "Toutes les directions" : "Direction : " + x.direction)
                + "   ·   " + x.table.rows().size() + (x.table.rows().size() > 1 ? " lignes" : " ligne"));
        context.add("Édité le " + DATE.format(now) + " à " + DateTimeFormatter.ofPattern("HH:mm").format(now) + " par " + x.user.displayName()
                + " — montants en FCFA, dates au fuseau de Douala");
        byte[] bytes = PdfReport.render(new PdfReport.Meta(r.title(), r.description(), context,
                "NexFleet — document interne CAMTEL. Cet export est enregistré dans le journal d'audit."), cols, rows, totals);
        return file(x.fileName + ".pdf", MediaType.APPLICATION_PDF, bytes);
    }

    private record Export(Table table, String fileName, LocalDate from, LocalDate to, String direction, String vehicle, AuthenticatedUser user) { }

    private Export run(String code, LocalDate from, LocalDate to, String direction, UUID vehicleId, String format) {
        Report r = REPORTS.get(code);
        if (r == null) throw new cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException("Rapport inconnu.");
        List<UUID> scope = access.scopeFor("report:export");
        AuthenticatedUser me = access.currentUser();
        if (scope.isEmpty()) {
            audit.recordDenied(me.userId(), "report", code, "report:export");
            throw new AccessDeniedException("Accès refusé");
        }
        LocalDate today = LocalDate.now(DOUALA);
        LocalDate f = from != null ? from : today.withDayOfMonth(1);
        LocalDate t = to != null ? to : today;
        if (t.isBefore(f)) throw new IllegalArgumentException("La fin de période précède le début.");
        if (f.plusYears(5).isBefore(t)) throw new IllegalArgumentException("Période limitée à 5 ans.");
        String dir = direction == null || direction.isBlank() ? null : direction.strip();
        if (dir != null && dir.length() > 120) throw new IllegalArgumentException("Direction trop longue.");
        UUID veh = r.byDirection() ? vehicleId : null;
        if (!r.byDirection()) dir = null;
        String plate = null;
        if (veh != null) {
            List<String> found = jdbc.queryForList("SELECT registration FROM vehicle WHERE id = :v AND org_unit_id IN (:s)",
                    new MapSqlParameterSource("v", veh).addValue("s", scope), String.class);
            if (found.isEmpty()) throw new cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException("Véhicule introuvable dans votre périmètre.");
            plate = found.get(0);
        }
        MapSqlParameterSource p = new MapSqlParameterSource("s", scope)
                .addValue("from", Timestamp.from(f.atStartOfDay(DOUALA).toInstant()))
                .addValue("to", Timestamp.from(t.plusDays(1).atStartOfDay(DOUALA).toInstant()))
                .addValue("dfrom", f).addValue("dto", t.plusDays(1))
                .addValue("dir", dir).addValue("veh", veh);
        List<String> labels = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        jdbc.query(r.sql().replace(DIR, DIR_V), p, rs -> {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            if (labels.isEmpty()) for (int c = 1; c <= n; c++) labels.add(md.getColumnLabel(c));
            Object[] row = new Object[n];
            for (int c = 1; c <= n; c++) row[c - 1] = rs.getObject(c);
            rows.add(row);
        });
        if (labels.isEmpty()) labels.addAll(columnLabels(r.sql().replace(DIR, DIR_V), p));
        audit.record(me.userId(), "EXPORT", "report", code, null, null, Map.of("from", f.toString(), "to", t.toString(),
                "rows", rows.size(), "format", format, "direction", dir == null ? "" : dir, "vehicle", plate == null ? "" : plate));
        String name = "nexfleet-" + code + (plate != null ? "-" + slug(plate) : dir == null ? "" : "-" + slug(dir)) + "-" + f + (r.period() ? "_" + t : "");
        return new Export(new Table(labels, rows), name, f, t, dir, plate, me);
    }

    /** Libellés des colonnes quand la requête ne renvoie aucune ligne (en-tête du CSV et du PDF). */
    private List<String> columnLabels(String sql, MapSqlParameterSource p) {
        List<String> out = new ArrayList<>();
        jdbc.query("SELECT * FROM (" + stripOrder(sql) + ") q LIMIT 0", p, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
            ResultSetMetaData md = rs.getMetaData();
            for (int c = 1; c <= md.getColumnCount(); c++) out.add(md.getColumnLabel(c));
            return null;
        });
        return out;
    }

    private static String stripOrder(String sql) {
        int i = sql.lastIndexOf("ORDER BY");
        return i < 0 ? sql : sql.substring(0, i);
    }

    private static ResponseEntity<byte[]> file(String name, MediaType type, byte[] body) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    static boolean totalled(String label) {
        return (label.contains("(XAF)") && !label.startsWith("Prix")) || label.startsWith("Litres");
    }

    static String csvValue(String label, Object v) {
        if (v == null) return "";
        if (v instanceof Timestamp ts) return DATE_TIME.format(ts.toInstant().atZone(DOUALA));
        if (v instanceof java.sql.Date d) return DATE.format(d.toLocalDate());
        if (v instanceof LocalDate d) return DATE.format(d);
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString().replace('.', ',');
        String s = v.toString();
        return "Statut".equals(label) ? STATUS.getOrDefault(s, s) : s;
    }

    static String pdfValue(String label, Object v) {
        if (v instanceof Number n) return number(new BigDecimal(n.toString()));
        return csvValue(label, v);
    }

    /** Nombre à la française : virgule décimale, espaces insécables par milliers à partir de 10 000 (pas pour une année). */
    static String number(BigDecimal b) {
        String plain = b.stripTrailingZeros().toPlainString();
        boolean neg = plain.startsWith("-");
        if (neg) plain = plain.substring(1);
        int dot = plain.indexOf('.');
        String ip = dot < 0 ? plain : plain.substring(0, dot);
        String dp = dot < 0 ? "" : "," + plain.substring(dot + 1, Math.min(plain.length(), dot + 3));
        if (ip.length() > 4) {
            StringBuilder g = new StringBuilder();
            for (int i = 0; i < ip.length(); i++) {
                if (i > 0 && (ip.length() - i) % 3 == 0) g.append(' ');
                g.append(ip.charAt(i));
            }
            ip = g.toString();
        }
        return (neg ? "-" : "") + ip + dp;
    }

    static String slug(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase(java.util.Locale.ROOT);
    }

    static String cell(String s) {
        String v = s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
