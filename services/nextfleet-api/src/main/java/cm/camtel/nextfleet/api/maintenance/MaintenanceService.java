package cm.camtel.nextfleet.api.maintenance;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.StaleVersionException;
import cm.camtel.nextfleet.api.files.FileService;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Atelier (chap. 04) : demandes et ordres d'intervention avec cycle DEMANDE → QUALIFIÉE → PLANIFIÉE → EN COURS → CLÔTURÉE
 * (ou ANNULÉE), vidanges, contrôles par points, pneumatiques. La clôture exige kilométrage, travaux, coût, justificatif et
 * visa technique (maintenance:validate) — vérifié ici ET par la base.
 */
@Service
public class MaintenanceService {

    public record Category(String code, String label) { }

    public record OrderView(UUID id, UUID vehicleId, String registration, String vehicleLabel, String categoryCode,
                            String categoryLabel, String title, String status, String garageLabel, UUID garageSupplierId,
                            Instant immobilisedFrom, Instant immobilisedTo, BigDecimal odometerKm, BigDecimal costXaf,
                            String workDone, UUID proofFileId, String technicalVisaBy, String requestedBy, Instant createdAt,
                            long version) { }

    public record OrderCreate(UUID vehicleId, String categoryCode, String title, String garageLabel, UUID garageSupplierId,
                              BigDecimal odometerKm) { }

    public record Transition(String status, Instant immobilisedFrom, Instant immobilisedTo, String garageLabel,
                             UUID garageSupplierId, BigDecimal odometerKm, BigDecimal costXaf, String workDone,
                             UUID proofFileId, String reason) { }

    public record OilChangeView(UUID id, UUID vehicleId, String registration, LocalDate doneOn, BigDecimal odometerKm,
                                String oilLabel, BigDecimal oilLiters, List<String> filters, Integer intervalKm,
                                Integer intervalMonths, BigDecimal nextDueKm, LocalDate nextDueOn, BigDecimal costXaf,
                                String garageLabel, UUID maintenanceOrderId, String author) { }

    public record OilChangeCreate(UUID vehicleId, UUID maintenanceOrderId, LocalDate doneOn, BigDecimal odometerKm,
                                  String oilLabel, BigDecimal oilLiters, boolean oilFilter, boolean fuelFilter, boolean airFilter,
                                  boolean transmissionOil, boolean coolant, boolean washerFluid, Integer intervalKm,
                                  Integer intervalMonths, BigDecimal costXaf, String garageLabel) { }

    public record InspectionPoint(String code, int position, String label, String hint) { }

    public record InspectionItem(String pointCode, String state, boolean critical, String comment) { }

    public record InspectionView(UUID id, UUID vehicleId, String registration, String kind, Instant inspectedAt,
                                 BigDecimal odometerKm, String result, String notes, String author, List<InspectionItem> items) { }

    public record InspectionCreate(UUID vehicleId, UUID missionId, String kind, Instant inspectedAt, BigDecimal odometerKm,
                                   String notes, List<InspectionItem> items) { }

    public record TireView(UUID tireId, String serial, String brand, String size, String position, String condition, LocalDate since) { }

    public record TireEventCreate(UUID tireId, String serial, String brand, String size, UUID vehicleId, String kind,
                                  String position, String condition, LocalDate occurredOn, BigDecimal odometerKm, String note) { }

    /** Transitions permises : l'interface ne propose que celles-ci, le serveur refuse les autres. */
    static final Map<String, Set<String>> NEXT = Map.of(
            "DEMANDE", Set.of("QUALIFIEE", "ANNULEE"),
            "QUALIFIEE", Set.of("PLANIFIEE", "EN_COURS", "ANNULEE"),
            "PLANIFIEE", Set.of("EN_COURS", "ANNULEE"),
            "EN_COURS", Set.of("CLOTUREE", "ANNULEE"),
            "CLOTUREE", Set.of(),
            "ANNULEE", Set.of());

