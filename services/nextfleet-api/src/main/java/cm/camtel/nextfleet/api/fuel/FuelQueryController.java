package cm.camtel.nextfleet.api.fuel;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.identity.AccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Carburant, côté consultation : bons et pleins (avec libellés lisibles), fournisseurs, annulation d'un bon. */
@RestController
public class FuelQueryController {

    public record OrderRow(UUID id, String orderNumber, UUID vehicleId, String registration, UUID supplierId, String supplierName,
                           String requestedBy, String approvedBy, Instant approvedAt, BigDecimal authorizedLiters,
                           BigDecimal authorizedAmountXaf, BigDecimal deliveredLiters, BigDecimal deliveredAmountXaf,
                           LocalDate validUntil, String status, String reason, Instant createdAt, boolean mine, long version) { }

    public record FillRow(UUID id, UUID fuelOrderId, String orderNumber, UUID vehicleId, String registration, String driverName,
                          String supplierName, Instant filledAt, BigDecimal odometerKm, BigDecimal liters, BigDecimal unitPriceXaf,
                          BigDecimal amountXaf, boolean fullTank, String receiptReference, UUID receiptFileId,
                          String exceptionReason, String enteredBy) { }

    public record SupplierRow(UUID id, String code, String name, String kind) { }

    public record SupplierCreate(@NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{1,29}$") String code,
                                 @NotBlank @Size(max = 120) String name,
                                 @NotBlank @Pattern(regexp = "STATION|GARAGE|AUTRE") String kind) { }

    public record CancelRequest(@NotBlank @Size(min = 5, max = 500) String reason) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;

    public FuelQueryController(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    private List<UUID> scope() {
        Set<UUID> s = new LinkedHashSet<>();
        for (String p : List.of("fuel_order:create", "fuel_order:approve", "fuel_fill:create", "cost:read")) s.addAll(access.scopeFor(p));
        return List.copyOf(s);
    }

    /** Bons du périmètre ; un conducteur ne voit que les siens. */
    @GetMapping("/api/v1/fuel-orders")
    @Transactional(readOnly = true)
    public List<OrderRow> orders(@RequestParam(required = false) String status, @RequestParam(required = false) UUID vehicleId) {
        List<UUID> s = scope();
        if (s.isEmpty()) return List.of();
        UUID me = access.currentUser().userId();
        boolean wide = !access.scopeFor("fuel_order:approve").isEmpty() || !access.scopeFor("cost:read").isEmpty()
                || !access.scopeFor("vehicle:write").isEmpty();
        return jdbc.query("""
                SELECT o.id, o.order_number, o.vehicle_id, v.registration, o.supplier_id, sp.name, rq.display_name, ap.display_name,
                       o.approved_at, o.authorized_liters, o.authorized_amount, o.delivered_liters, o.delivered_amount, o.valid_until,
                       o.status, o.reason, o.created_at, o.requested_by = :me, o.version
                  FROM fuel_order o JOIN vehicle v ON v.id = o.vehicle_id JOIN supplier sp ON sp.id = o.supplier_id
                  JOIN app_user rq ON rq.id = o.requested_by LEFT JOIN app_user ap ON ap.id = o.approved_by
                 WHERE o.org_unit_id IN (:s) AND (:wide OR o.requested_by = :me)
                   AND (CAST(:st AS text) IS NULL OR o.status = :st) AND (CAST(:v AS uuid) IS NULL OR o.vehicle_id = :v)
                 ORDER BY o.created_at DESC LIMIT 500""",
                new MapSqlParameterSource("s", s).addValue("me", me).addValue("wide", wide).addValue("st", status).addValue("v", vehicleId),
                (rs, i) -> new OrderRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(), rs.getBigDecimal(10), rs.getBigDecimal(11),
                        rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getObject(14, LocalDate.class), rs.getString(15), rs.getString(16),
                        rs.getTimestamp(17).toInstant(), rs.getBoolean(18), rs.getLong(19)));
    }

