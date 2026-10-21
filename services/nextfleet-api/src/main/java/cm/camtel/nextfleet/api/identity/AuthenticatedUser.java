package cm.camtel.nextfleet.api.identity;

import cm.camtel.nextfleet.domain.security.AccessPolicy.Grant;

import java.util.List;
import java.util.UUID;

/** Principal porté par la requête après validation du jeton de session. */
public record AuthenticatedUser(UUID userId, UUID sessionId, String login, String displayName,
                                UUID orgUnitId, List<Grant> grants, boolean mustChangePassword) { }
