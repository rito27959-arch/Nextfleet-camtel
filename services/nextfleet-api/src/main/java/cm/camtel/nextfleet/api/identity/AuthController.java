package cm.camtel.nextfleet.api.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 120) String login,
                               @NotBlank @Size(max = 256) String password,
                               @NotBlank @Pattern(regexp = "DESKTOP|ANDROID|API") String clientKind) { }

    public record GrantView(String role, String scopeOrgId, Instant validUntil) { }

    public record MeView(UUID id, String login, String displayName, UUID orgUnitId, List<GrantView> roles) {
        static MeView of(AuthenticatedUser u) {
            return new MeView(u.userId(), u.login(), u.displayName(), u.orgUnitId(),
                    u.grants().stream().filter(g -> g.revokedAt() == null)
                            .map(g -> new GrantView(g.roleCode(), g.scopeOrgId(), g.validUntil())).toList());
        }
    }

    public record LoginResponse(String token, Instant expiresAt, MeView user) { }

    private final SessionService sessions;
    private final AccessService access;

    public AuthController(SessionService sessions, AccessService access) {
        this.sessions = sessions;
        this.access = access;
    }

    /** NF-01 : message générique en cas d'échec (ni « compte inconnu » ni « mot de passe faux »). */
    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        return sessions.login(req.login(), req.password(), req.clientKind())
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(new LoginResponse(r.token(), r.expiresAt(), MeView.of(r.user()))))
                .orElseGet(() -> {
                    ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                            "Identifiants invalides ou compte indisponible.");
                    p.setProperty("code", "LOGIN_FAILED");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(p);
                });
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout() {
        sessions.logout(access.currentUser());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeView me() {
        return MeView.of(access.currentUser());
    }
}
