package cm.camtel.nextfleet.api.common;

import cm.camtel.nextfleet.domain.fuel.BusinessRuleViolation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Traduction homogène des erreurs en RFC 9457 (ProblemDetail), sans pile ni détail SQL exposé. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public static class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public NotFoundException(String m) { super(m); }
    }

    /** RG-03 : la version transmise est obsolète. */
    public static class StaleVersionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public StaleVersionException(String m) { super(m); }
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) {
        // 404 serait possible pour masquer l'existence ; on reste explicite mais sans détail sur l'objet.
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Vous n'avez pas les droits nécessaires pour cette action.");
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(StaleVersionException.class)
    ProblemDetail stale(StaleVersionException e) {
        return problem(HttpStatus.CONFLICT, "STALE_VERSION",
                "L'objet a été modifié par un autre utilisateur. Rechargez-le avant de recommencer.");
    }

    @ExceptionHandler(BusinessRuleViolation.class)
    ProblemDetail rule(BusinessRuleViolation e) {
        ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE", e.getMessage());
        p.setProperty("rule", e.ruleCode());
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage());
    }

    /** Transition d'état impossible (mission déjà démarrée, non autorisée…) ou service non configuré. */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
    }

    /** Refus d'un trigger de sécurité (SQLSTATE 42501 insufficient_privilege) : 403 sans détail SQL. */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ProblemDetail dataAccess(org.springframework.dao.DataAccessException e) {
        Throwable c = e.getMostSpecificCause();
        if (c instanceof java.sql.SQLException s && "42501".equals(s.getSQLState())) {
            return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Vous n'avez pas les droits nécessaires pour cette action.");
        }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "DATA_ACCESS", "Erreur d'accès aux données.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "VALIDATION", "Certains champs sont invalides.");
        p.setProperty("fields", fields);
        return p;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        String detail = msg.contains("ux_vehicle_registration") ? "Cette immatriculation existe déjà (y compris archivée)."
                : msg.contains("ux_vehicle_vin") ? "Ce numéro de châssis existe déjà."
                : msg.contains("ux_fuel_fill_receipt") ? "Ce reçu a déjà été saisi pour ce fournisseur (CAR-02)."
                : "Cet enregistrement existe déjà.";
        return problem(HttpStatus.CONFLICT, "DUPLICATE", detail);
    }

    /** Les triggers de la base (CAR-01, GPS hors mission, transitions) remontent un message métier en français. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        String firstLine = msg.lines().findFirst().orElse("").replaceFirst("^ERROR:\\s*", "");
        boolean businessMessage = firstLine.matches("^(CAR-|GPS|NF-|Transition|Chat|3D).*");
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INTEGRITY",
                businessMessage ? firstLine : "La demande viole une règle d'intégrité des données.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("code", code);
        return p;
    }
}
