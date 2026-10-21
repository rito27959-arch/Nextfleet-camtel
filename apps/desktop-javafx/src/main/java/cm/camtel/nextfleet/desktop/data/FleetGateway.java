package cm.camtel.nextfleet.desktop.data;

import cm.camtel.nextfleet.desktop.data.Models.DashboardData;
import cm.camtel.nextfleet.desktop.data.Models.Page;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Models.VehicleDraft;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;

import java.util.UUID;

/**
 * Accès aux données du client bureau. Le client ne lit jamais la base : il passe par l'API (règle cardinale 3).
 * Deux implémentations : {@link HttpFleetGateway} (API réelle) et {@link DemoFleetGateway} (données fictives, badge DÉMO).
 */
public interface FleetGateway {

    /** Erreur présentable à l'utilisateur, avec nature (réseau, droits, session, validation, conflit). */
    final class GatewayException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public enum Kind { NETWORK, UNAUTHORIZED, FORBIDDEN, VALIDATION, CONFLICT, NOT_FOUND, SERVER }

        private final Kind kind;

        public GatewayException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    boolean isDemo();

    Session login(String login, char[] password);

    void logout();

    DashboardData dashboard();

    Page<Vehicle> vehicles(String query, VehicleStatus status, int page, int size);

    Vehicle vehicle(UUID id);

    Vehicle createVehicle(VehicleDraft draft);

    java.util.List<Models.Driver> drivers(String query);

    /** Échéances du périmètre, triées par date ; {@code thresholdDays} = seuil d'alerte approuvé. */
    java.util.List<Models.DueItem> dueDates(int thresholdDays);

    java.util.List<Models.NotificationItem> notifications();

    void markNotificationsRead(java.util.Collection<String> ids);

    /** Archivage métier d'un véhicule (RG-04) avec motif ; l'historique reste consultable. */
    Vehicle archiveVehicle(UUID id, long expectedVersion, String reason);

    /** Échéances d'un véhicule donné. */
    java.util.List<Models.DueItem> vehicleDueDates(UUID vehicleId);

    // ------------------------------------------------------------------ lot 2 : missions et GPS
    // Mode serveur : HttpFleetGateway appelle l'API du lot 2 ; les valeurs par défaut ci-dessous ne servent qu'aux
    // passerelles qui n'implémentent pas encore une fonction (le client le dit au lieu de simuler).

    default java.util.List<Ops.Mission> missions() {
        throw notYet("Les missions");
    }

    /** Positions ACCEPTÉES de la mission (jamais hors période de mission). */
    default java.util.List<cm.camtel.nextfleet.domain.gps.GpsFix> missionTrack(UUID missionId) {
        throw notYet("Les trajets");
    }

    /** Démarrage par le conducteur affecté, après information explicite (notice GPS versionnée). */
    default Ops.Mission startMission(UUID missionId, String noticeVersion) {
        throw notYet("Le démarrage de mission");
    }

    default Ops.Mission finishMission(UUID missionId) {
        throw notYet("La clôture de mission");
    }

    // ------------------------------------------------------------------ suivi GPS en direct

    default java.util.List<Ops.VehicleLive> fleetLive() {
        throw notYet("Le suivi GPS en direct");
    }

