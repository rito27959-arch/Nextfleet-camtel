package cm.camtel.nextfleet.api.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Journal probant (NF-53, RG-01) : auteur, action, objet, motif, anciennes et nouvelles valeurs, identifiant de requête.
 * Jamais de mot de passe ni de jeton. Le chaînage d'empreintes et l'interdiction de modification sont assurés par la base.
 */
@Service
public class AuditService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(UUID actorId, String action, String objectType, String objectId, String reason,
                       Object oldValue, Object newValue) {
        jdbc.update("""
                INSERT INTO audit_event(actor_id, action, object_type, object_id, reason, old_value, new_value, request_id)
                VALUES (:actor, :action, :type, :id, :reason, CAST(:old AS jsonb), CAST(:new AS jsonb), :rid)""",
                new MapSqlParameterSource()
                        .addValue("actor", actorId).addValue("action", action).addValue("type", objectType)
                        .addValue("id", objectId).addValue("reason", reason)
                        .addValue("old", json(oldValue)).addValue("new", json(newValue))
                        .addValue("rid", MDC.get("requestId")));
    }

    /**
     * Refus d'accès journalisé dans une transaction AUTONOME : la trace survit à l'annulation de la transaction
     * appelante et fonctionne aussi depuis une transaction en lecture seule (REC-SEC-01).
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordDenied(UUID actorId, String objectType, String objectId, String permission) {
        record(actorId, "ACCESS_DENIED", objectType, objectId, permission, null, null);
    }

    private String json(Object v) {
        if (v == null) return null;
        try {
            return mapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Valeur d'audit non sérialisable", e);
        }
    }
}
