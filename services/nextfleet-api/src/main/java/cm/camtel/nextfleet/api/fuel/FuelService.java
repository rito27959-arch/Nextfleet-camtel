package cm.camtel.nextfleet.api.fuel;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.common.ApiExceptionHandler.NotFoundException;
import cm.camtel.nextfleet.api.fuel.FuelController.FuelFillCreate;
import cm.camtel.nextfleet.api.fuel.FuelController.FuelOrderCreate;
import cm.camtel.nextfleet.api.identity.AccessService;
import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import cm.camtel.nextfleet.domain.fuel.BusinessRuleViolation;
import cm.camtel.nextfleet.domain.fuel.ConsumptionResult;
import cm.camtel.nextfleet.domain.fuel.FuelFillRecord;
import cm.camtel.nextfleet.domain.fuel.FullToFullConsumptionCalculator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FuelService {

    public record FuelOrderView(UUID id, String orderNumber, UUID vehicleId, UUID supplierId, UUID orgUnitId,
                                UUID requestedBy, UUID approvedBy, BigDecimal authorizedLiters, BigDecimal authorizedAmountXaf,
                                BigDecimal deliveredLiters, BigDecimal deliveredAmountXaf, LocalDate validUntil,
                                String status, long version) { }

    public record FuelFillView(UUID id, UUID fuelOrderId, UUID vehicleId, Instant filledAt, BigDecimal odometerKm,
                               BigDecimal liters, BigDecimal unitPriceXaf, BigDecimal amountXaf, boolean fullTank,
                               String receiptReference) { }

    public record IdempotentResult(FuelFillView fill, boolean replayed) { }

    private static final RowMapper<FuelOrderView> ORDER = (rs, i) -> new FuelOrderView(
            rs.getObject("id", UUID.class), rs.getString("order_number"), rs.getObject("vehicle_id", UUID.class),
            rs.getObject("supplier_id", UUID.class), rs.getObject("org_unit_id", UUID.class),
            rs.getObject("requested_by", UUID.class), rs.getObject("approved_by", UUID.class),
            rs.getBigDecimal("authorized_liters"), rs.getBigDecimal("authorized_amount"),
            rs.getBigDecimal("delivered_liters"), rs.getBigDecimal("delivered_amount"),
            rs.getObject("valid_until", LocalDate.class), rs.getString("status"), rs.getLong("version"));

    private static final RowMapper<FuelFillView> FILL = (rs, i) -> new FuelFillView(
            rs.getObject("id", UUID.class), rs.getObject("fuel_order_id", UUID.class), rs.getObject("vehicle_id", UUID.class),
            rs.getTimestamp("filled_at").toInstant(), rs.getBigDecimal("odometer_km"), rs.getBigDecimal("liters"),
            rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"), rs.getBoolean("full_tank"),
            rs.getString("receipt_reference"));

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final FullToFullConsumptionCalculator calculator = new FullToFullConsumptionCalculator();

    public FuelService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public FuelOrderView createOrder(FuelOrderCreate c) {
        UUID org = vehicleOrg(c.vehicleId());
        access.require("fuel_order:create", org, "fuel_order", "new");
        AuthenticatedUser u = access.currentUser();
        String number = jdbc.queryForObject(
                "SELECT 'BON-' || to_char(now() AT TIME ZONE 'Africa/Douala', 'YYYYMMDD') || '-' || "
                        + "lpad((count(*) + 1)::text, 4, '0') FROM fuel_order WHERE created_at::date = current_date",
                Map.of(), String.class);
        UUID id = jdbc.queryForObject("""
                INSERT INTO fuel_order(order_number, vehicle_id, supplier_id, org_unit_id, requested_by,
                                       authorized_liters, authorized_amount, valid_until, reason)
                VALUES (:n, :v, :s, :o, :u, :l, :a, :until, :r) RETURNING id""",
                new MapSqlParameterSource().addValue("n", number).addValue("v", c.vehicleId())
                        .addValue("s", c.supplierId()).addValue("o", org).addValue("u", u.userId())
                        .addValue("l", c.authorizedLiters()).addValue("a", c.authorizedAmountXaf())
                        .addValue("until", c.validUntil()).addValue("r", c.reason()), UUID.class);
        FuelOrderView v = order(id);
        audit.record(u.userId(), "CREATE", "fuel_order", id.toString(), c.reason(), null, v);
        return v;
    }

    @Transactional
    public FuelOrderView submit(UUID id) {
        FuelOrderView o = order(id);
        access.require("fuel_order:create", o.orgUnitId(), "fuel_order", id.toString());
        jdbc.update("UPDATE fuel_order SET status = 'SUBMITTED', version = version + 1 WHERE id = :id",
                Map.of("id", id));                      // transition contrôlée par trigger (RG-02)
        FuelOrderView after = order(id);
        audit.record(access.currentUser().userId(), "SUBMIT", "fuel_order", id.toString(), null, o, after);
        return after;
    }

    @Transactional
    public FuelOrderView approve(UUID id, String comment) {
        FuelOrderView o = order(id);
        access.require("fuel_order:approve", o.orgUnitId(), "fuel_order", id.toString());
        UUID approver = access.currentUser().userId();
        if (approver.equals(o.requestedBy())) {
            throw new BusinessRuleViolation("NF-40", "Le demandeur ne peut pas valider son propre bon.");
        }
        jdbc.update("""
                UPDATE fuel_order SET status = 'APPROVED', approved_by = :a, approved_at = now(), version = version + 1
                 WHERE id = :id""", Map.of("a", approver, "id", id));
        FuelOrderView after = order(id);
        audit.record(approver, "APPROVE", "fuel_order", id.toString(), comment, o, after);
        return after;
    }

    /**
     * Le solde du bon est consommé atomiquement par le trigger {@code nf_consume_fuel_order} (verrou de ligne).
     * L'idempotence repose sur l'index unique (entered_by, client_request_id).
     */
    @Transactional
    public IdempotentResult createFill(String idempotencyKey, FuelFillCreate c) {
        AuthenticatedUser u = access.currentUser();
        List<FuelFillView> existing = jdbc.query("SELECT * FROM fuel_fill WHERE entered_by = :u AND client_request_id = :k",
                Map.of("u", u.userId(), "k", idempotencyKey), FILL);
        if (!existing.isEmpty()) return new IdempotentResult(existing.get(0), true);

        UUID org = vehicleOrg(c.vehicleId());
        access.require("fuel_fill:create", org, "fuel_fill", "new");
        BigDecimal amount = c.liters().multiply(c.unitPriceXaf()).setScale(2, RoundingMode.HALF_UP);
        UUID id = jdbc.queryForObject("""
                INSERT INTO fuel_fill(fuel_order_id, exception_reason, vehicle_id, driver_id, supplier_id, filled_at,
                                      odometer_km, liters, unit_price, amount, full_tank, receipt_reference,
                                      entered_by, client_request_id)
                VALUES (:o, :ex, :v, :d, :s, :t, :km, :l, :p, :a, :full, :ref, :u, :k) RETURNING id""",
                new MapSqlParameterSource().addValue("o", c.fuelOrderId()).addValue("ex", c.exceptionReason())
                        .addValue("v", c.vehicleId()).addValue("d", c.driverId()).addValue("s", c.supplierId())
                        .addValue("t", Timestamp.from(c.filledAt())).addValue("km", c.odometerKm())
                        .addValue("l", c.liters()).addValue("p", c.unitPriceXaf()).addValue("a", amount)
                        .addValue("full", c.fullTank()).addValue("ref", c.receiptReference().strip())
                        .addValue("u", u.userId()).addValue("k", idempotencyKey), UUID.class);
        FuelFillView v = jdbc.queryForObject("SELECT * FROM fuel_fill WHERE id = :id", Map.of("id", id), FILL);
        audit.record(u.userId(), "CREATE", "fuel_fill", id.toString(), c.exceptionReason(), null, v);
        return new IdempotentResult(v, false);
    }

    @Transactional(readOnly = true)
    public ConsumptionResult consumption(UUID vehicleId) {
        access.require("vehicle:read", vehicleOrg(vehicleId), "vehicle", vehicleId.toString());
        List<FuelFillRecord> fills = jdbc.query("""
                SELECT id, filled_at, odometer_km, liters, full_tank FROM fuel_fill
                 WHERE vehicle_id = :v ORDER BY filled_at""", Map.of("v", vehicleId),
                (rs, i) -> new FuelFillRecord(rs.getString("id"), rs.getTimestamp("filled_at").toInstant(),
                        rs.getBigDecimal("odometer_km"), rs.getBigDecimal("liters"), rs.getBoolean("full_tank")));
        return calculator.latest(fills);
    }

    private FuelOrderView order(UUID id) {
        List<FuelOrderView> l = jdbc.query("SELECT * FROM fuel_order WHERE id = :id", Map.of("id", id), ORDER);
        if (l.isEmpty()) throw new NotFoundException("Bon introuvable.");
        return l.get(0);
    }

    private UUID vehicleOrg(UUID vehicleId) {
        List<UUID> l = jdbc.queryForList("SELECT org_unit_id FROM vehicle WHERE id = :id AND archived_at IS NULL",
                Map.of("id", vehicleId), UUID.class);
        if (l.isEmpty()) throw new NotFoundException("Véhicule introuvable ou archivé.");
        return l.get(0);
    }
}
