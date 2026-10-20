package cm.camtel.nextfleet.domain.gps;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Règle cardinale GPS (REC-GPS-01, NF-70) appliquée AVANT l'écriture : une position n'est retenue que si elle a été
 * relevée pendant la période ACTIVE d'une mission, par un émetteur autorisé et non révoqué pour cette mission.
 * Le trigger {@code nf_guard_location_point} de la base applique la même règle en défense en profondeur.
 * Un point refusé n'est jamais conservé : seul le motif du refus est journalisé (sans coordonnées).
 */
public final class MissionTrackingGate {

    public enum MissionStatus { DRAFT, AUTHORIZED, ACTIVE, FINISHED, CANCELLED }

    public enum Reason { SOURCE_INCONNUE, AUCUNE_MISSION_ACTIVE, HORS_PERIODE, AUTORISATION_REVOQUEE, DONNEE_INVALIDE }

    public record Mission(UUID id, String reference, UUID vehicleId, MissionStatus status, Instant startedAt,
                          Instant finishedAt) { }

    /** Autorisation de collecte d'un émetteur pour une mission (table {@code tracking_authorization}). */
    public record Authorization(UUID missionId, String sourceDeviceId, Instant grantedAt, Instant revokedAt) { }

    public record Decision(boolean accepted, Mission mission, Reason reason) {
        static Decision accept(Mission m) {
            return new Decision(true, m, null);
        }

        static Decision reject(Reason r) {
            return new Decision(false, null, r);
        }
    }

    /**
     * @param deviceId       identifiant unique de l'émetteur (uniqueId Traccar)
     * @param recordedAt     instant de relevé (fixTime), pas l'instant de réception
     * @param authorizations autorisations connues pour cet émetteur
     * @param missions       missions référencées par ces autorisations
     */
    public Decision evaluate(String deviceId, Instant recordedAt, Collection<Authorization> authorizations,
                             java.util.function.Function<UUID, Optional<Mission>> missions) {
        if (deviceId == null || deviceId.isBlank() || recordedAt == null) return Decision.reject(Reason.DONNEE_INVALIDE);
        boolean known = false;
        boolean revokedOnly = false;
        boolean outOfPeriod = false;
        for (Authorization a : authorizations) {
            if (!Objects.equals(a.sourceDeviceId(), deviceId)) continue;
            known = true;
            Optional<Mission> om = missions.apply(a.missionId());
            if (om.isEmpty()) continue;
            Mission m = om.get();
            if (m.status() != MissionStatus.ACTIVE && m.status() != MissionStatus.FINISHED) continue;
            boolean inMission = m.startedAt() != null && !recordedAt.isBefore(m.startedAt())
                    && (m.finishedAt() == null || !recordedAt.isAfter(m.finishedAt()));
            boolean authorized = !recordedAt.isBefore(a.grantedAt())
                    && (a.revokedAt() == null || recordedAt.isBefore(a.revokedAt()));
            if (inMission && authorized) return Decision.accept(m);
            if (inMission) revokedOnly = true;
            else outOfPeriod = true;
        }
        if (!known) return Decision.reject(Reason.SOURCE_INCONNUE);
        if (revokedOnly) return Decision.reject(Reason.AUTORISATION_REVOQUEE);
        if (outOfPeriod) return Decision.reject(Reason.HORS_PERIODE);
        return Decision.reject(Reason.AUCUNE_MISSION_ACTIVE);
    }
}
