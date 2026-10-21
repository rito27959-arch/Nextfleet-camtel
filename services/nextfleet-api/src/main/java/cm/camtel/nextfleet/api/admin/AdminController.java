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

    public record UserCreate(@NotBlank @Size(max = 64) String login, @NotBlank @Size(max = 120) String displayName,
                             @jakarta.validation.constraints.Email @Size(max = 160) String email, @Size(max = 30) String phone,
                             @jakarta.validation.constraints.NotNull UUID orgUnitId,
                             @Size(max = 10) List<AccountService.GrantRequest> roles, UUID driverId) { }

    public record ReasonRequest(@NotBlank @Size(min = 5, max = 500) String reason) { }

    public record TemporaryPassword(String temporaryPassword) { }

    private final AdminService service;
    private final AccountService accounts;

    public AdminController(AdminService service, AccountService accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    @GetMapping("/roles")
    public List<AccountService.RoleView> roles() {
        return accounts.roles();
    }

    /** Le mot de passe provisoire n'est renvoyé qu'ici, une seule fois ; il n'est jamais stocké en clair. */
    @PostMapping("/users")
    public org.springframework.http.ResponseEntity<AccountService.Created> create(@Valid @RequestBody UserCreate c) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .body(accounts.create(c.login(), c.displayName(), c.email(), c.phone(), c.orgUnitId(), c.roles(), c.driverId()));
    }

    @GetMapping("/users/{id}/grants")
    public List<AccountService.GrantView> grants(@PathVariable UUID id) {
        return accounts.grants(id);
    }

    @PostMapping("/users/{id}/grants")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public AccountService.GrantView grant(@PathVariable UUID id, @RequestBody AccountService.GrantRequest g) {
        return accounts.grant(id, g, "Attribution par l'administration");
    }

    @PostMapping("/users/{id}/grants/{grantId}/revoke")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, @PathVariable UUID grantId, @Valid @RequestBody ReasonRequest r) {
        accounts.revoke(id, grantId, r.reason());
    }

    @PostMapping("/users/{id}/password-reset")
    public org.springframework.http.ResponseEntity<TemporaryPassword> reset(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return org.springframework.http.ResponseEntity.ok().header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TemporaryPassword(accounts.resetPassword(id, r.reason())));
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
