package cm.camtel.nextfleet.api.dues;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.files.FileService;
import cm.camtel.nextfleet.api.identity.AccessService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Échéances unifiées (NF-11) : documents véhicule (assurance, vignette, contrôle technique, carte grise), permis et CNI des
 * conducteurs (vue v_due_date), vidanges (date issue de l'intervalle en mois). Mêmes dates et mêmes niveaux partout ;
 * les dates sont des dates civiles du fuseau Africa/Douala (RG-05).
 */
@Service
public class DueService {

    public static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    public static final int SOON_DAYS = 30;

    public record DueView(String kind, String subjectKind, UUID subjectId, String subject, String subjectDetail, boolean vehicle,
                          LocalDate dueOn, long daysLeft, String level, UUID orgUnitId) { }

    public record DocumentView(UUID id, String docType, String reference, LocalDate validFrom, LocalDate validUntil, UUID fileId,
                               String fileName) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final FileService files;
    private final Clock clock;

    public DueService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit, FileService files, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.files = files;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(DOUALA));
    }

    /** Échéances dépassées ou à moins de {@code horizonDays} jours, dans le périmètre de lecture. */
    @Transactional(readOnly = true)
    public List<DueView> upcoming(int horizonDays) {
        LocalDate limit = today().plusDays(Math.max(0, Math.min(horizonDays, 3650)));
        List<DueView> all = new ArrayList<>(query(null, limit));
        all.sort(Comparator.comparing(DueView::dueOn).thenComparing(DueView::subject));
        return all;
    }

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public List<DueView> forVehicle(UUID vehicleId) {
        UUID org = vehicleOrg(vehicleId);
        access.require("vehicle:read", org, "vehicle", vehicleId.toString());
        return query(vehicleId, null).stream().sorted(Comparator.comparing(DueView::dueOn)).toList();
    }

    private List<DueView> query(UUID onlyVehicle, LocalDate limit) {
        List<UUID> vScope = access.scopeFor("vehicle:read");
        List<UUID> dScope = onlyVehicle != null ? List.of() : access.scopeFor("driver:read");
        List<DueView> out = new ArrayList<>();
        LocalDate today = today();
        if (!vScope.isEmpty()) {
            MapSqlParameterSource p = new MapSqlParameterSource("s", vScope).addValue("lim", limit).addValue("v", onlyVehicle);
            jdbc.query("""
                    SELECT dd.due_kind, dd.subject_id, dd.subject_label, v.make || ' ' || v.model, dd.due_on, dd.org_unit_id
                      FROM v_due_date dd JOIN vehicle v ON v.id = dd.subject_id
                     WHERE dd.subject_kind = 'VEHICULE' AND dd.org_unit_id IN (:s)
                       AND (CAST(:v AS uuid) IS NULL OR dd.subject_id = :v)
                       AND (CAST(:lim AS date) IS NULL OR dd.due_on <= :lim)""", p, rs -> {
                out.add(view(rs.getString(1), "VEHICULE", rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), true,
                        rs.getObject(5, LocalDate.class), rs.getObject(6, UUID.class), today));
            });
            // vidange : dernière vidange de chaque véhicule portant un intervalle en mois
            jdbc.query("""
                    SELECT v.id, v.registration, v.make || ' ' || v.model, (oc.done_on + make_interval(months => oc.interval_months))::date, v.org_unit_id
                      FROM vehicle v
                      JOIN LATERAL (SELECT done_on, interval_months FROM oil_change WHERE vehicle_id = v.id ORDER BY done_on DESC, created_at DESC LIMIT 1) oc ON true
                     WHERE v.archived_at IS NULL AND v.org_unit_id IN (:s) AND oc.interval_months IS NOT NULL
                       AND (CAST(:v AS uuid) IS NULL OR v.id = :v)
                       AND (CAST(:lim AS date) IS NULL OR (oc.done_on + make_interval(months => oc.interval_months))::date <= :lim)""", p, rs -> {
                out.add(view("VIDANGE", "VEHICULE", rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), true,
                        rs.getObject(4, LocalDate.class), rs.getObject(5, UUID.class), today));
            });
        }
        if (!dScope.isEmpty()) {
            jdbc.query("""
                    SELECT dd.due_kind, dd.subject_id, dd.subject_label, dr.staff_number, dd.due_on, dd.org_unit_id
                      FROM v_due_date dd JOIN driver dr ON dr.id = dd.subject_id
                     WHERE dd.subject_kind = 'CONDUCTEUR' AND dd.org_unit_id IN (:s)
                       AND (CAST(:lim AS date) IS NULL OR dd.due_on <= :lim)""",
                    new MapSqlParameterSource("s", dScope).addValue("lim", limit), rs -> {
                        out.add(view(rs.getString(1), "CONDUCTEUR", rs.getObject(2, UUID.class), rs.getString(3),
                                "Matricule " + rs.getString(4), false, rs.getObject(5, LocalDate.class), rs.getObject(6, UUID.class), today));
                    });
        }
        return out;
    }

    static DueView view(String kind, String subjectKind, UUID id, String subject, String detail, boolean vehicle, LocalDate due,
                        UUID org, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, due);
        String level = days < 0 ? "EXPIRE" : days <= SOON_DAYS ? "A_ECHEANCE" : "A_JOUR";
        return new DueView(kind, subjectKind, id, subject, detail, vehicle, due, days, level, org);
    }

    // ---------------------------------------------------------------- documents du véhicule

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public List<DocumentView> documents(UUID vehicleId) {
        access.require("vehicle:read", vehicleOrg(vehicleId), "vehicle", vehicleId.toString());
        return jdbc.query("""
                SELECT d.id, d.doc_type, d.reference, d.valid_from, d.valid_until, d.file_id, f.original_name
                  FROM vehicle_document d LEFT JOIN stored_file f ON f.id = d.file_id
                 WHERE d.vehicle_id = :v ORDER BY d.valid_until DESC""",
                new MapSqlParameterSource("v", vehicleId), (rs, i) -> new DocumentView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getObject(6, UUID.class),
                        rs.getString(7)));
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public DocumentView addDocument(UUID vehicleId, String docType, String reference, LocalDate validFrom, LocalDate validUntil, UUID fileId) {
        UUID org = vehicleOrg(vehicleId);
        access.require("vehicle:write", org, "vehicle", vehicleId.toString());
        if (!List.of("ASSURANCE", "CONTROLE_TECHNIQUE", "VIGNETTE", "CARTE_GRISE", "AUTRE").contains(docType)) {
            throw new IllegalArgumentException("Type de document inconnu.");
        }
        if (validUntil == null) throw new IllegalArgumentException("Date de fin de validité obligatoire.");
        if (validFrom != null && validUntil.isBefore(validFrom)) throw new IllegalArgumentException("La fin de validité précède le début.");
        files.requireAttachable(fileId, "VEHICLE_DOCUMENT", org);
        UUID id = jdbc.queryForObject("""
                INSERT INTO vehicle_document(vehicle_id, doc_type, reference, valid_from, valid_until, file_id)
                VALUES (:v, :t, :r, :f, :u, :file) RETURNING id""",
                new MapSqlParameterSource("v", vehicleId).addValue("t", docType).addValue("r", reference == null || reference.isBlank() ? null : reference.strip())
                        .addValue("f", validFrom).addValue("u", validUntil).addValue("file", fileId), UUID.class);
        audit.record(access.currentUser().userId(), "DOCUMENT", "vehicle", vehicleId.toString(), null, null,
                Map.of("documentId", id.toString(), "docType", docType, "validUntil", validUntil.toString()));
        return documents(vehicleId).stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    private UUID vehicleOrg(UUID vehicleId) {
        List<UUID> l = jdbc.queryForList("SELECT org_unit_id FROM vehicle WHERE id = :v", new MapSqlParameterSource("v", vehicleId), UUID.class);
        if (l.isEmpty()) throw new NotFoundException("Véhicule introuvable.");
        return l.get(0);
    }
}
