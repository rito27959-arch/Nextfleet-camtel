package cm.camtel.nextfleet.mobile.data;

import cm.camtel.nextfleet.domain.chat.Chat;
import cm.camtel.nextfleet.domain.chat.InMemoryChatStore;
import cm.camtel.nextfleet.domain.security.Permission;
import cm.camtel.nextfleet.mobile.data.Models.ChatLine;
import cm.camtel.nextfleet.mobile.data.Models.Conversation;
import cm.camtel.nextfleet.mobile.data.Models.Mission;
import cm.camtel.nextfleet.mobile.data.Models.MissionStatus;
import cm.camtel.nextfleet.mobile.data.Models.StartResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Données de DÉMONSTRATION (fictives), mêmes règles que le serveur : le conducteur ne voit et ne démarre que ses
 * missions, la messagerie passe par le cœur du noyau métier (participants, idempotence, états de remise).
 * En démonstration, la localisation est la VRAIE position du téléphone, conservée sur l'appareil pendant la mission.
 */
public final class DemoRepository implements Repository {

    static final Map<String, String[]> ACCOUNTS = new LinkedHashMap<>();

    static {
        ACCOUNTS.put("conducteur", new String[]{"Félix Kamga", "DRIVER"});
        ACCOUNTS.put("gestionnaire", new String[]{"Aline Mvondo", "FLEET_MANAGER"});
        ACCOUNTS.put("maintenance", new String[]{"Atelier central", "MAINTENANCE"});
        ACCOUNTS.put("controleur", new String[]{"Paul Essomba", "CONTROLLER"});
        ACCOUNTS.put("admin", new String[]{"Admin technique", "ADMIN"});
        ACCOUNTS.put("auditeur", new String[]{"Nadine Ewane", "AUDITOR"});
    }

    private final Clock clock;
    private final MutableClock chatClock;
    private final List<Mission> missions = new CopyOnWriteArrayList<>();
    private final Map<String, String> drivers = new LinkedHashMap<>();   // mission → identifiant du conducteur
    private final InMemoryChatStore chat;
    private final Map<String, String[]> meta = new LinkedHashMap<>();
    private volatile Session session;

    public DemoRepository() {
        this(Clock.systemUTC());
    }

    public DemoRepository(Clock clock) {
        this.clock = clock;
        this.chatClock = new MutableClock(clock);
        this.chat = new InMemoryChatStore(chatClock, u -> !"auditeur".equals(u) && !"admin".equals(u));
        seed();
    }

    @Override
    public boolean isDemo() {
        return true;
    }

    @Override
    public Session login(String login, char[] password) {
        String l = login == null ? "" : login.trim().toLowerCase(java.util.Locale.ROOT);
        boolean ok = "demo".equals(new String(password));
        Arrays.fill(password, '\0');
        String[] a = ACCOUNTS.get(l);
        if (!ok || a == null) throw new RepositoryException(RepositoryException.Kind.UNAUTHORIZED, "Identifiant ou mot de passe incorrect.");
        session = new Session("demo", l, a[0], List.of(a[1]), true);
        return session;
    }

    @Override
    public void logout() {
        session = null;
    }

    private Session require() {
        Session s = session;
        if (s == null) throw new RepositoryException(RepositoryException.Kind.UNAUTHORIZED, "Session expirée. Reconnectez-vous.");
        return s;
    }

    // ------------------------------------------------------------------ carte de flotte (positions FICTIVES)

    private static final double[][] MBALMAYO = {{3.8667, 11.5167}, {3.8410, 11.5098}, {3.8020, 11.5030}, {3.7560, 11.4990},
            {3.7105, 11.4952}, {3.6680, 11.5010}, {3.6240, 11.5035}, {3.5790, 11.5012}, {3.5390, 11.4990}, {3.5172, 11.5003}};
    private static final double[] KRIBI_PARKED = {2.6150, 9.8480};

