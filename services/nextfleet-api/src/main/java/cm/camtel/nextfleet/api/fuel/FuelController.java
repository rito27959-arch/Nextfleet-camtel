package cm.camtel.nextfleet.api.fuel;

import cm.camtel.nextfleet.domain.fuel.ConsumptionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** NF-40 à NF-42 : bons, validation indépendante, pleins avec solde atomique, consommation plein-à-plein. */
@RestController
@RequestMapping("/api/v1")
public class FuelController {

    public record FuelOrderCreate(@NotNull UUID vehicleId, @NotNull UUID supplierId,
                                  @NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal authorizedLiters,
                                  @NotNull @DecimalMin("1") @Digits(integer = 14, fraction = 2) BigDecimal authorizedAmountXaf,
                                  @NotNull @FutureOrPresent LocalDate validUntil,
                                  @Size(max = 500) String reason) { }

    public record FuelFillCreate(UUID fuelOrderId, @Size(max = 500) String exceptionReason,
                                 @NotNull UUID vehicleId, UUID driverId, @NotNull UUID supplierId,
                                 @NotNull @PastOrPresent Instant filledAt,
                                 @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal odometerKm,
                                 @NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal liters,
                                 @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal unitPriceXaf,
                                 @NotNull Boolean fullTank,
                                 @NotBlank @Size(max = 60) String receiptReference,
                                 UUID receiptFileId) { }

    public record ApprovalRequest(@Size(max = 500) String comment) { }

    private final FuelService service;

    public FuelController(FuelService service) {
        this.service = service;
    }

    @PostMapping("/fuel-orders")
    public ResponseEntity<FuelService.FuelOrderView> createOrder(@Valid @RequestBody FuelOrderCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOrder(body));
    }

    @PostMapping("/fuel-orders/{id}/submit")
    public FuelService.FuelOrderView submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    /** Le valideur doit être distinct du demandeur ; refus 422 avec règle NF-40 sinon. */
    @PostMapping("/fuel-orders/{id}/approve")
    public FuelService.FuelOrderView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApprovalRequest body) {
        return service.approve(id, body == null ? null : body.comment());
    }

    /** Idempotency-Key obligatoire : un renvoi après coupure réseau ne crée pas un second plein. */
    @PostMapping("/fuel-fills")
    public ResponseEntity<FuelService.FuelFillView> createFill(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 80) String key,
                                                               @Valid @RequestBody FuelFillCreate body) {
        FuelService.IdempotentResult r = service.createFill(key, body);
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(r.fill());
    }

    @GetMapping("/vehicles/{id}/consumption")
    public ConsumptionResult consumption(@PathVariable UUID id) {
        return service.consumption(id);
    }
}
