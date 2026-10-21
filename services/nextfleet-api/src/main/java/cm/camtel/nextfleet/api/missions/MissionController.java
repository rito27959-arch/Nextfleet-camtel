package cm.camtel.nextfleet.api.missions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contrat : shared/api-contract/openapi-lot2.yaml (missions et GPS). */
@RestController
@RequestMapping("/api/v1/missions")
public class MissionController {

    public record CreateRequest(@NotNull UUID vehicleId, @NotNull UUID driverId, @NotBlank @Size(max = 300) String purpose,
                                @Size(max = 120) String origin, @Size(max = 120) String destination,
                                @NotNull Instant plannedStart, @NotNull Instant plannedEnd) { }

    public record StartRequest(@NotBlank @Size(max = 40) String noticeVersion,
                               @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,64}$") String deviceId) { }

    private final MissionService service;

    public MissionController(MissionService service) {
        this.service = service;
    }

    @GetMapping
    public List<MissionService.MissionView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MissionService.MissionView create(@Valid @RequestBody CreateRequest r) {
        return service.create(new MissionService.MissionCreate(r.vehicleId(), r.driverId(), r.purpose(), r.origin(), r.destination(),
                r.plannedStart(), r.plannedEnd()));
    }

    @PostMapping("/{id}/authorize")
    public MissionService.MissionView authorize(@PathVariable UUID id) {
        return service.authorize(id);
    }

    @PostMapping("/{id}/start")
    public MissionService.StartResult start(@PathVariable UUID id, @Valid @RequestBody StartRequest r) {
        return service.start(id, r.noticeVersion(), r.deviceId());
    }

    public record CancelRequest(@NotBlank @Size(min = 5, max = 500) String reason) { }

    @PostMapping("/{id}/cancel")
    public MissionService.MissionView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        return service.cancel(id, r.reason());
    }

    @PostMapping("/{id}/finish")
    public MissionService.MissionView finish(@PathVariable UUID id) {
        return service.finish(id);
    }

    @GetMapping("/{id}/track")
    public List<MissionService.TrackPoint> track(@PathVariable UUID id) {
        return service.track(id);
    }
}
