package cm.camtel.nextfleet.api.vehicles;

import cm.camtel.nextfleet.api.vehicles.VehicleService.VehicleView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** NF-10 : parc automobile — recherche, filtres, pagination, détail, création, correction, archivage. */
@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    public record VehicleCreate(@NotBlank @Size(max = 20) String registration,
                                @Size(max = 32) String vin,
                                @NotBlank @Size(max = 60) String make,
                                @NotBlank @Size(max = 60) String model,
                                @Min(1950) @Max(2100) Integer modelYear,
                                @Size(max = 40) String vehicleType,
                                @Pattern(regexp = "DIESEL|ESSENCE|HYBRIDE|ELECTRIQUE|GPL|AUTRE") String energy,
                                @DecimalMin("1") BigDecimal tankCapacityLiters,
                                boolean tankCapacityVerified,
                                @NotNull UUID orgUnitId) { }

    /** Correction partielle ; {@code reason} obligatoire pour l'historique (RG-01). */
    public record VehiclePatch(@Size(max = 60) String make, @Size(max = 60) String model,
                               @Min(1950) @Max(2100) Integer modelYear,
                               @Pattern(regexp = "AVAILABLE|IN_SERVICE|ON_MISSION|MAINTENANCE|OUT_OF_SERVICE") String status,
                               @DecimalMin("1") BigDecimal tankCapacityLiters, Boolean tankCapacityVerified,
                               @NotBlank @Size(min = 5, max = 500) String reason) { }

    public record Page<T>(List<T> items, int page, int size, long total) { }

    private final VehicleService service;

    public VehicleController(VehicleService service) {
        this.service = service;
    }

    @GetMapping
    public Page<VehicleView> list(@RequestParam(required = false) @Size(max = 60) String q,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size,
                                  @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.search(q, status, includeArchived, page, size);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VehicleView> get(@PathVariable UUID id) {
        VehicleView v = service.get(id);
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").body(v);
    }

    @PostMapping
    public ResponseEntity<VehicleView> create(@Valid @RequestBody VehicleCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    /** RG-03 : If-Match obligatoire ; une version obsolète renvoie 409 avec invitation à recharger. */
    @PatchMapping("/{id}")
    public VehicleView patch(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                             @Valid @RequestBody VehiclePatch body) {
        return service.patch(id, parseVersion(ifMatch), body);
    }

    @PostMapping("/{id}/archive")
    public VehicleView archive(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                               @RequestParam @Size(min = 5, max = 500) String reason) {
        return service.archive(id, parseVersion(ifMatch), reason);
    }

    private static long parseVersion(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("En-tête If-Match invalide");
        }
    }
}
