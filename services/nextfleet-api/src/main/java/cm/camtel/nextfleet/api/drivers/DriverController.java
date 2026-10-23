package cm.camtel.nextfleet.api.drivers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class DriverController {

    public record DriverCreate(@NotBlank @Size(max = 30) String staffNumber, @NotBlank @Size(max = 120) String fullName,
                               @Size(max = 30) String phone, @Email @Size(max = 160) String email, @NotNull UUID orgUnitId,
                               @Size(max = 40) String idCardNumber, LocalDate idCardExpiresOn) { }

    public record DriverPatch(@Size(max = 30) String staffNumber, @Size(max = 120) String fullName, @Size(max = 30) String phone,
                              @Email @Size(max = 160) String email, UUID orgUnitId, @Size(max = 40) String idCardNumber,
                              LocalDate idCardExpiresOn, @NotBlank @Size(min = 5, max = 500) String reason) { }

    public record LicenseCreate(@NotBlank @Size(max = 40) String licenseNumber, @NotNull @Size(min = 1, max = 11) List<String> categories,
                                LocalDate issuedOn, @NotNull LocalDate expiresOn, UUID fileId) { }

    public record AssignRequest(UUID driverId, @NotBlank @Size(min = 5, max = 500) String reason) { }

    public record ReasonRequest(@NotBlank @Size(min = 5, max = 500) String reason) { }

    public record FromAccountRequest(@NotNull UUID userId, UUID driverId) { }

    private final DriverService service;

    public DriverController(DriverService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/drivers")
    public List<DriverService.DriverView> list(@RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(q, includeArchived);
    }

    @GetMapping("/api/v1/drivers/{id}")
    public ResponseEntity<DriverService.DriverView> get(@PathVariable UUID id) {
        DriverService.DriverView d = service.get(id);
        return ResponseEntity.ok().eTag("\"" + d.version() + "\"").body(d);
    }

    @GetMapping("/api/v1/drivers/accounts")
    public List<DriverService.DriverAccount> accounts() {
        return service.accounts();
    }

    @PostMapping("/api/v1/drivers/from-account")
    public DriverService.DriverView fromAccount(@Valid @RequestBody FromAccountRequest request) {
        return service.fromAccount(request.userId(), request.driverId());
    }

    @PostMapping("/api/v1/drivers")
    @ResponseStatus(HttpStatus.CREATED)
    public DriverService.DriverView create(@Valid @RequestBody DriverCreate c) {
        return service.create(new DriverService.DriverInput(c.staffNumber(), c.fullName(), c.phone(), c.email(), c.orgUnitId(),
                c.idCardNumber(), c.idCardExpiresOn()));
    }

    @PatchMapping("/api/v1/drivers/{id}")
    public DriverService.DriverView patch(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody DriverPatch p) {
        return service.update(id, version(ifMatch), new DriverService.DriverInput(p.staffNumber(), p.fullName(), p.phone(), p.email(),
                p.orgUnitId(), p.idCardNumber(), p.idCardExpiresOn()), p.reason());
    }

    @PostMapping("/api/v1/drivers/{id}/licenses")
    public DriverService.DriverView license(@PathVariable UUID id, @Valid @RequestBody LicenseCreate l) {
        return service.addLicense(id, new DriverService.LicenseInput(l.licenseNumber(), l.categories(), l.issuedOn(), l.expiresOn(), l.fileId()));
    }

    @PostMapping("/api/v1/drivers/{id}/archive")
    public DriverService.DriverView archive(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody ReasonRequest r) {
        return service.archive(id, version(ifMatch), r.reason());
    }

    /** {@code driverId} absent = véhicule sans conducteur attitré à partir de maintenant. */
    @PostMapping("/api/v1/vehicles/{id}/assignment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest r) {
        service.assign(id, r.driverId(), r.reason());
    }

    static long version(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("En-tête If-Match invalide");
        }
    }
}