    @Override
    public List<Models.VehicleLive> fleetLive() {
        Session s = require();
        if (!s.can(cm.camtel.nextfleet.domain.security.Permission.MISSION_MANAGE)
                && !s.can(cm.camtel.nextfleet.domain.security.Permission.GPS_READ)) {
            throw new RepositoryException(RepositoryException.Kind.FORBIDDEN, "La carte de flotte n'est pas ouverte à votre profil.");
        }
        Instant now = clock.instant();
        List<Models.VehicleLive> out = new java.util.ArrayList<>();
        Mission m = find("m-014");
        if (m.status == MissionStatus.ACTIVE && m.startedAt != null) {
            // 18 km en 90 min, position recalculée à chaque appel : le marqueur avance réellement en démonstration
            double f = Math.min(1, Duration.between(m.startedAt, now).getSeconds() / 5400.0);
            double[] p = along(MBALMAYO, f);
            double speed = f >= 1 ? 0.0 : 31 + 9 * Math.sin(now.getEpochSecond() / 40.0);
            out.add(new Models.VehicleLive("v-lt5520", m.registration, m.vehicleLabel, m.reference, m.driverName, m.destination,
                    f >= 1 ? "ARRET" : "EN_MOUVEMENT", f >= 1 ? "À l'arrêt" : "En mouvement", p[0], p[1], Math.round(speed * 10) / 10.0,
                    Math.round(f * 180) / 10.0, now.minusSeconds(4)));
        }
        out.add(new Models.VehicleLive("v-ce4210", "CE 4210 MK", "Toyota Hilux", "MIS-2026-013", "Lucie Abena", "Campo (direction)",
                "ARRET", "À l'arrêt", KRIBI_PARKED[0], KRIBI_PARKED[1], 0.0, 41.6, now.minusSeconds(12)));
        return out;
    }

    static double[] along(double[][] route, double fraction) {
        double total = 0;
        double[] cum = new double[route.length];
        for (int i = 1; i < route.length; i++) {
            total += Math.hypot(route[i][0] - route[i - 1][0], route[i][1] - route[i - 1][1]);
            cum[i] = total;
        }
        double target = total * Math.max(0, Math.min(1, fraction));
        for (int i = 1; i < route.length; i++) {
            if (cum[i] >= target) {
                double seg = cum[i] - cum[i - 1];
                double k = seg == 0 ? 0 : (target - cum[i - 1]) / seg;
                return new double[]{route[i - 1][0] + k * (route[i][0] - route[i - 1][0]), route[i - 1][1] + k * (route[i][1] - route[i - 1][1])};
            }
        }
        return route[route.length - 1];
    }

    // ------------------------------------------------------------------ missions

    private void seed() {
        Instant now = clock.instant();
        add(new Mission("m-015", "MIS-2026-015", "Relevé de compteurs — antenne d'Obala", "Yaoundé, Poste centrale", "Obala",
                "CE 7302 RT", "Isuzu D-Max", "Pick-up", "Félix Kamga", MissionStatus.AUTHORIZED, now.minus(Duration.ofMinutes(10)),
                null, null, null, "conv-015"), "conducteur");
        add(new Mission("m-014", "MIS-2026-014", "Intervention fibre — site de Mbalmayo", "Yaoundé, Poste centrale", "Mbalmayo",
                "LT 5520 CE", "Renault Duster", "SUV", "Alain Mbarga", MissionStatus.ACTIVE, now.minus(Duration.ofMinutes(70)),
                now.minus(Duration.ofMinutes(52)), null, "AND-7F3K", "conv-014"), "mbarga");
        add(new Mission("m-016", "MIS-2026-016", "Audit d'infrastructure — Dschang", "Bafoussam, agence", "Dschang",
                "OU 1180 PL", "Hyundai Tucson", "SUV", "Claire Ngono", MissionStatus.DRAFT, now.plus(Duration.ofDays(1)),
                null, null, null, null), "ngono");
        add(new Mission("m-009", "MIS-2026-009", "Dépannage relais — Obala", "Yaoundé, Poste centrale", "Obala",
                "CE 7302 RT", "Isuzu D-Max", "Pick-up", "Félix Kamga", MissionStatus.FINISHED, now.minus(Duration.ofDays(6)),
                now.minus(Duration.ofDays(6)), now.minus(Duration.ofDays(6)).plus(Duration.ofMinutes(91)), "AND-DEMO", null), "conducteur");

        conv("conv-015", Chat.Kind.MISSION, "MIS-2026-015", "Yaoundé → Obala", Set.of("gestionnaire", "conducteur"));
        conv("conv-014", Chat.Kind.MISSION, "MIS-2026-014", "Yaoundé → Mbalmayo", Set.of("gestionnaire", "mbarga", "controleur"));
        conv("conv-atelier", Chat.Kind.SERVICE, "Atelier · BK 7280 LT", "Toyota Prado en entretien",
                Set.of("gestionnaire", "maintenance", "controleur"));
        say("conv-015", "gestionnaire", now.minus(Duration.ofMinutes(40)), "Félix, la mission d'Obala est autorisée. Tu peux démarrer quand tu es prêt.");
        say("conv-015", "conducteur", now.minus(Duration.ofMinutes(35)), "Merci, je pars vers 10 h après le plein.");
        say("conv-014", "gestionnaire", now.minus(Duration.ofMinutes(58)), "Bonjour Alain, mission autorisée. Merci de confirmer le départ.");
        say("conv-014", "mbarga", now.minus(Duration.ofMinutes(53)), "Départ confirmé, compteur 61 204 km.");
        say("conv-014", "controleur", now.minus(Duration.ofMinutes(31)), "Le site de Mbalmayo est prévenu de votre arrivée.");
        say("conv-atelier", "maintenance", now.minus(Duration.ofHours(20)), "Le Prado BK 7280 LT est sur le pont : plaquettes et disques avant à changer.");
        say("conv-atelier", "gestionnaire", now.minus(Duration.ofHours(19)), "D'accord. Délai estimé ?");
        say("conv-atelier", "maintenance", now.minus(Duration.ofHours(3)), "Pièces reçues ce matin. Restitution prévue demain 16 h.");
        chat.ackRead("conv-015", "gestionnaire", 2);
        chat.ackRead("conv-014", "mbarga", 3);
        chatClock.release();
    }