    /**
     * Abonnement aux positions en direct. Par défaut : relecture de la carte de flotte toutes les 5 s (mode
     * démonstration et serveurs sans canal temps réel) ; la passerelle HTTP utilise le WebSocket du serveur.
     * {@code onConnection} reçoit l'état du canal (vrai = connecté). Fermer l'objet rendu arrête l'abonnement.
     */
    default AutoCloseable subscribeLive(java.util.function.Consumer<Ops.LiveEvent> onEvent,
                                        java.util.function.Consumer<Boolean> onConnection) {
        java.util.concurrent.ScheduledExecutorService ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nf-live-poll");
            t.setDaemon(true);
            return t;
        });
        java.util.Map<String, java.time.Instant> last = new java.util.concurrent.ConcurrentHashMap<>();
        ex.scheduleWithFixedDelay(() -> {
            try {
                for (Ops.VehicleLive v : fleetLive()) {
                    java.time.Instant prev = last.put(v.vehicleId(), v.recordedAt());
                    if (prev == null || v.recordedAt().isAfter(prev)) {
                        onEvent.accept(new Ops.LiveEvent(v.vehicleId(), v.registration(), v.missionRef(), v.lat(), v.lon(),
                                v.speedKmh(), v.recordedAt()));
                    }
                }
                onConnection.accept(true);
            } catch (RuntimeException e) {
                onConnection.accept(false);
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        return ex::shutdownNow;
    }

    // ------------------------------------------------------------------ lot 2 : messagerie

    default java.util.List<Ops.Conversation> conversations() {
        throw notYet("La messagerie");
    }

    default java.util.List<Ops.ChatLine> messages(String conversationId) {
        throw notYet("La messagerie");
    }

    /** Envoi idempotent : même {@code clientMessageId} = même message, jamais de doublon. */
    default Ops.ChatLine sendMessage(String conversationId, String clientMessageId, String body) {
        throw notYet("La messagerie");
    }

    default void markConversationRead(String conversationId) {
        throw notYet("La messagerie");
    }

    // ------------------------------------------------------------------ lot 2 : assistant (lecture seule)

    default cm.camtel.nextfleet.domain.assistant.AssistantActivation.State assistantState() {
        throw notYet("L'assistant");
    }

    default cm.camtel.nextfleet.domain.assistant.FleetAssistant.Answer askAssistant(String question) {
        throw notYet("L'assistant");
    }

    default cm.camtel.nextfleet.domain.assistant.AssistantActivation.State decideAssistant(boolean enable, String reviewRef,
                                                                                          String approvedBy, String reason) {
        throw notYet("L'activation de l'assistant");
    }

    default java.util.List<cm.camtel.nextfleet.domain.assistant.AssistantActivation.State> assistantDecisions() {
        throw notYet("L'historique des décisions");
    }

    // ------------------------------------------------------------------ lot 2 : administration et audit

    default java.util.List<Ops.UserAccount> users() {
        throw notYet("La gestion des comptes");
    }

    default java.util.List<Ops.AuditEntry> auditTrail() {
        throw notYet("Le journal d'audit");
    }

    /** Premier événement dont l'empreinte ne correspond pas (-1 si la chaîne est intègre). */
    default long auditFirstBroken(java.util.List<Ops.AuditEntry> shown) {
        return AuditChain.firstBroken(shown);
    }

    /** Nouveaux messages en temps réel ; par défaut aucun signal (l'écran relit périodiquement). */
    default AutoCloseable subscribeMessages(java.util.function.Consumer<String> onConversation) {
        return () -> { };
    }

    // ------------------------------------------------------------------ production : compte et entités

    /** Vrai après une connexion avec un mot de passe provisoire : seul le changement de mot de passe est permis. */
    default boolean mustChangePassword() {
        return false;
    }

    default void changePassword(char[] current, char[] next) {
        throw notYet("Le changement de mot de passe");
    }

    /** Entités où l'utilisateur détient la permission (code serveur, ex. « vehicle:write »). */
    default java.util.List<Prod.OrgUnit> orgUnits(String permission) {
        throw notYet("La liste des entités");
    }

    // ------------------------------------------------------------------ production : missions

    default Ops.Mission createMission(Prod.MissionDraft draft) {
        throw notYet("La création de mission");
    }

    default Ops.Mission authorizeMission(UUID missionId) {
        throw notYet("L'autorisation de mission");
    }

    default Ops.Mission cancelMission(UUID missionId, String reason) {
        throw notYet("L'annulation de mission");
    }

    // ------------------------------------------------------------------ production : parc, conducteurs, documents

    default void assignDriver(UUID vehicleId, UUID driverId, String reason) {
        throw notYet("L'affectation d'un conducteur");
    }

    default java.util.List<Prod.DriverRef> driverRefs() {
        return drivers("").stream().map(d -> new Prod.DriverRef(d.id(), d.fullName(), d.staffNumber())).toList();
    }

    default Models.Driver createDriver(Prod.DriverDraft draft) {
        throw notYet("La création de conducteur");
    }

    default java.util.List<Prod.VehicleDocument> vehicleDocuments(UUID vehicleId) {
        throw notYet("Les documents du véhicule");
    }

    default Prod.VehicleDocument addVehicleDocument(UUID vehicleId, String docType, String reference, java.time.LocalDate validFrom,
                                                    java.time.LocalDate validUntil, UUID fileId) {
        throw notYet("L'ajout de document");
    }

    // ------------------------------------------------------------------ production : pièces justificatives

    /** Dépôt d'un PDF, JPEG, PNG ou WebP (10 Mo max) ; le serveur vérifie le contenu réel du fichier. */
    /** {@code vehicleId} (facultatif) : le serveur rattache le fichier à l'entité du véhicule. */
    default Prod.StoredFile upload(String purpose, UUID vehicleId, java.nio.file.Path file) {
        throw notYet("Le dépôt de pièces justificatives");
    }

    default byte[] download(UUID fileId) {
        throw notYet("Le téléchargement de pièces justificatives");
    }

    // ------------------------------------------------------------------ production : atelier

    default java.util.List<Prod.Category> maintenanceCategories() {
        throw notYet("L'atelier");
    }

    default java.util.List<Prod.WorkOrder> workOrders(boolean openOnly) {
        throw notYet("L'atelier");
    }

    default Prod.WorkOrder requestWork(UUID vehicleId, String categoryCode, String title) {
        throw notYet("Les demandes d'intervention");
    }

    default Prod.WorkOrder transitionWork(UUID orderId, long version, Prod.WorkTransition t) {
        throw notYet("Le suivi des interventions");
    }

    default java.util.List<Prod.OilChange> oilChanges() {
        throw notYet("Les vidanges");
    }

    default Prod.OilChange addOilChange(Prod.OilChangeDraft draft) {
        throw notYet("La saisie de vidange");
    }

    // ------------------------------------------------------------------ production : carburant

    default java.util.List<Prod.Supplier> suppliers() {
        throw notYet("Les fournisseurs");
    }

    default Prod.Supplier createSupplier(String code, String name, String kind) {
        throw notYet("La création de fournisseur");
    }

    default java.util.List<Prod.FuelOrder> fuelOrders() {
        throw notYet("Les bons de carburant");
    }

    default Prod.FuelOrder createFuelOrder(Prod.FuelOrderDraft draft, boolean submit) {
        throw notYet("La demande de bon");
    }

    default void approveFuelOrder(UUID orderId, String comment) {
        throw notYet("La validation de bon");
    }

    default void cancelFuelOrder(UUID orderId, String reason) {
        throw notYet("L'annulation de bon");
    }

    default java.util.List<Prod.FuelFill> fuelFills() {
        throw notYet("Les pleins");
    }

    /** Idempotent : la même {@code requestKey} ne crée jamais deux pleins (renvoi après coupure réseau). */
    default void recordFill(String requestKey, Prod.FillDraft draft) {
        throw notYet("La saisie de plein");
    }

    // ------------------------------------------------------------------ production : coûts et rapports

    default Prod.CostSummary costs(int year) {
        throw notYet("Le suivi des coûts");
    }

    default Prod.ImportResult importBudget(int year, UUID orgUnitId, String sourceFile, String csv) {
        throw notYet("L'import du budget");
    }

    default java.util.List<Prod.ReportInfo> reports() {
        throw notYet("Les rapports");
    }

    default byte[] exportReport(String code, java.time.LocalDate from, java.time.LocalDate to) {
        throw notYet("L'export de rapports");
    }

    // ------------------------------------------------------------------ production : comptes

    default Prod.OrgUnit createOrgUnit(String code, String name, UUID parentId) {
        throw notYet("La création d'entité");
    }

    default java.util.List<Prod.RoleInfo> roles() {
        throw notYet("La liste des rôles");
    }

    default Prod.CreatedAccount createAccount(Prod.AccountDraft draft) {
        throw notYet("La création de compte");
    }

    default java.util.List<Prod.RoleGrant> grants(String userId) {
        throw notYet("Les rôles du compte");
    }

    default void grantRole(String userId, String role, UUID scopeOrgId, java.time.Instant validUntil) {
        throw notYet("L'attribution de rôle");
    }

    default void revokeGrant(String userId, UUID grantId, String reason) {
        throw notYet("Le retrait de rôle");
    }

    /** Renvoie le nouveau mot de passe provisoire (affiché une seule fois). */
    default String resetPassword(String userId, String reason) {
        throw notYet("La réinitialisation de mot de passe");
    }

    default void setAccountStatus(String userId, boolean active, String reason) {
        throw notYet("La suspension de compte");
    }

    // ------------------------------------------------------------------ production : messagerie

    default java.util.List<Prod.Contact> contacts() {
        throw notYet("Les contacts");
    }

    default Ops.Conversation openConversation(java.util.List<String> userIds, String title) {
        throw notYet("La création de conversation");
    }

    default Ops.ChatLine sendMessage(String conversationId, String clientMessageId, String body, java.util.List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return sendMessage(conversationId, clientMessageId, body);
        throw notYet("L'envoi de pièces jointes");
    }

    static GatewayException notYet(String what) {
        return new GatewayException(GatewayException.Kind.SERVER, what + " n'est pas disponible dans ce mode.");
    }
}