    @GetMapping("/api/v1/fuel-fills")
    @Transactional(readOnly = true)
    public List<FillRow> fills(@RequestParam(required = false) UUID vehicleId,
                               @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        List<UUID> s = scope();
        if (s.isEmpty()) return List.of();
        UUID me = access.currentUser().userId();
        boolean wide = !access.scopeFor("fuel_order:approve").isEmpty() || !access.scopeFor("cost:read").isEmpty()
                || !access.scopeFor("vehicle:write").isEmpty();
        return jdbc.query("""
                SELECT f.id, f.fuel_order_id, o.order_number, f.vehicle_id, v.registration, d.full_name, sp.name, f.filled_at, f.odometer_km,
                       f.liters, f.unit_price, f.amount, f.full_tank, f.receipt_reference, f.receipt_file_id, f.exception_reason, e.display_name
                  FROM fuel_fill f JOIN vehicle v ON v.id = f.vehicle_id JOIN supplier sp ON sp.id = f.supplier_id
                  JOIN app_user e ON e.id = f.entered_by LEFT JOIN driver d ON d.id = f.driver_id
                  LEFT JOIN fuel_order o ON o.id = f.fuel_order_id
                 WHERE v.org_unit_id IN (:s) AND (:wide OR f.entered_by = :me)
                   AND (CAST(:v AS uuid) IS NULL OR f.vehicle_id = :v)
                   AND (CAST(:from AS date) IS NULL OR (f.filled_at AT TIME ZONE 'Africa/Douala')::date >= :from)
                   AND (CAST(:to AS date) IS NULL OR (f.filled_at AT TIME ZONE 'Africa/Douala')::date <= :to)
                 ORDER BY f.filled_at DESC LIMIT 1000""",
                new MapSqlParameterSource("s", s).addValue("me", me).addValue("wide", wide).addValue("v", vehicleId)
                        .addValue("from", from).addValue("to", to),
                (rs, i) -> new FillRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getBigDecimal(9),
                        rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBoolean(13), rs.getString(14),
                        rs.getObject(15, UUID.class), rs.getString(16), rs.getString(17)));
    }

    @GetMapping("/api/v1/suppliers")
    @Transactional(readOnly = true)
    public List<SupplierRow> suppliers(@RequestParam(required = false) String kind) {
        access.currentUser();
        return jdbc.query("SELECT id, code, name, kind FROM supplier WHERE (CAST(:k AS text) IS NULL OR kind = :k) ORDER BY name",
                new MapSqlParameterSource("k", kind), (rs, i) -> new SupplierRow(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4)));
    }

    /** Référentiel fournisseurs : gestionnaire de parc (vehicle:write) ou administration des paramètres. */
    @PostMapping("/api/v1/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public SupplierRow createSupplier(@Valid @RequestBody SupplierCreate c) {
        UUID me = access.currentUser().userId();
        if (access.scopeFor("vehicle:write").isEmpty() && access.scopeFor("admin:settings").isEmpty()) {
            audit.recordDenied(me, "supplier", "new", "vehicle:write");
            throw new AccessDeniedException("Accès refusé");
        }
        UUID id = jdbc.queryForObject("INSERT INTO supplier(code, name, kind) VALUES (:c, :n, :k) RETURNING id",
                new MapSqlParameterSource("c", c.code()).addValue("n", c.name().strip()).addValue("k", c.kind()), UUID.class);
        audit.record(me, "CREATE", "supplier", id.toString(), null, null, c);
        return new SupplierRow(id, c.code(), c.name().strip(), c.kind());
    }

    /** Annulation d'un bon non consommé (brouillon, soumis ou validé sans plein) ; motif obligatoire. */
    @PostMapping("/api/v1/fuel-orders/{id}/cancel")
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public Map<String, String> cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        List<Map<String, Object>> o = jdbc.queryForList("SELECT org_unit_id, status, requested_by, delivered_liters FROM fuel_order WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (o.isEmpty()) throw new NotFoundException("Bon introuvable.");
        UUID org = (UUID) o.get(0).get("org_unit_id");
        UUID me = access.currentUser().userId();
        boolean requester = me.equals(o.get(0).get("requested_by"));
        access.require(requester ? "fuel_order:create" : "fuel_order:approve", org, "fuel_order", id.toString());
        String st = (String) o.get(0).get("status");
        if (!List.of("DRAFT", "SUBMITTED", "APPROVED").contains(st) || ((BigDecimal) o.get(0).get("delivered_liters")).signum() > 0) {
            throw new IllegalArgumentException("Seul un bon non consommé peut être annulé.");
        }
        jdbc.update("UPDATE fuel_order SET status = 'CANCELLED', version = version + 1 WHERE id = :id", new MapSqlParameterSource("id", id));
        audit.record(me, "CANCEL", "fuel_order", id.toString(), r.reason().strip(), Map.of("status", st), Map.of("status", "CANCELLED"));
        return Map.of("status", "CANCELLED");
    }
}