    private void add(Mission m, String driverLogin) {
        missions.add(m);
        drivers.put(m.id, driverLogin);
    }

    private void conv(String id, Chat.Kind kind, String title, String subtitle, Set<String> members) {
        chat.createConversation(id, members);
        meta.put(id, new String[]{kind.name(), title, subtitle});
    }

    private void say(String conv, String who, Instant at, String body) {
        chatClock.set(at);
        chat.post(conv, who, "seed-" + conv + "-" + chat.lastSeq(conv), body);
    }

    @Override
    public List<Mission> missions() {
        Session s = require();
        boolean all = s.can(Permission.MISSION_MANAGE) || s.can(Permission.GPS_READ);
        if (!all && !s.can(Permission.MISSION_DRIVE)) {
            throw new RepositoryException(RepositoryException.Kind.FORBIDDEN, "Les missions ne font pas partie de votre profil.");
        }
        List<Mission> out = new ArrayList<>();
        for (Mission m : missions) if (all || s.login.equals(drivers.get(m.id))) out.add(m);
        out.sort(Comparator.comparingInt((Mission m) -> order(m.status)).thenComparing(m -> m.plannedStart, Comparator.reverseOrder()));
        return out;
    }

    private static int order(MissionStatus s) {
        switch (s) {
            case ACTIVE: return 0;
            case AUTHORIZED: return 1;
            case DRAFT: return 2;
            default: return 3;
        }
    }

    private Mission find(String id) {
        for (Mission m : missions) if (m.id.equals(id)) return m;
        throw new RepositoryException(RepositoryException.Kind.NOT_FOUND, "Mission introuvable.");
    }

    @Override
    public StartResult start(String missionId, String noticeVersion, String deviceId) {
        Session s = require();
        Mission m = find(missionId);
        if (!s.can(Permission.MISSION_DRIVE) || !s.login.equals(drivers.get(missionId))) {
            throw new RepositoryException(RepositoryException.Kind.FORBIDDEN, "Seul le conducteur affecté peut démarrer cette mission.");
        }
        if (m.status != MissionStatus.AUTHORIZED) {
            throw new RepositoryException(RepositoryException.Kind.CONFLICT, "Cette mission n'est pas autorisée ou a déjà démarré.");
        }
        if (!"NOTICE-GPS-v1".equals(noticeVersion)) {
            throw new RepositoryException(RepositoryException.Kind.VALIDATION, "Notice de localisation non reconnue.");
        }
        Mission started = m.with(MissionStatus.ACTIVE, clock.instant(), null, deviceId);
        missions.replaceAll(x -> x.id.equals(missionId) ? started : x);
        return new StartResult(started, "demo", null);
    }

