package cm.camtel.nextfleet.desktop;

import cm.camtel.nextfleet.desktop.data.DemoFleetGateway;
import cm.camtel.nextfleet.desktop.data.FleetGateway.GatewayException;
import cm.camtel.nextfleet.desktop.data.HttpFleetGateway;
import cm.camtel.nextfleet.desktop.data.Models.DashboardData;
import cm.camtel.nextfleet.desktop.data.Models.VehicleDraft;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;
import cm.camtel.nextfleet.desktop.ui.VehicleFormValidator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DesktopLogicTest {

    private static DemoFleetGateway demo() {
        return new DemoFleetGateway(Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC), Duration.ZERO);
    }

    @Test
    @DisplayName("Démo : identifiants invalides → message générique ; session exigée avant toute lecture")
    void demoLogin() {
        DemoFleetGateway g = demo();
        GatewayException e = assertThrows(GatewayException.class, () -> g.vehicles(null, null, 0, 10));
        assertEquals(GatewayException.Kind.UNAUTHORIZED, e.kind());
        GatewayException bad = assertThrows(GatewayException.class, () -> g.login("gestionnaire", "faux".toCharArray()));
        assertEquals("Identifiants invalides ou compte indisponible.", bad.getMessage());
        assertTrue(g.login("gestionnaire", "demo".toCharArray()).demo());
    }

    @Test
    @DisplayName("Démo : recherche par immatriculation normalisée et filtre de statut")
    void demoSearch() {
        DemoFleetGateway g = demo();
        g.login("gestionnaire", "demo".toCharArray());
        assertEquals(1, g.vehicles("ce-4401", null, 0, 10).total());
        assertEquals(12, g.vehicles("", null, 0, 10).total());
        assertEquals(10, g.vehicles("", null, 0, 10).items().size());
        assertEquals(2, g.vehicles("", null, 1, 10).items().size());
        assertTrue(g.vehicles(null, VehicleStatus.ON_MISSION, 0, 50).items().stream()
                .allMatch(v -> v.status() == VehicleStatus.ON_MISSION));
    }

    @Test
    @DisplayName("Démo : doublon d'immatriculation refusé ; l'auditeur ne peut pas créer")
    void demoCreate() {
        DemoFleetGateway g = demo();
        g.login("gestionnaire", "demo".toCharArray());
        VehicleDraft dup = new VehicleDraft("ce 4401-lt", null, "Toyota", "Hilux", 2022, "DIESEL", null, null);
        assertEquals(GatewayException.Kind.CONFLICT, assertThrows(GatewayException.class, () -> g.createVehicle(dup)).kind());
        VehicleDraft ok = new VehicleDraft("CE 1000 ZZ", null, "Toyota", "Hilux", 2025, "DIESEL", null, null);
        assertEquals(VehicleStatus.AVAILABLE, g.createVehicle(ok).status());
        DemoFleetGateway g2 = demo();
        g2.login("auditeur", "demo".toCharArray());
        assertEquals(GatewayException.Kind.FORBIDDEN, assertThrows(GatewayException.class, () -> g2.createVehicle(ok)).kind());
    }

    @Test
    @DisplayName("Démo : la consommation du tableau de bord est calculée plein-à-plein ; mois sans relevé fiable signalé")
    void demoDashboard() {
        DemoFleetGateway g = demo();
        g.login("gestionnaire", "demo".toCharArray());
        DashboardData d = g.dashboard();
        assertEquals(12, d.totalVehicles());
        assertEquals(6, d.consumptionSeries().size());
        assertNotNull(d.avgConsumption());
        assertTrue(d.avgConsumption().doubleValue() > 7 && d.avgConsumption().doubleValue() < 11, "valeur " + d.avgConsumption());
        assertTrue(d.consumptionSeries().stream().anyMatch(m -> !"Fiable".equals(m.reliability())),
                "le relevé manquant fictif doit être signalé, pas lissé");
    }

    @Test
    @DisplayName("Démo : coupure réseau simulée → erreur NETWORK explicite")
    void demoNetwork() {
        DemoFleetGateway g = demo();
        g.login("gestionnaire", "demo".toCharArray());
        g.setNetworkDown(true);
        assertEquals(GatewayException.Kind.NETWORK, assertThrows(GatewayException.class, () -> g.dashboard()).kind());
    }

    @Test
    @DisplayName("NF-11 : échéances unifiées — niveaux cohérents, tri par date, tableau de bord alimenté par la même source")
    void dueDates() {
        DemoFleetGateway g = demo();
        g.login("gestionnaire", "demo".toCharArray());
        var all = g.dueDates(30);
        assertFalse(all.isEmpty());
        for (int i = 1; i < all.size(); i++) assertFalse(all.get(i).dueOn().isBefore(all.get(i - 1).dueOn()), "tri par date");
        for (var d : all) {
            var expected = d.daysLeft() < 0 ? cm.camtel.nextfleet.desktop.data.Models.DueLevel.EXPIRE
                    : d.daysLeft() <= 30 ? cm.camtel.nextfleet.desktop.data.Models.DueLevel.A_ECHEANCE
                    : cm.camtel.nextfleet.desktop.data.Models.DueLevel.A_JOUR;
            assertEquals(expected, d.level(), d.toString());
        }
        // Serge Fone : permis expiré depuis 6 jours dans le jeu fictif
        assertTrue(all.stream().anyMatch(d -> d.subject().equals("Serge Fone")
                && d.kind() == cm.camtel.nextfleet.desktop.data.Models.DueKind.PERMIS && d.daysLeft() == -6));
        var v = g.vehicles("CE 9021", null, 0, 1).items().get(0);
        var vd = g.vehicleDueDates(v.id());
        assertEquals(3, vd.size());
        assertTrue(all.containsAll(vd), "la fiche et l'écran Échéances affichent les mêmes échéances");
    }

    @Test
    @DisplayName("NF-12 : conducteurs — recherche par nom ou matricule, initiales")
    void drivers() {
        DemoFleetGateway g = demo();
        g.login("gestionnaire", "demo".toCharArray());
        assertEquals(11, g.drivers("").size());
        assertEquals(1, g.drivers("mat-0412").size());
        assertEquals("JN", g.drivers("nguema").get(0).initials());
        assertEquals("ÉO", g.drivers("ondoa").get(0).initials());
    }

    @Test
    @DisplayName("NF-24 : notifications — titres courts, corps en phrase complète, lecture mémorisée, tri récent d'abord")
    void notifications() {
        DemoFleetGateway g = demo();
        g.login("gestionnaire", "demo".toCharArray());
        var n = g.notifications();
        assertFalse(n.isEmpty());
        for (int i = 1; i < n.size(); i++) assertFalse(n.get(i).at().isAfter(n.get(i - 1).at()), "plus récent d'abord");
        for (var x : n) {
            assertFalse(x.title().endsWith("."), "HIG : pas de ponctuation finale au titre — " + x.title());
            assertTrue(x.body().endsWith("."), "HIG : corps en phrase complète — " + x.body());
        }
        assertTrue(n.stream().noneMatch(x -> x.read()));
        g.markNotificationsRead(java.util.List.of(n.get(0).id()));
        assertTrue(g.notifications().get(0).read());
    }

    @Test
    @DisplayName("RG-04 : archivage — droits, version attendue, retrait de la liste active")
    void archive() {
        DemoFleetGateway g = demo();
        g.login("gestionnaire", "demo".toCharArray());
        var v = g.vehicles("BK 7280", null, 0, 1).items().get(0);
        assertEquals(GatewayException.Kind.CONFLICT,
                assertThrows(GatewayException.class, () -> g.archiveVehicle(v.id(), v.version() + 5, "test")).kind());
        var a = g.archiveVehicle(v.id(), v.version(), "Archivage de test");
        assertEquals(VehicleStatus.ARCHIVED, a.status());
        assertEquals(11, g.vehicles("", null, 0, 50).total(), "un véhicule archivé ne figure plus au parc actif");
        assertEquals(1, g.vehicles("", VehicleStatus.ARCHIVED, 0, 50).total(), "mais il reste consultable");
        DemoFleetGateway g2 = demo();
        g2.login("auditeur", "demo".toCharArray());
        var v2 = g2.vehicles("", null, 0, 1).items().get(0);
        assertEquals(GatewayException.Kind.FORBIDDEN,
                assertThrows(GatewayException.class, () -> g2.archiveVehicle(v2.id(), v2.version(), "x")).kind());
    }

    @Test
    @DisplayName("Formulaire 04 : champs obligatoires, année, capacité et VIN validés")
    void formValidation() {
        VehicleFormValidator v = new VehicleFormValidator();
        var r = v.validate("CE-4401 lt", "", "Toyota", "", "1890", "DIESEL", "abc", "", Year.of(2026));
        assertFalse(r.ok());
        assertEquals(java.util.Set.of("model", "year", "tank"), r.errors().keySet());
        var bad = v.validate(" - ", "IOQ123", "T", "H", "", "DIESEL", "", "", Year.of(2026));
        assertTrue(bad.errors().containsKey("registration"));
        assertTrue(bad.errors().containsKey("vin"));
        var ok = v.validate("CE 1000 ZZ", "jtebu3fj005012345", "Toyota", "Hilux", "2027", "DIESEL", "80,5", "Pick-up", Year.of(2026));
        assertTrue(ok.ok(), ok.errors().toString());
        assertEquals("JTEBU3FJ005012345", ok.draft().vin());
        assertEquals(new java.math.BigDecimal("80.5"), ok.draft().tankCapacityLiters());
    }

    @Test
    @DisplayName("Client HTTP : HTTPS imposé hors localhost ; jeton transmis ; 403 traduit en FORBIDDEN")
    void httpGateway() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new HttpFleetGateway(URI.create("http://serveur.camtel.invalid/")));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/api/v1/auth/login", ex -> reply(ex, 200,
                "{\"token\":\"tok-123456789012345678901\",\"expiresAt\":\"2026-10-20T16:00:00Z\","
                        + "\"user\":{\"login\":\"g\",\"displayName\":\"Gestionnaire\",\"roles\":[{\"role\":\"FLEET_MANAGER\"}]}}"));
        server.createContext("/api/v1/vehicles", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (ex.getRequestURI().getPath().endsWith("/vehicles")) {
                reply(ex, 200, "{\"items\":[{\"id\":\"00000000-0000-0000-0000-0000000000b1\",\"registration\":\"CE 4401 LT\","
                        + "\"make\":\"Toyota\",\"model\":\"Hilux\",\"status\":\"IN_SERVICE\",\"version\":3}],\"page\":0,\"size\":10,\"total\":1}");
            } else {
                reply(ex, 403, "{\"status\":403,\"code\":\"ACCESS_DENIED\",\"detail\":\"Vous n'avez pas les droits nécessaires pour cette action.\"}");
            }
        });
        server.start();
        try {
            HttpFleetGateway g = new HttpFleetGateway(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            assertTrue(g.login("g", "x".toCharArray()).has("FLEET_MANAGER"));
            var page = g.vehicles("ce 4401", null, 0, 10);
            assertEquals("Bearer tok-123456789012345678901", auth.get());
            assertEquals(1, page.total());
            assertEquals(VehicleStatus.IN_SERVICE, page.items().get(0).status());
            assertNull(page.items().get(0).fuelLevelPercent(), "valeur absente ≠ 0 (RG-07)");
            GatewayException e = assertThrows(GatewayException.class,
                    () -> g.vehicle(java.util.UUID.fromString("00000000-0000-0000-0000-0000000000b2")));
            assertEquals(GatewayException.Kind.FORBIDDEN, e.kind());
            assertTrue(e.getMessage().startsWith("Vous n'avez pas les droits"));
        } finally {
            server.stop(0);
        }
        HttpFleetGateway down = new HttpFleetGateway(URI.create("http://127.0.0.1:9/"));
        assertEquals(GatewayException.Kind.NETWORK,
                assertThrows(GatewayException.class, () -> down.login("g", "x".toCharArray())).kind());
    }

    @Test
    @DisplayName("Client HTTP lot 2 : missions, messagerie idempotente, assistant, journal vérifié par le serveur")
    void httpGatewayLot2() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> posted = new AtomicReference<>();
        server.createContext("/api/v1/auth/login", ex -> reply(ex, 200,
                "{\"token\":\"tok-123456789012345678901\",\"user\":{\"login\":\"g\",\"displayName\":\"G\",\"roles\":[{\"role\":\"FLEET_MANAGER\"}]}}"));
        server.createContext("/api/v1/missions", ex -> reply(ex, 200, "[{\"id\":\"00000000-0000-0000-0000-0000000000e1\",\"reference\":\"MIS-2026-001\","
                + "\"status\":\"ACTIVE\",\"plannedStart\":\"2026-10-20T07:00:00Z\",\"startedAt\":\"2026-10-20T07:05:00Z\",\"deviceId\":\"AND-1\","
                + "\"conversationId\":\"00000000-0000-0000-0000-0000000000f1\",\"version\":2}]"));
        server.createContext("/api/v1/conversations", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/messages") && "POST".equals(ex.getRequestMethod())) {
                posted.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                reply(ex, 201, "{\"serverSeq\":7,\"duplicate\":false,\"createdAt\":\"2026-10-20T08:00:00Z\"}");
            } else if (path.endsWith("/messages")) {
                reply(ex, 200, "[{\"serverSeq\":6,\"senderName\":\"Vous\",\"body\":\"ok\",\"createdAt\":\"2026-10-20T07:59:00Z\",\"mine\":true,\"state\":\"LU\",\"clientMessageId\":\"c6\"}]");
            } else {
                reply(ex, 200, "[{\"id\":\"00000000-0000-0000-0000-0000000000f1\",\"kind\":\"MISSION\",\"title\":\"MIS-2026-001\",\"unread\":2}]");
            }
        });
        server.createContext("/api/v1/assistant/ask", ex -> reply(ex, 200, "{\"intent\":\"COUT\",\"refused\":false,\"text\":\"Coût : 42 000 XAF.\","
                + "\"figures\":[{\"label\":\"Total\",\"value\":\"42 000 XAF\"}],\"period\":\"octobre 2026\",\"filters\":[],"
                + "\"sources\":[{\"ref\":\"fuel_fill:1\",\"description\":\"Plein\"}]}"));
        server.createContext("/api/v1/admin/audit", ex -> {
            if (ex.getRequestURI().getPath().endsWith("/verify")) reply(ex, 200, "{\"events\":2,\"firstBrokenId\":null,\"intact\":true}");
            else reply(ex, 200, "[{\"id\":2,\"occurredAt\":\"2026-10-20T08:00:00Z\",\"actor\":\"G\",\"action\":\"MISSION_STARTED\","
                    + "\"objectType\":\"mission\",\"objectId\":\"e1\",\"hash\":\"ab\",\"previousHash\":\"cd\"}]");
        });
        server.start();
        try {
            HttpFleetGateway g = new HttpFleetGateway(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            g.login("g", "x".toCharArray());
            var m = g.missions().get(0);
            assertEquals(cm.camtel.nextfleet.desktop.data.Ops.MissionStatus.ACTIVE, m.status());
            assertEquals("AND-1", m.deviceId());
            assertEquals(GatewayException.Kind.VALIDATION, assertThrows(GatewayException.class,
                    () -> g.startMission(m.id(), "NOTICE-GPS-v1")).kind(), "le bureau n'est pas un émetteur GPS");
            assertEquals(2, g.conversations().get(0).unread());
            assertEquals(cm.camtel.nextfleet.domain.chat.Chat.DeliveryState.LU, g.messages(m.conversationId()).get(0).state());
            assertEquals(7, g.sendMessage(m.conversationId(), "cid-1", "Bien reçu").seq());
            assertTrue(posted.get().contains("\"clientMessageId\":\"cid-1\""));
            var a = g.askAssistant("Combien a coûté CE 4401 LT ?");
            assertEquals("42 000 XAF", a.figures().get(0).value());
            assertEquals("fuel_fill:1", a.sources().get(0).ref());
            var trail = g.auditTrail();
            assertEquals(1, trail.size());
            assertEquals(-1, g.auditFirstBroken(trail), "intégrité décidée par le serveur sur tout le journal");
        } finally {
            server.stop(0);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
