package cm.camtel.nextfleet.api.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Lit « Authorization: Bearer &lt;jeton&gt; » et place l'utilisateur authentifié dans le contexte. */
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final java.util.Set<String> OPEN_WHEN_PASSWORD_CHANGE =
            java.util.Set.of("/api/v1/me", "/api/v1/me/password", "/api/v1/auth/logout");

    private final SessionService sessions;

    public TokenAuthenticationFilter(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            AuthenticatedUser user = sessions.resolve(header.substring(7).trim()).orElse(null);
            if (user != null) {
                if (user.mustChangePassword() && !OPEN_WHEN_PASSWORD_CHANGE.contains(req.getRequestURI())) {
                    // Mot de passe provisoire : aucune donnée métier tant qu'il n'a pas été remplacé.
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType("application/problem+json");
                    res.setCharacterEncoding("UTF-8");
                    res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Changement de mot de passe requis\","
                            + "\"status\":403,\"code\":\"PASSWORD_CHANGE_REQUIRED\","
                            + "\"detail\":\"Choisissez un nouveau mot de passe pour continuer.\"}");
                    return;
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
