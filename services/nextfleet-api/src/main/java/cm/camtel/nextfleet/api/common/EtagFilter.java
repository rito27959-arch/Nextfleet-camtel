package cm.camtel.nextfleet.api.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import java.io.InputStream;

/**
 * ETag sur les lectures JSON de l'API : un client qui renvoie l'ETag reçu (If-None-Match) obtient « 304 inchangé »
 * sans corps quand les données n'ont pas bougé. La requête est quand même entièrement exécutée (droits et périmètre
 * revérifiés à chaque fois) ; seul le transfert est économisé.
 *
 * Les réponses gardent « Cache-Control: no-store » (Spring Security) : aucun cache intermédiaire ne les conserve ;
 * les clients NexFleet gardent la dernière réponse en mémoire seulement. Exclus : WebSocket, fichiers, exports CSV.
 */
@Configuration
public class EtagFilter {

    static final class ApiEtagFilter extends ShallowEtagHeaderFilter {
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String uri = request.getRequestURI();
            return !"GET".equals(request.getMethod())
                    || uri.startsWith("/api/v1/live")
                    || uri.endsWith("/content")
                    || uri.endsWith(".csv");
        }

        @Override
        protected boolean isEligibleForEtag(HttpServletRequest request, HttpServletResponse response,
                                            int responseStatusCode, InputStream inputStream) {
            return responseStatusCode == 200 && "GET".equals(request.getMethod())
                    && response.getContentType() != null && response.getContentType().contains("json");
        }
    }

    @Bean
    FilterRegistrationBean<ApiEtagFilter> apiEtagFilter() {
        ApiEtagFilter f = new ApiEtagFilter();
        // ETag faible (W/"…") : Tomcat refuse de compresser une réponse portant un ETag fort
        f.setWriteWeakETag(true);
        FilterRegistrationBean<ApiEtagFilter> reg = new FilterRegistrationBean<>(f);
        reg.addUrlPatterns("/api/v1/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return reg;
    }
}
