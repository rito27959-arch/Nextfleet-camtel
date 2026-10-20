package cm.camtel.nextfleet.desktop.data;

import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import cm.camtel.nextfleet.desktop.data.Ops.AuditEntry;
import cm.camtel.nextfleet.desktop.data.Ops.ChatLine;
import cm.camtel.nextfleet.desktop.data.Ops.Conversation;
import cm.camtel.nextfleet.desktop.data.Ops.Grant;
import cm.camtel.nextfleet.desktop.data.Ops.Mission;
import cm.camtel.nextfleet.desktop.data.Ops.MissionStatus;
import cm.camtel.nextfleet.desktop.data.Ops.UserAccount;
import cm.camtel.nextfleet.domain.assistant.AssistantActivation;
import cm.camtel.nextfleet.domain.assistant.FleetAssistant;
import cm.camtel.nextfleet.domain.assistant.FleetFacts;
import cm.camtel.nextfleet.domain.chat.Chat;
import cm.camtel.nextfleet.domain.chat.InMemoryChatStore;
import cm.camtel.nextfleet.domain.fuel.ConsumptionResult;
import cm.camtel.nextfleet.domain.fuel.FuelFillRecord;
import cm.camtel.nextfleet.domain.fuel.FullToFullConsumptionCalculator;
import cm.camtel.nextfleet.domain.gps.GpsFix;
import cm.camtel.nextfleet.domain.gps.TrackAnalyzer;
import cm.camtel.nextfleet.domain.security.Permission;
import cm.camtel.nextfleet.domain.security.Role;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Opérations du lot 2 en mode DÉMONSTRATION. Données fictives, mais calculs réels du noyau métier :
 * statistiques de trace ({@link TrackAnalyzer}), messagerie ({@link InMemoryChatStore}, mêmes règles que PostgreSQL),
 * assistant ({@link FleetAssistant}), activation ({@link AssistantActivation}), journal chaîné SHA-256.
 * Les tracés suivent des itinéraires approximatifs dessinés à la main ; ce ne sont pas des relevés réels.
 */
final class DemoOperations {

    static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    static final String NOTICE = "NOTICE-GPS-v1";

    /** Comptes de démonstration : identifiant → (nom, rôle). Mot de passe commun : « demo ». */
    static final Map<String, String[]> ACCOUNTS = new LinkedHashMap<>();

    static {
        ACCOUNTS.put("gestionnaire", new String[]{"Aline Mvondo (démo)", "FLEET_MANAGER"});
        ACCOUNTS.put("conducteur", new String[]{"Félix Kamga (démo)", "DRIVER"});
        ACCOUNTS.put("maintenance", new String[]{"Atelier central (démo)", "MAINTENANCE"});
        ACCOUNTS.put("controleur", new String[]{"Paul Essomba (démo)", "CONTROLLER"});
        ACCOUNTS.put("admin", new String[]{"Admin technique (démo)", "ADMIN"});
        ACCOUNTS.put("auditeur", new String[]{"Nadine Ewane (démo)", "AUDITOR"});
    }

    private final DemoFleetGateway fleet;
    private final Clock clock;
    private final List<Mission> missions = new CopyOnWriteArrayList<>();
    private final Map<UUID, double[][]> routes = new LinkedHashMap<>();
    private final InMemoryChatStore chat;
    private final Map<String, String[]> conversationMeta = new LinkedHashMap<>();   // id → kind, title, subtitle
    private final Map<String, String> names = new LinkedHashMap<>();
    private final List<AssistantActivation.State> decisions = new CopyOnWriteArrayList<>();
    private final List<AuditEntry> audit = new CopyOnWriteArrayList<>();
    private final FleetAssistant assistant = new FleetAssistant(this::logAssistant);
    private final DemoClock chatClock;

    DemoOperations(DemoFleetGateway fleet, Clock clock) {
        this.fleet = fleet;
        this.clock = clock;
        this.chatClock = new DemoClock(clock);
        ACCOUNTS.forEach((login, v) -> names.put(login, v[0].replace(" (démo)", "")));
        names.put("mbarga", "Alain Mbarga");
        names.put("abena", "Lucie Abena");
        this.chat = new InMemoryChatStore(chatClock, u -> !"auditeur".equals(u) && !"admin".equals(u));
        seedMissions();
        seedChat();
        seedAudit();
        decisions.add(new AssistantActivation.State(true, "DS-DEMO-001", "RSSI CAMTEL (fictif)", "Admin technique (démo)",
                clock.instant().minus(Duration.ofDays(2)), "Pilote de démonstration BUT Yaoundé"));
    }

