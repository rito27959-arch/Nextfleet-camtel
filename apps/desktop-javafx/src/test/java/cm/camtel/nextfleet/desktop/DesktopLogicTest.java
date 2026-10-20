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

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