    @Override
    public Mission finish(String missionId) {
        Session s = require();
        Mission m = find(missionId);
        if (!(s.login.equals(drivers.get(missionId)) || s.can(Permission.MISSION_MANAGE))) {
            throw new RepositoryException(RepositoryException.Kind.FORBIDDEN, "Vous n'avez pas les droits nécessaires.");
        }
        if (m.status != MissionStatus.ACTIVE) {
            throw new RepositoryException(RepositoryException.Kind.CONFLICT, "Cette mission n'est pas en cours.");
        }
        Mission done = m.with(MissionStatus.FINISHED, m.startedAt, clock.instant(), m.deviceId);
        missions.replaceAll(x -> x.id.equals(missionId) ? done : x);
        return done;
    }

    // ------------------------------------------------------------------ messagerie

    private Session requireChat() {
        Session s = require();
        if (!s.can(Permission.CHAT_USE)) {
            throw new RepositoryException(RepositoryException.Kind.FORBIDDEN, "La messagerie n'est pas ouverte à votre profil.");
        }
        return s;
    }

    @Override
    public List<Conversation> conversations() {
        Session s = requireChat();
        List<Conversation> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : meta.entrySet()) {
            String id = e.getKey();
            Map<String, Chat.Cursor> cursors;
            try {
                cursors = chat.cursors(id, s.login);
            } catch (Chat.AccessDenied notMember) {
                continue;
            }
            List<Chat.Message> all = chat.after(id, s.login, 0, 10_000);
            Chat.Message last = all.isEmpty() ? null : all.get(all.size() - 1);
            Chat.Cursor mine = cursors.get(s.login);
            long unread = all.size() - (mine == null ? 0 : mine.read());
            out.add(new Conversation(id, Chat.Kind.valueOf(e.getValue()[0]), e.getValue()[1], e.getValue()[2],
                    last == null ? null : last.body(), last == null ? null : last.createdAt(), Math.max(0, unread)));
        }
        out.sort((a, b) -> b.lastAt == null ? -1 : a.lastAt == null ? 1 : b.lastAt.compareTo(a.lastAt));
        return out;
    }

    @Override
    public List<ChatLine> messages(String conversationId) {
        Session s = requireChat();
        try {
            Map<String, Chat.Cursor> cursors = chat.cursors(conversationId, s.login);
            List<ChatLine> out = new ArrayList<>();
            for (Chat.Message m : chat.after(conversationId, s.login, 0, 10_000)) out.add(line(s, m, cursors));
            return out;
        } catch (Chat.AccessDenied e) {
            throw new RepositoryException(RepositoryException.Kind.FORBIDDEN, e.getMessage());
        }
    }

    @Override
    public ChatLine send(String conversationId, String clientMessageId, String body) {
        Session s = requireChat();
        try {
            Chat.PostResult r = chat.post(conversationId, s.login, clientMessageId, body);
            return line(s, r.message(), chat.cursors(conversationId, s.login));
        } catch (Chat.AccessDenied e) {
            throw new RepositoryException(RepositoryException.Kind.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new RepositoryException(RepositoryException.Kind.VALIDATION, e.getMessage());
        }
    }

    @Override
    public void markRead(String conversationId) {
        Session s = requireChat();
        long last = chat.lastSeq(conversationId);
        if (last > 0) chat.ackRead(conversationId, s.login, last);
    }

    private ChatLine line(Session s, Chat.Message m, Map<String, Chat.Cursor> cursors) {
        boolean mine = m.senderId().equals(s.login);
        String[] acc = ACCOUNTS.get(m.senderId());
        String name = acc != null ? acc[0] : "mbarga".equals(m.senderId()) ? "Alain Mbarga" : m.senderId();
        return new ChatLine(m.serverSeq(), name, m.body(), m.createdAt(), mine, mine ? Chat.stateFor(m, cursors) : null,
                m.clientMessageId());
    }

    /** Horloge figée pendant l'amorçage des conversations, puis alignée sur l'horloge réelle. */
    static final class MutableClock extends Clock {
        private final Clock base;
        private volatile Instant fixed;

        MutableClock(Clock base) {
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
