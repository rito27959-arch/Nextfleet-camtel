package cm.camtel.nextfleet.domain.security;

import cm.camtel.nextfleet.domain.fleet.RegistrationNumber;
import cm.camtel.nextfleet.domain.security.AccessPolicy.Grant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private final AccessPolicy policy;

    AccessPolicyTest() {
        Map<String, String> tree = new HashMap<>();
        tree.put("CAMTEL", null);
        tree.put("BUT", "CAMTEL");
        tree.put("BUT-YDE", "BUT");
        tree.put("BUT-DLA", "BUT");
        policy = new AccessPolicy(Map.of(
                "FLEET_MANAGER", Set.of("vehicle:read", "gps:read", "chat:use"),
                "ADMIN", Set.of("admin:users")), tree);
    }

    @Test
    @DisplayName("REC-SEC-01 : un gestionnaire de Yaoundé ne lit pas le GPS de Douala")
    void crossEntityDenied() {
        var g = List.of(new Grant("FLEET_MANAGER", "BUT-YDE", NOW.minusSeconds(3600), null, null));
        assertTrue(policy.isAllowed(g, "gps:read", "BUT-YDE", NOW));
        assertFalse(policy.isAllowed(g, "gps:read", "BUT-DLA", NOW));
    }

    @Test
    @DisplayName("Un rôle sur l'entité parente couvre les sous-entités")
    void hierarchy() {
        var g = List.of(new Grant("FLEET_MANAGER", "BUT", NOW.minusSeconds(3600), null, null));
        assertTrue(policy.isAllowed(g, "vehicle:read", "BUT-DLA", NOW));
        assertFalse(policy.isAllowed(g, "vehicle:read", "CAMTEL", NOW));
    }

    @Test
    @DisplayName("L'administrateur n'a aucun pouvoir métier implicite ; refus par défaut")
    void adminHasNoBusinessPower() {
        var g = List.of(new Grant("ADMIN", "CAMTEL", NOW.minusSeconds(3600), null, null));
        assertFalse(policy.isAllowed(g, "gps:read", "BUT-YDE", NOW));
        assertFalse(policy.isAllowed(List.of(), "vehicle:read", "BUT-YDE", NOW));
        assertFalse(policy.isAllowed(g, "vehicle:read", null, NOW));
    }

    @Test
    @DisplayName("Délégation expirée ou rôle révoqué : accès refusé")
    void expiredAndRevoked() {
        var expired = List.of(new Grant("FLEET_MANAGER", "BUT-YDE", NOW.minusSeconds(7200), NOW.minusSeconds(1), null));
        var revoked = List.of(new Grant("FLEET_MANAGER", "BUT-YDE", NOW.minusSeconds(7200), null, NOW.minusSeconds(60)));
        assertFalse(policy.isAllowed(expired, "vehicle:read", "BUT-YDE", NOW));
        assertFalse(policy.isAllowed(revoked, "vehicle:read", "BUT-YDE", NOW));
    }

    @Test
    @DisplayName("NF-10 : immatriculations « ce-4401 lt » et « CE 4401 LT » identiques")
    void registration() {
        assertTrue(RegistrationNumber.of("ce-4401 lt").sameVehicleAs(RegistrationNumber.of("CE 4401 LT")));
        assertEquals("CE4401LT", RegistrationNumber.of(" CE 4401 LT ").normalized());
        assertThrows(IllegalArgumentException.class, () -> RegistrationNumber.of(" - "));
    }
}