    private static final String ORDER_SELECT = """
            SELECT mo.id, mo.vehicle_id, v.registration, v.make || ' ' || v.model, mo.category_code, c.label, mo.title, mo.status,
                   coalesce(mo.garage_label, s.name), mo.garage_supplier_id, mo.immobilised_from, mo.immobilised_to, mo.odometer_km,
                   mo.cost_xaf, mo.work_done, mo.proof_file_id, tv.display_name, rq.display_name, mo.created_at, mo.version, v.org_unit_id
              FROM maintenance_order mo
              JOIN vehicle v ON v.id = mo.vehicle_id
              JOIN maintenance_category c ON c.code = mo.category_code
              LEFT JOIN supplier s ON s.id = mo.garage_supplier_id
              LEFT JOIN app_user tv ON tv.id = mo.technical_visa_by
              JOIN app_user rq ON rq.id = mo.requested_by
            """;

    private static final RowMapper<OrderView> ORDER = (rs, i) -> new OrderView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
            rs.getObject(10, UUID.class), ts(rs.getTimestamp(11)), ts(rs.getTimestamp(12)), rs.getBigDecimal(13), rs.getBigDecimal(14),
            rs.getString(15), rs.getObject(16, UUID.class), rs.getString(17), rs.getString(18), ts(rs.getTimestamp(19)), rs.getLong(20));

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final FileService files;

    public MaintenanceService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit, FileService files) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public List<Category> categories() {
        return jdbc.query("SELECT code, label FROM maintenance_category ORDER BY label", new MapSqlParameterSource(),
                (rs, i) -> new Category(rs.getString(1), rs.getString(2)));
    }

    // ---------------------------------------------------------------- ordres d'intervention

    @Transactional(readOnly = true)
    public List<OrderView> orders(String status, UUID vehicleId, boolean openOnly) {
        List<UUID> scope = readScope();
        if (scope.isEmpty()) return List.of();
        return jdbc.query(ORDER_SELECT + """
                 WHERE v.org_unit_id IN (:s) AND (CAST(:st AS text) IS NULL OR mo.status = :st)
                   AND (CAST(:v AS uuid) IS NULL OR mo.vehicle_id = :v)
                   AND (NOT :open OR mo.status NOT IN ('CLOTUREE','ANNULEE'))
                 ORDER BY CASE mo.status WHEN 'EN_COURS' THEN 0 WHEN 'PLANIFIEE' THEN 1 WHEN 'QUALIFIEE' THEN 2 WHEN 'DEMANDE' THEN 3 ELSE 4 END,
                          mo.created_at DESC LIMIT 500""",
                new MapSqlParameterSource("s", scope).addValue("st", status).addValue("v", vehicleId).addValue("open", openOnly), ORDER);
    }

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public OrderView order(UUID id) {
        Object[] o = findOrder(id);
        requireRead((UUID) o[1]);
        return (OrderView) o[0];
    }

    /** Demande d'intervention : atelier, gestionnaire du parc ou conducteur (signalement de panne). */
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public OrderView create(OrderCreate c) {
        AuthenticatedUser me = access.currentUser();
        UUID org = vehicleOrg(c.vehicleId());
        requireAny(List.of("maintenance:write", "vehicle:write", "mission:drive"), org, "maintenance_order", "new");
        if (c.title() == null || c.title().strip().length() < 3) throw new IllegalArgumentException("Décrivez l'intervention (3 caractères au moins).");
        requireCategory(c.categoryCode());
        UUID id = jdbc.queryForObject("""
                INSERT INTO maintenance_order(vehicle_id, category_code, title, garage_label, garage_supplier_id, odometer_km, requested_by)
                VALUES (:v, :c, :t, :g, :gs, :km, :me) RETURNING id""",
                new MapSqlParameterSource("v", c.vehicleId()).addValue("c", c.categoryCode()).addValue("t", c.title().strip())
                        .addValue("g", blank(c.garageLabel())).addValue("gs", c.garageSupplierId()).addValue("km", c.odometerKm())
                        .addValue("me", me.userId()), UUID.class);
        OrderView v = (OrderView) findOrder(id)[0];
        audit.record(me.userId(), "CREATE", "maintenance_order", id.toString(), null, null, v);
        return v;
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public OrderView transition(UUID id, long expectedVersion, Transition t) {
        AuthenticatedUser me = access.currentUser();
        Object[] found = findOrder(id);
        OrderView before = (OrderView) found[0];
        UUID org = (UUID) found[1];
        access.require("maintenance:write", org, "maintenance_order", id.toString());
        String to = t.status();
        if (to == null || !NEXT.getOrDefault(before.status(), Set.of()).contains(to)) {
            throw new IllegalArgumentException("Passage de « " + label(before.status()) + " » à « " + label(to) + " » non permis.");
        }
        if ("ANNULEE".equals(to) && (t.reason() == null || t.reason().strip().length() < 5)) {
            throw new IllegalArgumentException("Motif d'annulation obligatoire (5 caractères au moins).");
        }
        UUID visa = null;
        BigDecimal km = t.odometerKm() != null ? t.odometerKm() : before.odometerKm();
        BigDecimal cost = t.costXaf() != null ? t.costXaf() : before.costXaf();
        String work = t.workDone() != null && !t.workDone().isBlank() ? t.workDone().strip() : before.workDone();
        UUID proof = t.proofFileId() != null ? t.proofFileId() : before.proofFileId();
        if ("CLOTUREE".equals(to)) {
            access.require("maintenance:validate", org, "maintenance_order", id.toString());
            List<String> missing = new ArrayList<>();
            if (km == null) missing.add("kilométrage");
            if (work == null || work.isBlank()) missing.add("travaux réalisés");
            if (cost == null) missing.add("coût");
            if (proof == null) missing.add("justificatif");
            if (!missing.isEmpty()) throw new IllegalArgumentException("Clôture impossible, il manque : " + String.join(", ", missing) + ".");
            files.requireAttachable(proof, "MAINTENANCE", org);
            visa = me.userId();
        } else if (t.proofFileId() != null) {
            files.requireAttachable(t.proofFileId(), "MAINTENANCE", org);
        }
        Instant immFrom = t.immobilisedFrom() != null ? t.immobilisedFrom() : before.immobilisedFrom();
        if ("EN_COURS".equals(to) && immFrom == null) immFrom = Instant.now();
        Instant immTo = t.immobilisedTo() != null ? t.immobilisedTo() : before.immobilisedTo();
        if (("CLOTUREE".equals(to) || "ANNULEE".equals(to)) && immFrom != null && immTo == null) immTo = Instant.now();
        int n = jdbc.update("""
                UPDATE maintenance_order SET status = :st, immobilised_from = :f, immobilised_to = :t,
                       garage_label = coalesce(:g, garage_label), garage_supplier_id = coalesce(:gs, garage_supplier_id),
                       odometer_km = :km, cost_xaf = :cost, work_done = :w, proof_file_id = :p,
                       technical_visa_by = coalesce(:visa, technical_visa_by), version = version + 1
                 WHERE id = :id AND version = :v""",
                new MapSqlParameterSource("st", to).addValue("f", tsOrNull(immFrom)).addValue("t", tsOrNull(immTo))
                        .addValue("g", blank(t.garageLabel())).addValue("gs", t.garageSupplierId()).addValue("km", km).addValue("cost", cost)
                        .addValue("w", work).addValue("p", proof).addValue("visa", visa).addValue("id", id).addValue("v", expectedVersion));
        if (n == 0) throw new StaleVersionException("version " + expectedVersion);
        // disponibilité du véhicule : immobilisé pendant l'intervention, rendu disponible à la fin
        if ("EN_COURS".equals(to)) {
            jdbc.update("""
                    UPDATE vehicle SET status = 'MAINTENANCE', version = version + 1, updated_at = now()
                     WHERE id = :v AND status IN ('AVAILABLE','IN_SERVICE','OUT_OF_SERVICE')""", new MapSqlParameterSource("v", before.vehicleId()));
        } else if ("CLOTUREE".equals(to) || ("ANNULEE".equals(to) && "EN_COURS".equals(before.status()))) {
            Integer stillOpen = jdbc.queryForObject("""
                    SELECT count(*) FROM maintenance_order WHERE vehicle_id = :v AND status = 'EN_COURS' AND id <> :id""",
                    new MapSqlParameterSource("v", before.vehicleId()).addValue("id", id), Integer.class);
            if (stillOpen != null && stillOpen == 0) {
                jdbc.update("UPDATE vehicle SET status = 'AVAILABLE', version = version + 1, updated_at = now() WHERE id = :v AND status = 'MAINTENANCE'",
                        new MapSqlParameterSource("v", before.vehicleId()));
            }
        }
        if ("CLOTUREE".equals(to) && km != null) {
            jdbc.update("""
                    INSERT INTO vehicle_odometer(vehicle_id, read_at, value_km, source, author_id, justification)
                    VALUES (:v, now(), :km, 'MAINTENANCE', :me, :j)""",
                    new MapSqlParameterSource("v", before.vehicleId()).addValue("km", km).addValue("me", me.userId())
                            .addValue("j", "Clôture de l'intervention " + id));
        }
        OrderView after = (OrderView) findOrder(id)[0];
        audit.record(me.userId(), "STATUS_" + to, "maintenance_order", id.toString(), blank(t.reason()), before, after);
        return after;
    }

    // ---------------------------------------------------------------- vidanges

    @Transactional(readOnly = true)
    public List<OilChangeView> oilChanges(UUID vehicleId) {
        List<UUID> scope = readScope();
        if (scope.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT oc.id, oc.vehicle_id, v.registration, oc.done_on, oc.odometer_km, oc.oil_label, oc.oil_liters,
                       oc.oil_filter, oc.fuel_filter, oc.air_filter, oc.transmission_oil, oc.coolant, oc.washer_fluid,
                       oc.interval_km, oc.interval_months, oc.next_due_km,
                       CASE WHEN oc.interval_months IS NULL THEN NULL ELSE (oc.done_on + make_interval(months => oc.interval_months))::date END,
                       oc.cost_xaf, oc.garage_label, oc.maintenance_order_id, a.display_name
                  FROM oil_change oc JOIN vehicle v ON v.id = oc.vehicle_id JOIN app_user a ON a.id = oc.author_id
                 WHERE v.org_unit_id IN (:s) AND (CAST(:v AS uuid) IS NULL OR oc.vehicle_id = :v)
                 ORDER BY oc.done_on DESC, oc.created_at DESC LIMIT 500""",
                new MapSqlParameterSource("s", scope).addValue("v", vehicleId), (rs, i) -> {
                    List<String> f = new ArrayList<>();
                    if (rs.getBoolean(8)) f.add("Filtre à huile");
                    if (rs.getBoolean(9)) f.add("Filtre à carburant");
                    if (rs.getBoolean(10)) f.add("Filtre à air");
                    if (rs.getBoolean(11)) f.add("Huile de boîte");
                    if (rs.getBoolean(12)) f.add("Liquide de refroidissement");
                    if (rs.getBoolean(13)) f.add("Lave-glace");
                    return new OilChangeView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getString(6), rs.getBigDecimal(7), f,
                            (Integer) rs.getObject(14), rs.getObject(15) == null ? null : ((Number) rs.getObject(15)).intValue(),
                            rs.getBigDecimal(16), rs.getObject(17, LocalDate.class), rs.getBigDecimal(18), rs.getString(19),
                            rs.getObject(20, UUID.class), rs.getString(21));
                });
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public OilChangeView addOilChange(OilChangeCreate c) {
        AuthenticatedUser me = access.currentUser();
        UUID org = vehicleOrg(c.vehicleId());
        access.require("maintenance:write", org, "oil_change", "new");
        if (c.doneOn() == null || c.odometerKm() == null) throw new IllegalArgumentException("Date et kilométrage de la vidange obligatoires.");
        if (c.doneOn().isAfter(LocalDate.now().plusDays(1))) throw new IllegalArgumentException("La date de vidange ne peut pas être future.");
        if (c.maintenanceOrderId() != null && c.costXaf() != null) {
            throw new IllegalArgumentException("Vidange rattachée à un ordre d'intervention : le coût est porté par l'ordre (pas de double comptage).");
        }
        UUID id = jdbc.queryForObject("""
                INSERT INTO oil_change(vehicle_id, maintenance_order_id, done_on, odometer_km, oil_label, oil_liters, oil_filter, fuel_filter,
                                       air_filter, transmission_oil, coolant, washer_fluid, interval_km, interval_months, cost_xaf,
                                       garage_label, author_id)
                VALUES (:v, :mo, :d, :km, :ol, :oll, :of, :ff, :af, :to, :co, :wf, :ik, :im, :cost, :g, :me) RETURNING id""",
                new MapSqlParameterSource("v", c.vehicleId()).addValue("mo", c.maintenanceOrderId()).addValue("d", c.doneOn())
                        .addValue("km", c.odometerKm()).addValue("ol", blank(c.oilLabel())).addValue("oll", c.oilLiters())
                        .addValue("of", c.oilFilter()).addValue("ff", c.fuelFilter()).addValue("af", c.airFilter())
                        .addValue("to", c.transmissionOil()).addValue("co", c.coolant()).addValue("wf", c.washerFluid())
                        .addValue("ik", c.intervalKm()).addValue("im", c.intervalMonths()).addValue("cost", c.costXaf())
                        .addValue("g", blank(c.garageLabel())).addValue("me", me.userId()), UUID.class);
        jdbc.update("""
                INSERT INTO vehicle_odometer(vehicle_id, read_at, value_km, source, author_id, justification)
                VALUES (:v, :at, :km, 'MAINTENANCE', :me, 'Vidange')""",
                new MapSqlParameterSource("v", c.vehicleId()).addValue("at", Timestamp.from(
                                c.doneOn().equals(LocalDate.now(java.time.ZoneId.of("Africa/Douala"))) ? Instant.now()
                                        : c.doneOn().atTime(12, 0).atZone(java.time.ZoneId.of("Africa/Douala")).toInstant()))
                        .addValue("km", c.odometerKm()).addValue("me", me.userId()));
        audit.record(me.userId(), "CREATE", "oil_change", id.toString(), null, null, c);
        return oilChanges(c.vehicleId()).stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- contrôles

    @Transactional(readOnly = true)
    public List<InspectionPoint> points() {
        return jdbc.query("SELECT code, position, label, hint FROM inspection_point WHERE active ORDER BY position", new MapSqlParameterSource(),
                (rs, i) -> new InspectionPoint(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)));
    }

    @Transactional(readOnly = true)
    public List<InspectionView> inspections(UUID vehicleId) {
        List<UUID> scope = readScope();
        if (scope.isEmpty()) return List.of();
        List<InspectionView> heads = jdbc.query("""
                SELECT i.id, i.vehicle_id, v.registration, i.kind, i.inspected_at, i.odometer_km, i.result, i.notes, a.display_name
                  FROM inspection i JOIN vehicle v ON v.id = i.vehicle_id JOIN app_user a ON a.id = i.author_id
                 WHERE v.org_unit_id IN (:s) AND (CAST(:v AS uuid) IS NULL OR i.vehicle_id = :v)
                 ORDER BY i.inspected_at DESC LIMIT 300""",
                new MapSqlParameterSource("s", scope).addValue("v", vehicleId), (rs, i) -> new InspectionView(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), ts(rs.getTimestamp(5)), rs.getBigDecimal(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), new ArrayList<>()));
        if (heads.isEmpty()) return heads;
        Map<UUID, InspectionView> byId = new java.util.HashMap<>();
        heads.forEach(h -> byId.put(h.id(), h));
        jdbc.query("""
                SELECT it.inspection_id, it.point_code, it.state, it.critical, it.comment FROM inspection_item it
                  JOIN inspection_point p ON p.code = it.point_code WHERE it.inspection_id IN (:ids) ORDER BY p.position""",
                new MapSqlParameterSource("ids", byId.keySet()), rs -> {
                    byId.get(rs.getObject(1, UUID.class)).items().add(new InspectionItem(rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5)));
                });
        return heads;
    }

    /** Résultat calculé : un défaut critique → NON CONFORME ; un défaut ou point à surveiller → RÉSERVES ; sinon CONFORME. */
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public InspectionView addInspection(InspectionCreate c) {
        AuthenticatedUser me = access.currentUser();
        UUID org = vehicleOrg(c.vehicleId());
        requireAny(List.of("maintenance:write", "mission:drive"), org, "inspection", "new");
        if (!List.of("PERIODIQUE", "AVANT_MISSION", "APRES_MISSION", "INOPINE").contains(c.kind())) throw new IllegalArgumentException("Type de contrôle inconnu.");
        if (c.items() == null || c.items().isEmpty()) throw new IllegalArgumentException("Renseignez au moins un point de contrôle.");
        Set<String> seen = new LinkedHashSet<>();
        boolean critical = false;
        boolean reserve = false;
        for (InspectionItem it : c.items()) {
            if (!seen.add(it.pointCode())) throw new IllegalArgumentException("Point en double : " + it.pointCode());
            if (!List.of("OK", "A_SURVEILLER", "DEFAUT").contains(it.state())) throw new IllegalArgumentException("État inconnu : " + it.state());
            if ("DEFAUT".equals(it.state()) && (it.comment() == null || it.comment().isBlank())) {
                throw new IllegalArgumentException("Décrivez le défaut constaté (" + it.pointCode() + ").");
            }
            if ("DEFAUT".equals(it.state()) && it.critical()) critical = true;
            if (!"OK".equals(it.state())) reserve = true;
        }
        String result = critical ? "NON_CONFORME" : reserve ? "RESERVES" : "CONFORME";
        UUID driver = jdbc.queryForList("SELECT id FROM driver WHERE user_id = :me", new MapSqlParameterSource("me", me.userId()), UUID.class)
                .stream().findFirst().orElse(null);
        UUID id = jdbc.queryForObject("""
                INSERT INTO inspection(vehicle_id, driver_id, mission_id, kind, inspected_at, odometer_km, result, notes, author_id)
                VALUES (:v, :d, :m, :k, :at, :km, :r, :n, :me) RETURNING id""",
                new MapSqlParameterSource("v", c.vehicleId()).addValue("d", driver).addValue("m", c.missionId()).addValue("k", c.kind())
                        .addValue("at", Timestamp.from(c.inspectedAt() == null ? Instant.now() : c.inspectedAt())).addValue("km", c.odometerKm())
                        .addValue("r", result).addValue("n", blank(c.notes())).addValue("me", me.userId()), UUID.class);
        for (InspectionItem it : c.items()) {
            jdbc.update("""
                    INSERT INTO inspection_item(inspection_id, point_code, state, critical, comment) VALUES (:i, :p, :s, :c, :cm)""",
                    new MapSqlParameterSource("i", id).addValue("p", it.pointCode()).addValue("s", it.state())
                            .addValue("c", it.critical()).addValue("cm", blank(it.comment())));
        }
        if (critical) {
            // un défaut critique ouvre automatiquement une demande d'intervention (pas de véhicule dangereux sans suite)
            jdbc.update("""
                    INSERT INTO maintenance_order(vehicle_id, category_code, title, requested_by)
                    VALUES (:v, 'AUTRE', :t, :me)""",
                    new MapSqlParameterSource("v", c.vehicleId()).addValue("t", "Défaut critique relevé au contrôle du "
                            + java.time.LocalDate.now(java.time.ZoneId.of("Africa/Douala"))).addValue("me", me.userId()));
        }
        audit.record(me.userId(), "CREATE", "inspection", id.toString(), null, null, Map.of("result", result, "items", c.items().size()));
        return inspections(c.vehicleId()).stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- pneumatiques

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public List<TireView> tires(UUID vehicleId) {
        requireRead(vehicleOrg(vehicleId));
        return jdbc.query("""
                WITH last AS (SELECT DISTINCT ON (tire_id) tire_id, vehicle_id, kind, condition, occurred_on
                                FROM tire_event ORDER BY tire_id, occurred_on DESC, id DESC),
                     pos AS (SELECT DISTINCT ON (tire_id) tire_id, position
                               FROM tire_event WHERE position IS NOT NULL ORDER BY tire_id, occurred_on DESC, id DESC)
                SELECT t.id, t.serial_label, t.brand, t.size, pos.position, last.condition, last.occurred_on
                  FROM last JOIN tire t ON t.id = last.tire_id LEFT JOIN pos ON pos.tire_id = last.tire_id
                 WHERE last.vehicle_id = :v AND last.kind NOT IN ('DEMONTAGE','REMPLACEMENT')
                 ORDER BY pos.position NULLS LAST""",
                new MapSqlParameterSource("v", vehicleId), (rs, i) -> new TireView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class)));
    }

    @Transactional(noRollbackFor = AccessDeniedException.class)
    public List<TireView> addTireEvent(TireEventCreate c) {
        AuthenticatedUser me = access.currentUser();
        UUID org = vehicleOrg(c.vehicleId());
        access.require("maintenance:write", org, "tire_event", "new");
        if (!List.of("MONTAGE", "PERMUTATION", "DEMONTAGE", "REMPLACEMENT", "CONTROLE").contains(c.kind())) throw new IllegalArgumentException("Opération inconnue.");
        UUID tire = c.tireId();
        if (tire == null) {
            if (!"MONTAGE".equals(c.kind())) throw new IllegalArgumentException("Pneu inconnu : seul un montage peut créer un pneu.");
            if (c.size() == null || c.size().isBlank()) throw new IllegalArgumentException("Dimension du pneu obligatoire (ex. 265/65 R17).");
            tire = jdbc.queryForObject("INSERT INTO tire(serial_label, brand, size) VALUES (:s, :b, :z) RETURNING id",
                    new MapSqlParameterSource("s", blank(c.serial())).addValue("b", blank(c.brand())).addValue("z", c.size().strip()), UUID.class);
        }
        jdbc.update("""
                INSERT INTO tire_event(tire_id, vehicle_id, kind, position, condition, occurred_on, odometer_km, note, author_id)
                VALUES (:t, :v, :k, :p, :c, :d, :km, :n, :me)""",
                new MapSqlParameterSource("t", tire).addValue("v", c.vehicleId()).addValue("k", c.kind()).addValue("p", blank(c.position()))
                        .addValue("c", blank(c.condition())).addValue("d", c.occurredOn() == null ? LocalDate.now() : c.occurredOn())
                        .addValue("km", c.odometerKm()).addValue("n", blank(c.note())).addValue("me", me.userId()));
        audit.record(me.userId(), "TIRE_" + c.kind(), "vehicle", c.vehicleId().toString(), blank(c.note()), null,
                Map.of("tireId", tire.toString(), "position", c.position() == null ? "" : c.position()));
        return tires(c.vehicleId());
    }

    // ---------------------------------------------------------------- utilitaires

    private List<UUID> readScope() {
        Set<UUID> s = new LinkedHashSet<>(access.scopeFor("vehicle:read"));
        s.addAll(access.scopeFor("maintenance:write"));
        return new ArrayList<>(s);
    }

    private void requireRead(UUID org) {
        if (!readScope().contains(org)) access.require("vehicle:read", org, "maintenance", org.toString());
    }

    private void requireAny(List<String> perms, UUID org, String type, String id) {
        for (String p : perms) if (access.scopeFor(p).contains(org)) return;
        access.require(perms.get(0), org, type, id);
    }

    private void requireCategory(String code) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM maintenance_category WHERE code = :c", new MapSqlParameterSource("c", code), Integer.class);
        if (n == null || n == 0) throw new IllegalArgumentException("Catégorie d'intervention inconnue.");
    }

    private Object[] findOrder(UUID id) {
        List<Object[]> l = jdbc.query(ORDER_SELECT + " WHERE mo.id = :id", new MapSqlParameterSource("id", id),
                (rs, i) -> new Object[]{ORDER.mapRow(rs, i), rs.getObject(21, UUID.class)});
        if (l.isEmpty()) throw new NotFoundException("Intervention introuvable.");
        return l.get(0);
    }

    private UUID vehicleOrg(UUID vehicleId) {
        if (vehicleId == null) throw new IllegalArgumentException("Véhicule obligatoire.");
        List<UUID> l = jdbc.queryForList("SELECT org_unit_id FROM vehicle WHERE id = :v AND archived_at IS NULL",
                new MapSqlParameterSource("v", vehicleId), UUID.class);
        if (l.isEmpty()) throw new NotFoundException("Véhicule introuvable ou archivé.");
        return l.get(0);
    }

    static String label(String status) {
        if (status == null) return "?";
        return switch (status) {
            case "DEMANDE" -> "Demandée";
            case "QUALIFIEE" -> "Qualifiée";
            case "PLANIFIEE" -> "Planifiée";
            case "EN_COURS" -> "En cours";
            case "CLOTUREE" -> "Clôturée";
            case "ANNULEE" -> "Annulée";
            default -> status;
        };
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp tsOrNull(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
