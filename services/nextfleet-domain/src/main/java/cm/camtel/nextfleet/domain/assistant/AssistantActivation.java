package cm.camtel.nextfleet.domain.assistant;

import cm.camtel.nextfleet.domain.security.Permission;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Activation contrôlée de l'assistant (prompt maître : « activable après validation de sécurité »).
 * Mêmes règles que la table {@code assistant_activation} :
 * désactivé par défaut ; activer exige une référence de validation (DS / RSSI) et un signataire distinct de
 * l'administrateur qui applique la décision ; seule l'administration technique ({@code admin:settings}) décide ;
 * chaque décision est conservée.
 */
public final class AssistantActivation {

    public record State(boolean enabled, String securityReviewRef, String approvedBy, String decidedBy, Instant decidedAt,
                        String reason) {
        public static State disabledByDefault() {
            return new State(false, null, null, null, null, "Aucune décision d'activation enregistrée.");
        }
    }

    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    private AssistantActivation() { }

    public static State decide(boolean enable, String securityReviewRef, String approvedBy, String reason,
                               String deciderName, Set<Permission> deciderPermissions, Instant now) {
        if (!deciderPermissions.contains(Permission.ADMIN_SETTINGS)) {
            throw new Refused("Seule l'administration technique applique une décision d'activation.");
        }
        if (reason == null || reason.strip().length() < 5) throw new Refused("Indiquez le motif de la décision.");
        if (enable) {
            if (securityReviewRef == null || securityReviewRef.strip().length() < 3) {
                throw new Refused("Référence de la validation de sécurité obligatoire (ex. DS-2026-041).");
            }
            if (approvedBy == null || approvedBy.strip().length() < 3) {
                throw new Refused("Indiquez le signataire de la validation (DS ou RSSI).");
            }
            if (approvedBy.strip().toLowerCase(Locale.ROOT).equals(deciderName.strip().toLowerCase(Locale.ROOT))) {
                throw new Refused("Le signataire de la validation doit être distinct de l'administrateur.");
            }
        }
        return new State(enable, enable ? securityReviewRef.strip() : null, enable ? approvedBy.strip() : null,
                deciderName, now, reason.strip());
    }
}
