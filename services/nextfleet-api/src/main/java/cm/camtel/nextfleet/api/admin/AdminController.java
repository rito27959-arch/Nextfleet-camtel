package cm.camtel.nextfleet.api.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    public record StatusRequest(@NotBlank String status, @NotBlank @Size(min = 5, max = 500) String reason, long version) { }

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public List<AdminService.UserView> users(@RequestParam(required = false) String q) {
        return service.users(q);
    }

    @PostMapping("/users/{id}/status")
    public AdminService.UserView status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return service.setStatus(id, r.status(), r.reason(), r.version());
    }

    @GetMapping("/audit/verify")
    public AdminService.ChainCheck verify() {
        return service.verifyChain();
    }

    @GetMapping("/audit")
    public List<AdminService.AuditView> audit(@RequestParam(required = false) String objectType,
                                              @RequestParam(required = false) String objectId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                              @RequestParam(defaultValue = "200") int limit) {
        return service.audit(objectType, objectId, from, to, limit);
    }
}
