package cm.camtel.nextfleet.api.live;

import cm.camtel.nextfleet.api.identity.AuthenticatedUser;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** Canal /api/v1/live : la poignée de main passe par la chaîne de sécurité (jeton obligatoire). */
@Configuration
@EnableWebSocket
@EnableScheduling
public class LiveConfig implements WebSocketConfigurer {

    private final LiveSocketHandler handler;

    public LiveConfig(LiveSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/live").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Map<String, Object> attrs) {
                Authentication a = SecurityContextHolder.getContext().getAuthentication();
                if (a == null || !(a.getPrincipal() instanceof AuthenticatedUser u)) return false;
                String header = req.getHeaders().getFirst("Authorization");
                attrs.put(LiveSocketHandler.USER, u);
                attrs.put(LiveSocketHandler.TOKEN, header == null ? null : header.substring(7).trim());
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception e) { }
        }).setAllowedOrigins("*");   // clients natifs ; l'authentification repose sur le jeton, pas sur l'origine
    }
}