    // ================================================================ MISSIONS ET GPS

    private void seedMissions() {
        Instant now = clock.instant();
        double[][] mbalmayo = {{3.8667, 11.5167}, {3.8480, 11.5100}, {3.8205, 11.5032}, {3.7890, 11.4978}, {3.7520, 11.4930},
                {3.7105, 11.4952}, {3.6680, 11.5010}, {3.6240, 11.5035}, {3.5790, 11.5012}, {3.5390, 11.4990}, {3.5172, 11.5003}};
        double[][] kribi = {{2.9406, 9.9100}, {2.9105, 9.9022}, {2.8760, 9.8935}, {2.8402, 9.8871}, {2.8011, 9.8795},
                {2.7605, 9.8732}, {2.7190, 9.8650}, {2.6702, 9.8571}, {2.6150, 9.8480}};
        double[][] soa = {{3.8667, 11.5167}, {3.8842, 11.5301}, {3.9050, 11.5480}, {3.9285, 11.5640}, {3.9560, 11.5810}, {3.9833, 11.5958}};
        double[][] obala = {{3.8667, 11.5167}, {3.8960, 11.5080}, {3.9380, 11.5010}, {3.9810, 11.4985}, {4.0270, 11.5020},
                {4.0760, 11.5110}, {4.1240, 11.5230}, {4.1667, 11.5333}};
        double[][] dschang = {{5.4778, 10.4176}, {5.4600, 10.2900}, {5.4440, 10.0530}};
        add(mission("MIS-2026-014", "Intervention fibre — site de Mbalmayo", "Yaoundé, Poste centrale", "Mbalmayo",
                "LT 5520 CE", "Alain Mbarga", "mbarga", MissionStatus.ACTIVE, now.minus(Duration.ofMinutes(70)),
                now.plus(Duration.ofHours(4)), now.minus(Duration.ofMinutes(52)), null, "AND-7F3K", "conv-014"), mbalmayo);
        add(mission("MIS-2026-013", "Maintenance station côtière", "Kribi, agence", "Campo (direction)",
                "CE 4210 MK", "Lucie Abena", "abena", MissionStatus.ACTIVE, now.minus(Duration.ofHours(3)),
                now.plus(Duration.ofHours(3)), now.minus(Duration.ofMinutes(128)), null, "AND-2B9Q", "conv-013"), kribi);
        Instant yStart = now.truncatedTo(ChronoUnit.DAYS).minus(Duration.ofHours(15));
        add(mission("MIS-2026-012", "Livraison de matériel — Soa", "Yaoundé, Poste centrale", "Soa",
                "CE 4401 LT", "Jean Nguema", "nguema", MissionStatus.FINISHED, yStart.minus(Duration.ofMinutes(20)),
                yStart.plus(Duration.ofHours(3)), yStart, yStart.plus(Duration.ofMinutes(66)), "AND-91XC", null), soa);
        add(mission("MIS-2026-015", "Relevé de compteurs — antenne d'Obala", "Yaoundé, Poste centrale", "Obala",
                "CE 7302 RT", "Félix Kamga", "conducteur", MissionStatus.AUTHORIZED, now.minus(Duration.ofMinutes(10)),
                now.plus(Duration.ofHours(5)), null, null, null, "conv-015"), obala);
        add(mission("MIS-2026-016", "Audit d'infrastructure — Dschang", "Bafoussam, agence", "Dschang",
                "OU 1180 PL", "Claire Ngono", "ngono", MissionStatus.DRAFT, now.plus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(1)).plus(Duration.ofHours(6)), null, null, null, null), dschang);
        Instant lastWeek = now.minus(Duration.ofDays(6)).truncatedTo(ChronoUnit.HOURS);
        add(mission("MIS-2026-009", "Dépannage relais — Obala", "Yaoundé, Poste centrale", "Obala",
                "CE 7302 RT", "Félix Kamga", "conducteur", MissionStatus.FINISHED, lastWeek, lastWeek.plus(Duration.ofHours(5)),
                lastWeek.plus(Duration.ofMinutes(5)), lastWeek.plus(Duration.ofMinutes(96)), "AND-DEMO", null), obala);
    }

    private static Mission mission(String ref, String purpose, String origin, String dest, String reg, String driver, String login,
                                   MissionStatus status, Instant ps, Instant pe, Instant started, Instant finished, String device,
                                   String conv) {
        return new Mission(UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8)), ref, purpose, origin, dest, reg, null,
                driver, login, status, ps, pe, started, finished, device, device == null ? null : NOTICE, conv, 1);
    }

    private void add(Mission m, double[][] route) {
        String label = fleet.allVehicles().stream().filter(v -> v.registration().equals(m.registration())).map(Models.Vehicle::label)
                .findFirst().orElse("");
        missions.add(new Mission(m.id(), m.reference(), m.purpose(), m.origin(), m.destination(), m.registration(), label,
                m.driverName(), m.driverLogin(), m.status(), m.plannedStart(), m.plannedEnd(), m.startedAt(), m.finishedAt(),
                m.deviceId(), m.noticeVersion(), m.conversationId(), m.version()));
        routes.put(m.id(), route);
    }

    List<Mission> missions(Session s) {
        boolean all = s.can(Permission.MISSION_MANAGE) || s.can(Permission.GPS_READ);
        if (!all && !s.can(Permission.MISSION_DRIVE)) throw forbidden();
        return missions.stream().filter(m -> all || s.login().equals(m.driverLogin()))
                .sorted(Comparator.comparing((Mission m) -> order(m.status())).thenComparing(Mission::plannedStart, Comparator.reverseOrder()))
                .toList();
    }

    /** Carte de flotte FICTIVE : mêmes règles d'état que le serveur (FleetLiveService). */
    List<Ops.VehicleLive> fleetLive(Session s) {
        Instant now = clock.instant();
        List<Ops.VehicleLive> out = new ArrayList<>();
        for (Mission m : missions(s)) {
            boolean active = m.status() == MissionStatus.ACTIVE;
            boolean recent = m.status() == MissionStatus.FINISHED && m.finishedAt() != null
                    && m.finishedAt().isAfter(now.minus(Duration.ofHours(12)));
            if (!active && !recent) continue;
            List<GpsFix> t = track(s, m.id());
            if (t.isEmpty()) continue;
            GpsFix last = t.get(t.size() - 1);
            double km = 0;
            for (int i = 1; i < t.size(); i++) {
                km += TrackAnalyzer.distanceM(t.get(i - 1).latitude(), t.get(i - 1).longitude(), t.get(i).latitude(), t.get(i).longitude());
            }
            Double bearing = null;
            if (t.size() > 1) {
                GpsFix p = t.get(t.size() - 2);
                double y = Math.sin(Math.toRadians(last.longitude() - p.longitude())) * Math.cos(Math.toRadians(last.latitude()));
                double x = Math.cos(Math.toRadians(p.latitude())) * Math.sin(Math.toRadians(last.latitude()))
                        - Math.sin(Math.toRadians(p.latitude())) * Math.cos(Math.toRadians(last.latitude()))
                        * Math.cos(Math.toRadians(last.longitude() - p.longitude()));
                bearing = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
            }
            long age = Math.max(0, Duration.between(last.recordedAt(), now).getSeconds());
            Double speedKmh = last.speedMps() == null ? null : Math.round(last.speedMps() * 36) / 10.0;
            Ops.LiveState st = !active || age > 120 ? Ops.LiveState.SIGNAL_ANCIEN
                    : last.speedMps() != null && last.speedMps() >= 1.5 ? Ops.LiveState.EN_MOUVEMENT : Ops.LiveState.ARRET;
            String label = st == Ops.LiveState.SIGNAL_ANCIEN && !active ? "Mission terminée" : st.label;
            String category = fleet.allVehicles().stream().filter(v -> v.registration().equals(m.registration()))
                    .map(Models.Vehicle::category).filter(java.util.Objects::nonNull).findFirst().orElse("");
            out.add(new Ops.VehicleLive(m.registration(), m.registration(), m.vehicleLabel(), category, m.id().toString(),
                    m.reference(), m.status().name(), m.driverName(), m.origin(), m.destination(), last.latitude(), last.longitude(),
                    last.accuracyM(), speedKmh, bearing, last.recordedAt(), age, st, label, m.startedAt(),
                    Math.round(km / 100) / 10.0, 0));
        }
        out.sort(Comparator.comparing(Ops.VehicleLive::recordedAt).reversed());
        return out;
    }

    private static int order(MissionStatus s) {
        return switch (s) {
            case ACTIVE -> 0;
            case AUTHORIZED -> 1;
            case DRAFT -> 2;
            default -> 3;
        };
    }

    private Mission find(UUID id) {
        return missions.stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> new GatewayException(GatewayException.Kind.NOT_FOUND, "Mission introuvable."));
    }

    /**
     * Trace FICTIVE : un point toutes les 20 s entre le démarrage et la clôture (ou maintenant), le long de l'itinéraire,
     * avec un arrêt de 6 min à 40 % du parcours. Aucun point n'existe hors de la période de mission.
     */
    List<GpsFix> track(Session s, UUID id) {
        Mission m = find(id);
        if (!(s.can(Permission.GPS_READ) || s.can(Permission.MISSION_MANAGE) || s.login().equals(m.driverLogin()))) throw forbidden();
        if (m.startedAt() == null) return List.of();
        Instant end = m.finishedAt() != null ? m.finishedAt() : clock.instant();
        double[][] route = routes.get(id);
        double total = 0;
        double[] cum = new double[route.length];
        for (int i = 1; i < route.length; i++) {
            total += TrackAnalyzer.distanceM(route[i - 1][0], route[i - 1][1], route[i][0], route[i][1]);
            cum[i] = total;
        }
        long seconds = Duration.between(m.startedAt(), end).getSeconds();
        double planned = m.finishedAt() != null ? Duration.between(m.startedAt(), m.finishedAt()).getSeconds() : 90 * 60;
        double baseSpeed = total / Math.max(600, planned - 360);   // m/s, arrêt de 6 min inclus
        List<GpsFix> out = new ArrayList<>();
        double travelled = 0;
        int seed = Math.abs(m.reference().hashCode());
        long stopFrom = (long) (total * 0.4 / baseSpeed);
        for (long t = 0, k = 0; t <= seconds; t += 20, k++) {
            boolean stopped = t >= stopFrom && t < stopFrom + 360;
            double v = stopped ? 0 : Math.max(2, baseSpeed * (0.85 + 0.3 * Math.sin((k + seed % 17) / 6.0)));
            if (t > 0) travelled = Math.min(total, travelled + v * 20);
            double[] p = along(route, cum, travelled);
            double acc = 4 + ((k * 7 + seed) % 9);
            out.add(new GpsFix(m.deviceId() + ":" + k, m.startedAt().plusSeconds(t), p[0], p[1], acc, travelled >= total ? 0.0 : v));
            if (travelled >= total && t > 60 && m.finishedAt() != null) break;   // mission en cours : le véhicule garé émet toujours
        }
        return out;
    }

    private static double[] along(double[][] route, double[] cum, double d) {
        for (int i = 1; i < route.length; i++) {
            if (d <= cum[i]) {
                double f = (d - cum[i - 1]) / Math.max(1e-9, cum[i] - cum[i - 1]);
                return new double[]{route[i - 1][0] + f * (route[i][0] - route[i - 1][0]), route[i - 1][1] + f * (route[i][1] - route[i - 1][1])};
            }
        }
        return route[route.length - 1];
    }

    Mission start(Session s, UUID id, String notice) {
        Mission m = find(id);
        if (!s.can(Permission.MISSION_DRIVE) || !s.login().equals(m.driverLogin())) {
            throw new GatewayException(GatewayException.Kind.FORBIDDEN, "Seul le conducteur affecté peut démarrer cette mission.");
        }
        if (m.status() != MissionStatus.AUTHORIZED) {
            throw new GatewayException(GatewayException.Kind.CONFLICT, "Cette mission n'est pas autorisée ou a déjà démarré.");
        }
        if (!NOTICE.equals(notice)) throw new GatewayException(GatewayException.Kind.VALIDATION, "Notice de localisation non reconnue.");
        Instant now = clock.instant();
        Mission started = with(m, MissionStatus.ACTIVE, now, null, "AND-DEMO");
        missions.replaceAll(x -> x.id().equals(id) ? started : x);
        record(s, "MISSION_STARTED", m.reference(), "Collecte autorisée · émetteur AND-DEMO · " + NOTICE);
        return started;
    }

    Mission finish(Session s, UUID id) {
        Mission m = find(id);
        if (!(s.login().equals(m.driverLogin()) || s.can(Permission.MISSION_MANAGE))) throw forbidden();
        if (m.status() != MissionStatus.ACTIVE) throw new GatewayException(GatewayException.Kind.CONFLICT, "Cette mission n'est pas en cours.");
        Mission done = with(m, MissionStatus.FINISHED, m.startedAt(), clock.instant(), m.deviceId());
        missions.replaceAll(x -> x.id().equals(id) ? done : x);
        record(s, "MISSION_FINISHED", m.reference(), "Autorisation de collecte révoquée");
        return done;
    }

    private static Mission with(Mission m, MissionStatus st, Instant started, Instant finished, String device) {
        return new Mission(m.id(), m.reference(), m.purpose(), m.origin(), m.destination(), m.registration(), m.vehicleLabel(),
                m.driverName(), m.driverLogin(), st, m.plannedStart(), m.plannedEnd(), started, finished, device,
                device == null ? null : NOTICE, m.conversationId(), m.version() + 1);
    }

    // ================================================================ MESSAGERIE

    private void seedChat() {
        Instant now = clock.instant();
        conv("conv-014", Chat.Kind.MISSION, "MIS-2026-014", "Yaoundé → Mbalmayo", Set.of("gestionnaire", "mbarga", "controleur"));
        conv("conv-013", Chat.Kind.MISSION, "MIS-2026-013", "Kribi → Campo", Set.of("gestionnaire", "abena"));
        conv("conv-015", Chat.Kind.MISSION, "MIS-2026-015", "Yaoundé → Obala", Set.of("gestionnaire", "conducteur"));
        conv("conv-atelier", Chat.Kind.SERVICE, "Atelier · BK 7280 LT", "Toyota Prado en entretien", Set.of("gestionnaire", "maintenance", "controleur"));
        conv("dm-ctrl", Chat.Kind.DIRECT, "Paul Essomba", "Contrôleur", Set.of("gestionnaire", "controleur"));

        say("conv-014", "gestionnaire", now.minus(Duration.ofMinutes(58)), "Bonjour Alain, mission autorisée. Merci de confirmer le départ.");
        say("conv-014", "mbarga", now.minus(Duration.ofMinutes(53)), "Départ confirmé, compteur 61 204 km.");
        say("conv-014", "gestionnaire", now.minus(Duration.ofMinutes(52)), "Parfait. Pense à photographier le compteur à l'arrivée.");
        say("conv-014", "controleur", now.minus(Duration.ofMinutes(31)), "Le site de Mbalmayo est prévenu de votre arrivée.");
        say("conv-014", "mbarga", now.minus(Duration.ofMinutes(9)), "Arrêt de quelques minutes au péage de Nkolmesseng, je repars.");
        read("conv-014", "mbarga", 4);
        read("conv-014", "controleur", 5);

        say("conv-013", "abena", now.minus(Duration.ofMinutes(125)), "Départ de l'agence de Kribi.");
        say("conv-013", "gestionnaire", now.minus(Duration.ofMinutes(122)), "Bien noté, bonne route.");
        read("conv-013", "abena", 2);

        say("conv-015", "gestionnaire", now.minus(Duration.ofMinutes(40)), "Félix, la mission d'Obala est autorisée. Tu peux démarrer quand tu es prêt.");
        say("conv-015", "conducteur", now.minus(Duration.ofMinutes(35)), "Merci, je pars vers 10 h après le plein.");
        read("conv-015", "gestionnaire", 2);

        say("conv-atelier", "maintenance", now.minus(Duration.ofHours(20)), "Le Prado BK 7280 LT est sur le pont : plaquettes et disques avant à changer.");
        say("conv-atelier", "gestionnaire", now.minus(Duration.ofHours(19)), "D'accord. Délai estimé ?");
        say("conv-atelier", "maintenance", now.minus(Duration.ofHours(3)), "Pièces reçues ce matin. Restitution prévue demain 16 h.");
        read("conv-atelier", "controleur", 3);

        say("dm-ctrl", "controleur", now.minus(Duration.ofDays(1)), "Peux-tu me transmettre les bons de carburant de septembre pour le rapprochement ?");
        say("dm-ctrl", "gestionnaire", now.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(12)), "Oui, je les dépose dans le dossier partagé d'ici ce soir.");
        read("dm-ctrl", "controleur", 2);
        chatClock.release();
    }

    private void conv(String id, Chat.Kind kind, String title, String subtitle, Set<String> members) {
        chat.createConversation(id, members);
        conversationMeta.put(id, new String[]{kind.name(), title, subtitle});
    }

    private void say(String conv, String who, Instant at, String body) {
        chatClock.set(at);
        chat.post(conv, who, "seed-" + conv + "-" + chat.lastSeq(conv), body);
    }

    private void read(String conv, String who, long seq) {
        chat.ackRead(conv, who, seq);
    }

    List<Conversation> conversations(Session s) {
        requireChat(s);
        List<Conversation> out = new ArrayList<>();
        conversationMeta.forEach((id, meta) -> {
            Map<String, Chat.Cursor> cursors;
            try {
                cursors = chat.cursors(id, s.login());
            } catch (Chat.AccessDenied notMember) {
                return;
            }
            List<Chat.Message> all = chat.after(id, s.login(), 0, 10_000);
            Chat.Message last = all.isEmpty() ? null : all.get(all.size() - 1);
            long unread = all.size() - cursors.getOrDefault(s.login(), new Chat.Cursor(0, 0)).read();
            Chat.Kind kind = Chat.Kind.valueOf(meta[0]);
            String title = meta[1];
            String subtitle = meta[2];
            if (kind == Chat.Kind.DIRECT) {   // le titre d'une conversation directe est l'autre personne
                String other = cursors.keySet().stream().filter(u -> !u.equals(s.login())).findFirst().orElse("");
                title = names.getOrDefault(other, other);
                subtitle = ACCOUNTS.containsKey(other) ? Role.valueOf(ACCOUNTS.get(other)[1]).label : "";
            }
            out.add(new Conversation(id, kind, title, subtitle, last == null ? null : last.body(),
                    last == null ? null : (last.senderId().equals(s.login()) ? "Vous" : first(names.get(last.senderId()))),
                    last == null ? null : last.createdAt(), Math.max(0, unread),
                    cursors.keySet().stream().map(u -> names.getOrDefault(u, u)).toList()));
        });
        out.sort(Comparator.comparing(Conversation::lastAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    List<ChatLine> messages(Session s, String conv) {
        requireChat(s);
        try {
            Map<String, Chat.Cursor> cursors = chat.cursors(conv, s.login());
            return chat.after(conv, s.login(), 0, 10_000).stream().map(m -> line(s, m, cursors)).toList();
        } catch (Chat.AccessDenied e) {
            throw new GatewayException(GatewayException.Kind.FORBIDDEN, e.getMessage());
        }
    }

    ChatLine send(Session s, String conv, String clientId, String body) {
        requireChat(s);
        try {
            Chat.PostResult r = chat.post(conv, s.login(), clientId, body);
            if (!r.duplicate()) record(s, "MESSAGE_SENT", conversationMeta.get(conv)[1], "Message n° " + r.message().serverSeq()
                    + " (contenu non journalisé)");
            return line(s, r.message(), chat.cursors(conv, s.login()));
        } catch (Chat.AccessDenied e) {
            throw new GatewayException(GatewayException.Kind.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new GatewayException(GatewayException.Kind.VALIDATION, e.getMessage());
        }
    }

    void markRead(Session s, String conv) {
        requireChat(s);
        long last = chat.lastSeq(conv);
        if (last > 0) chat.ackRead(conv, s.login(), last);
    }

    private ChatLine line(Session s, Chat.Message m, Map<String, Chat.Cursor> cursors) {
        boolean mine = m.senderId().equals(s.login());
        return new ChatLine(m.serverSeq(), m.senderId(), names.getOrDefault(m.senderId(), m.senderId()), m.body(), m.createdAt(),
                mine, mine ? Chat.stateFor(m, cursors) : null, m.clientMessageId());
    }

    private static void requireChat(Session s) {
        if (!s.can(Permission.CHAT_USE)) throw new GatewayException(GatewayException.Kind.FORBIDDEN,
                "La messagerie n'est pas ouverte à votre profil (confidentialité des échanges).");
    }

    private static String first(String full) {
        return full == null ? "" : full.split(" ")[0];
    }

    // ================================================================ ASSISTANT

    AssistantActivation.State assistantState() {
        return decisions.isEmpty() ? AssistantActivation.State.disabledByDefault() : decisions.get(decisions.size() - 1);
    }

    List<AssistantActivation.State> decisions(Session s) {
        if (!s.can(Permission.ADMIN_SETTINGS) && !s.can(Permission.AUDIT_READ)) throw forbidden();
        return List.copyOf(decisions);
    }

    AssistantActivation.State decide(Session s, boolean enable, String ref, String approvedBy, String reason) {
        try {
            AssistantActivation.State st = AssistantActivation.decide(enable, ref, approvedBy, reason, s.displayName(),
                    s.permissions(), clock.instant());
            decisions.add(st);
            record(s, enable ? "ASSISTANT_ENABLED" : "ASSISTANT_DISABLED", "Assistant IA",
                    enable ? "Validation " + st.securityReviewRef() + " signée par " + st.approvedBy() : "Motif : " + st.reason());
            return st;
        } catch (AssistantActivation.Refused e) {
            throw new GatewayException(e.getMessage().startsWith("Seule") ? GatewayException.Kind.FORBIDDEN
                    : GatewayException.Kind.VALIDATION, e.getMessage());
        }
    }

    private Session askingSession;

    synchronized FleetAssistant.Answer ask(Session s, String question) {
        askingSession = s;
        return assistant.ask(s.login(), s.permissions(), assistantState(), facts(), question,
                LocalDate.now(clock.withZone(DOUALA)));
    }

    private void logAssistant(FleetAssistant.Run run) {
        if (askingSession != null) {
            record(askingSession, run.refused() ? "ASSISTANT_REFUSED" : "ASSISTANT_ANSWERED", "Assistant IA",
                    run.intent() + " · " + run.sourceRefs().size() + " source(s)");
        }
    }

    /** Faits en lecture seule tirés des données de démonstration (pleins réellement calculés plein-à-plein). */
    private FleetFacts facts() {
        return new FleetFacts() {
            public List<VehicleFact> vehicles() {
                return fleet.allVehicles().stream().filter(v -> v.status() != Models.VehicleStatus.ARCHIVED)
                        .map(v -> new VehicleFact(v.registration(), v.label(), v.status().label, "vehicle:" + v.registration())).toList();
            }

            public List<CostFact> costs(LocalDate from, LocalDate to) {
                List<CostFact> out = new ArrayList<>();
                int i = 0;
                for (Models.Vehicle v : fleet.allVehicles()) {
                    for (FuelFillRecord f : fleet.fillsOf(v.id())) {
                        LocalDate d = f.filledAt().atZone(DOUALA).toLocalDate();
                        if (d.isBefore(from) || d.isAfter(to)) continue;
                        out.add(new CostFact(v.registration(), d, "Carburant", f.liters().multiply(BigDecimal.valueOf(840))
                                .setScale(0, RoundingMode.HALF_UP), "fuel_fill:" + f.id().substring(f.id().lastIndexOf('-') + 1) + "-" + v.registration().replace(" ", "")));
                    }
                    for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(to)); ym = ym.plusMonths(1)) {
                        LocalDate d = ym.atDay(Math.min(10, ym.lengthOfMonth()));
                        if (d.isBefore(from) || d.isAfter(to)) continue;
                        out.add(new CostFact(v.registration(), d, "Entretien", BigDecimal.valueOf(i % 3 == 0 ? 210_000 : 95_000),
                                "maintenance:M-" + ym.getMonthValue() + String.format("%02d", i)));
                    }
                    i++;
                }
                return out;
            }

            public List<DueFact> dueItems() {
                return fleet.dueDates(30).stream().map(d -> new DueFact(d.kind().label, d.subject(), d.dueOn(), d.daysLeft(),
                        d.level().name(), "due:" + d.kind() + ":" + d.subject().replace(" ", ""))).toList();
            }

            public List<MissionFact> missions() {
                return missions.stream().map(m -> new MissionFact(m.reference(), m.registration(), m.driverName(), m.status().name(),
                        "mission:" + m.reference())).toList();
            }

            public List<ConsumptionFact> consumption(YearMonth month) {
                FullToFullConsumptionCalculator calc = new FullToFullConsumptionCalculator();
                List<ConsumptionFact> out = new ArrayList<>();
                for (Models.Vehicle v : fleet.allVehicles()) {
                    List<FuelFillRecord> list = fleet.fillsOf(v.id());
                    Map<String, Instant> at = new LinkedHashMap<>();
                    list.forEach(f -> at.put(f.id(), f.filledAt()));
                    BigDecimal sum = BigDecimal.ZERO;
                    int n = 0;
                    boolean insufficient = false;
                    for (ConsumptionResult r : calc.allPeriods(list)) {
                        if (r.endFillId() == null || !YearMonth.from(at.get(r.endFillId()).atZone(DOUALA)).equals(month)) continue;
                        if (r.isCalculated()) {
                            sum = sum.add(r.litersPer100Km());
                            n++;
                        } else insufficient = true;
                    }
                    if (n > 0) out.add(new ConsumptionFact(v.registration(), month, sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP),
                            "fuel_period:" + v.registration().replace(" ", "") + ":" + month));
                    else if (insufficient) out.add(new ConsumptionFact(v.registration(), month, null, "fuel_period:" + v.registration().replace(" ", "")));
                }
                return out;
            }
        };
    }

    // ================================================================ COMPTES ET AUDIT

    List<UserAccount> users(Session s) {
        if (!s.can(Permission.ADMIN_USERS)) throw forbidden();
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(DOUALA));
        List<UserAccount> out = new ArrayList<>();
        int i = 0;
        for (var e : ACCOUNTS.entrySet()) {
            out.add(new UserAccount("u-" + e.getKey(), e.getKey(), e.getValue()[0].replace(" (démo)", ""), "BUT Yaoundé",
                    List.of(new Grant(Role.valueOf(e.getValue()[1]), "BUT Yaoundé et sous-entités", null, false)), "ACTIVE",
                    now.minus(Duration.ofMinutes(5L + i++ * 47))));
        }
        out.add(new UserAccount("u-mbarga", "a.mbarga", "Alain Mbarga", "BUT Yaoundé",
                List.of(new Grant(Role.DRIVER, "BUT Yaoundé", null, false)), "ACTIVE", now.minus(Duration.ofMinutes(9))));
        out.add(new UserAccount("u-abena", "l.abena", "Lucie Abena", "Agence de Kribi",
                List.of(new Grant(Role.DRIVER, "Agence de Kribi", null, false)), "ACTIVE", now.minus(Duration.ofHours(2))));
        out.add(new UserAccount("u-onana", "j.onana", "Joseph Onana", "BUT Douala",
                List.of(new Grant(Role.FLEET_MANAGER, "BUT Douala", null, false),
                        new Grant(Role.CONTROLLER, "BUT Douala", today.plusDays(12), true)), "ACTIVE", now.minus(Duration.ofDays(1))));
        out.add(new UserAccount("u-tchoua", "p.tchoua", "Paul Tchoua", "BUT Douala",
                List.of(new Grant(Role.DRIVER, "BUT Douala", null, false)), "SUSPENDED", now.minus(Duration.ofDays(34))));
        return out;
    }

    List<AuditEntry> audit(Session s) {
        if (!s.can(Permission.AUDIT_READ)) throw forbidden();
        List<AuditEntry> copy = new ArrayList<>(audit);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private void seedAudit() {
        Instant now = clock.instant();
        seedEvent(now.minus(Duration.ofDays(2)), "Admin technique", "ASSISTANT_ENABLED", "Assistant IA", "Validation DS-DEMO-001 signée par RSSI CAMTEL (fictif)");
        seedEvent(now.minus(Duration.ofHours(30)), "Aline Mvondo", "VEHICLE_UPDATED", "BK 7280 LT", "Statut : entretien requis");
        seedEvent(now.minus(Duration.ofMinutes(90)), "Paul Essomba", "MISSION_AUTHORIZED", "MIS-2026-014", "Autorisation de mission");
        seedEvent(now.minus(Duration.ofMinutes(52)), "Alain Mbarga", "MISSION_STARTED", "MIS-2026-014", "Collecte autorisée · émetteur AND-7F3K · " + NOTICE);
        seedEvent(now.minus(Duration.ofMinutes(40)), "Aline Mvondo", "MESSAGE_SENT", "MIS-2026-015", "Message n° 1 (contenu non journalisé)");
    }

    private void seedEvent(Instant at, String actor, String action, String object, String detail) {
        append(at, actor, action, object, detail);
    }

    void record(Session s, String action, String object, String detail) {
        append(clock.instant(), s.displayName().replace(" (démo)", ""), action, object, detail);
    }

    private synchronized void append(Instant at, String actor, String action, String object, String detail) {
        String prev = audit.isEmpty() ? AuditChain.GENESIS : audit.get(audit.size() - 1).hash();
        long seq = audit.size() + 1L;
        audit.add(new AuditEntry(seq, at, actor, action, object, detail, AuditChain.hash(prev, seq, at, actor, action, object, detail), prev));
    }

    private static GatewayException forbidden() {
        return new GatewayException(GatewayException.Kind.FORBIDDEN, "Vous n'avez pas les droits nécessaires pour cette action.");
    }

    /** Horloge figée pendant l'amorçage des conversations, puis alignée sur l'horloge de démonstration. */
    static final class DemoClock extends Clock {
        private final Clock base;
        private volatile Instant fixed;

        DemoClock(Clock base) {
            this.base = base;
        }

        void set(Instant at) {
            fixed = at;
        }

        void release() {
            fixed = null;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant f = fixed;
            return f != null ? f : base.instant();
        }
    }
}
