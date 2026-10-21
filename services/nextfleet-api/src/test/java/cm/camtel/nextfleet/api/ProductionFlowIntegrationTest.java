package cm.camtel.nextfleet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mise en production de bout en bout sur une instance VIERGE (environnement PREPROD, aucune donnée de démonstration) :
 * l'administrateur d'amorçage crée l'organisation et les comptes ; chaque profil change son mot de passe provisoire puis
 * travaille avec ses seuls droits — parc, conducteurs, documents et échéances, atelier jusqu'à la clôture visée, vidange,
 * contrôle, pneus, carburant avec reçu, tableau de bord, coûts et budget, rapports CSV, messagerie avec pièce jointe,
 * annulation de mission, réinitialisation de mot de passe. Toutes les requêtes SQL nouvelles sont exercées sur PostGIS réel.
 */
@SpringBootTest(properties = {"nextfleet.environment=PREPROD", "nextfleet.bootstrap.enabled=true",
        "nextfleet.bootstrap.admin-login=admin", "nextfleet.bootstrap.admin-password=Admin-Initial-2026!"})
@AutoConfigureMockMvc
@Testcontainers
class ProductionFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withInitScript("test-extensions.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};
    static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
    static final String NEW_PASSWORD = "Camtel-Flotte-2026!";

    private String login(String login, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("login", login, "password", password, "clientKind", "DESKTOP"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder b, String token, String json) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (json != null) b.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(b);
    }

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String upload(String token, String purpose, String org, String name, byte[] content, int expected) throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", name, "application/octet-stream", content);
        var b = multipart("/api/v1/files").file(f).param("purpose", purpose);
        if (org != null) b.param("orgUnitId", org);
        ResultActions r = mvc.perform(b.header("Authorization", "Bearer " + token)).andExpect(status().is(expected));
        return expected == 201 ? json(r).get("id").asText() : null;
    }

    /** Première connexion avec le mot de passe provisoire : tout est refusé tant qu'il n'est pas remplacé. */
    private String firstLogin(String login, String temp) throws Exception {
        String t = login(login, temp);
        call(get("/api/v1/me"), t, null).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        call(get("/api/v1/vehicles"), t, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        call(post("/api/v1/me/password"), t, mapper.writeValueAsString(Map.of("currentPassword", temp, "newPassword", "court")))
                .andExpect(status().isBadRequest());
        call(post("/api/v1/me/password"), t, mapper.writeValueAsString(Map.of("currentPassword", temp, "newPassword", NEW_PASSWORD)))
                .andExpect(status().isNoContent());
        call(get("/api/v1/me"), t, null).andExpect(jsonPath("$.mustChangePassword").value(false));
        return t;
    }

    @Test
    @DisplayName("Instance vierge → administration → comptes → parc, atelier, carburant, coûts, rapports, messagerie")
    void productionFlow() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Douala"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM vehicle", Integer.class), "aucune donnée fictive en PREPROD");

        // ------------------------------------------------------------ administration
        String admin = login("admin", "Admin-Initial-2026!");
        String adminId = json(call(get("/api/v1/me"), admin, null)).get("id").asText();
        assertEquals(6, json(call(get("/api/v1/admin/roles"), admin, null).andExpect(status().isOk())).size());
        String org = json(call(post("/api/v1/admin/org-units"), admin, """
                {"code":"DR-CENTRE","name":"Direction régionale du Centre"}""").andExpect(status().isCreated())).get("id").asText();
        call(post("/api/v1/admin/users/" + adminId + "/grants"), admin, """
                {"role":"FLEET_MANAGER","scopeOrgId":"%s"}""".formatted(org)).andExpect(status().isBadRequest());   // NF-02

        Map<String, String> temp = new HashMap<>();
        Map<String, String> ids = new HashMap<>();
        for (String[] u : new String[][]{{"gestionnaire.centre", "FLEET_MANAGER"}, {"chef.parc.centre", "FLEET_MANAGER"},
                {"controleur.centre", "CONTROLLER"}, {"atelier.centre", "MAINTENANCE"}, {"auditeur.centre", "AUDITOR"}}) {
            JsonNode c = json(call(post("/api/v1/admin/users"), admin, """
                    {"login":"%s","displayName":"%s","orgUnitId":"%s","roles":[{"role":"%s","scopeOrgId":"%s"}]}"""
                    .formatted(u[0], u[0], org, u[1], org)).andExpect(status().isCreated()));
            temp.put(u[0], c.get("temporaryPassword").asText());
            ids.put(u[0], c.get("user").get("id").asText());
            assertTrue(c.get("temporaryPassword").asText().length() >= 16);
        }
        call(post("/api/v1/admin/users"), admin, """
                {"login":"gestionnaire.centre","displayName":"Doublon","orgUnitId":"%s"}""".formatted(org)).andExpect(status().isBadRequest());

        String mgr = firstLogin("gestionnaire.centre", temp.get("gestionnaire.centre"));
        String chef = firstLogin("chef.parc.centre", temp.get("chef.parc.centre"));
        String ctl = firstLogin("controleur.centre", temp.get("controleur.centre"));
        String shop = firstLogin("atelier.centre", temp.get("atelier.centre"));
        String aud = firstLogin("auditeur.centre", temp.get("auditeur.centre"));

        // ------------------------------------------------------------ parc et conducteurs
        assertTrue(json(call(get("/api/v1/org-units?permission=vehicle:write"), mgr, null)).toString().contains(org));
        JsonNode v = json(call(post("/api/v1/vehicles"), mgr, """
                {"registration":"LT 1234 CE","make":"Toyota","model":"Hilux","modelYear":2022,"energy":"DIESEL","tankCapacityLiters":80,
                 "tankCapacityVerified":true,"orgUnitId":"%s","category":"Pick-up","siteLabel":"Siège régional","odometerKm":12000}""".formatted(org))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.category").value("Pick-up")));
        String veh = v.get("id").asText();
        call(get("/api/v1/vehicles/" + veh), mgr, null).andExpect(jsonPath("$.odometerKm").value(12000.0))
                .andExpect(jsonPath("$.orgUnitName").value("Direction régionale du Centre"));
        call(post("/api/v1/vehicles"), shop, """
                {"registration":"LT 9999 CE","make":"X","model":"Y","orgUnitId":"%s"}""".formatted(org)).andExpect(status().isForbidden());

        String drv = json(call(post("/api/v1/drivers"), mgr, """
                {"staffNumber":"MAT-2041","fullName":"Félix Kamga","phone":"+237 6 99 00 00 00","orgUnitId":"%s",
                 "idCardNumber":"CNI-1","idCardExpiresOn":"%s"}""".formatted(org, today.plusYears(3))).andExpect(status().isCreated())).get("id").asText();
        call(post("/api/v1/drivers/" + drv + "/licenses"), mgr, """
                {"licenseNumber":"ce-12345","categories":["B","C"],"expiresOn":"%s"}""".formatted(today.plusDays(20)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.licenseNumber").value("CE-12345"));
        call(post("/api/v1/vehicles/" + veh + "/assignment"), mgr, """
                {"driverId":"%s","reason":"Affectation initiale"}""".formatted(drv)).andExpect(status().isNoContent());
        call(post("/api/v1/vehicles/" + veh + "/assignment"), mgr, """
                {"driverId":"%s","reason":"Réaffectation le même jour"}""".formatted(drv)).andExpect(status().isNoContent());
        call(get("/api/v1/vehicles/" + veh), mgr, null).andExpect(jsonPath("$.driverName").value("Félix Kamga"));
        call(get("/api/v1/drivers?q=kamga"), mgr, null).andExpect(jsonPath("$[0].assignedVehicle").value("LT 1234 CE"));
        JsonNode d1 = json(call(get("/api/v1/drivers/" + drv), mgr, null));
        call(patch("/api/v1/drivers/" + drv).header("If-Match", "\"" + d1.get("version").asLong() + "\""), mgr, """
                {"phone":"+237 6 77 00 00 00","reason":"Nouveau numéro de service"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+237 6 77 00 00 00"));

        // compte conducteur lié à sa fiche
        JsonNode cd = json(call(post("/api/v1/admin/users"), admin, """
                {"login":"felix.kamga","displayName":"Félix Kamga","orgUnitId":"%s","driverId":"%s","roles":[{"role":"DRIVER"}]}"""
                .formatted(org, drv)).andExpect(status().isCreated()));
        String driver = firstLogin("felix.kamga", cd.get("temporaryPassword").asText());

        // ------------------------------------------------------------ documents, échéances, notifications
        upload(mgr, "VEHICLE_DOCUMENT", org, "virus.exe", EXE, 400);
        String pdf = upload(mgr, "VEHICLE_DOCUMENT", org, "attestation", PDF, 201);
        call(get("/api/v1/files/" + pdf), mgr, null).andExpect(jsonPath("$.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.originalName").value("attestation.pdf"));
        call(post("/api/v1/vehicles/" + veh + "/documents"), mgr, """
                {"docType":"ASSURANCE","reference":"POL-778","validFrom":"%s","validUntil":"%s","fileId":"%s"}"""
                .formatted(today.minusYears(1), today.plusDays(10), pdf)).andExpect(status().isCreated());
        JsonNode dues = json(call(get("/api/v1/due-dates?days=60"), mgr, null).andExpect(status().isOk()));
        assertTrue(dues.toString().contains("\"kind\":\"ASSURANCE\"") && dues.toString().contains("\"kind\":\"PERMIS\""), dues.toString());
        assertTrue(dues.toString().contains("A_ECHEANCE"));
        JsonNode notes = json(call(get("/api/v1/notifications"), mgr, null).andExpect(status().isOk()));
        assertTrue(notes.size() >= 2, notes.toString());
        String firstNote = notes.get(0).get("id").asText();
        call(post("/api/v1/notifications/read"), mgr, mapper.writeValueAsString(Map.of("ids", java.util.List.of(firstNote))))
                .andExpect(status().isNoContent());
        assertTrue(json(call(get("/api/v1/notifications"), mgr, null)).get(0).get("read").asBoolean()
                || json(call(get("/api/v1/notifications"), mgr, null)).toString().contains("\"read\":true"));

        // ------------------------------------------------------------ atelier
        JsonNode mo = json(call(post("/api/v1/maintenance-orders"), driver, """
                {"vehicleId":"%s","categoryCode":"FREINAGE","title":"Bruit au freinage"}""".formatted(veh))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DEMANDE")));
        String moId = mo.get("id").asText();
        assertTrue(json(call(get("/api/v1/notifications"), shop, null)).toString().contains("ENTRETIEN"));
        long ver = mo.get("version").asLong();
        call(post("/api/v1/maintenance-orders/" + moId + "/transition").header("If-Match", "\"" + ver + "\""), shop,
                "{\"status\":\"CLOTUREE\"}").andExpect(status().isBadRequest());           // saut d'étape refusé
        ver = json(call(post("/api/v1/maintenance-orders/" + moId + "/transition").header("If-Match", "\"" + ver + "\""), shop,
                "{\"status\":\"QUALIFIEE\",\"garageLabel\":\"Atelier central\"}").andExpect(status().isOk())).get("version").asLong();
        call(post("/api/v1/maintenance-orders/" + moId + "/transition").header("If-Match", "\"0\""), shop,
                "{\"status\":\"EN_COURS\"}").andExpect(status().isConflict());              // version obsolète
        ver = json(call(post("/api/v1/maintenance-orders/" + moId + "/transition").header("If-Match", "\"" + ver + "\""), shop,
                "{\"status\":\"EN_COURS\"}").andExpect(status().isOk())).get("version").asLong();
        call(get("/api/v1/vehicles/" + veh), mgr, null).andExpect(jsonPath("$.status").value("MAINTENANCE"));
        call(post("/api/v1/maintenance-orders/" + moId + "/transition").header("If-Match", "\"" + ver + "\""), shop,
                "{\"status\":\"CLOTUREE\",\"odometerKm\":12500,\"costXaf\":85000,\"workDone\":\"Plaquettes AV remplacées\"}")
                .andExpect(status().isBadRequest());                                        // justificatif manquant
        String proof = upload(shop, "MAINTENANCE", org, "facture.png", PNG, 201);
        call(post("/api/v1/maintenance-orders/" + moId + "/transition").header("If-Match", "\"" + ver + "\""), shop, """
                {"status":"CLOTUREE","odometerKm":12500,"costXaf":85000,"workDone":"Plaquettes AV remplacées","proofFileId":"%s"}"""
                .formatted(proof)).andExpect(status().isOk()).andExpect(jsonPath("$.technicalVisaBy").value("atelier.centre"));
        call(get("/api/v1/vehicles/" + veh), mgr, null).andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.odometerKm").value(12500.0));
        call(get("/api/v1/files/" + proof + "/content"), mgr, null).andExpect(status().isOk());     // gestionnaire : lecture du parc
        call(get("/api/v1/files/" + proof + "/content"), driver, null).andExpect(status().isOk());  // conducteur : vehicle:read

        call(post("/api/v1/oil-changes"), shop, """
                {"vehicleId":"%s","doneOn":"%s","odometerKm":12600,"oilLabel":"15W40","oilLiters":7,"oilFilter":true,
                 "intervalKm":5000,"intervalMonths":6,"costXaf":45000,"garageLabel":"Atelier central"}""".formatted(veh, today))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nextDueKm").value(17600.0))
                .andExpect(jsonPath("$.filters[0]").value("Filtre à huile"));
        assertEquals(12, json(call(get("/api/v1/inspection-points"), driver, null)).size());
        call(post("/api/v1/inspections"), driver, """
                {"vehicleId":"%s","kind":"AVANT_MISSION","odometerKm":12650,"items":[
                  {"pointCode":"FREINS","state":"DEFAUT","critical":true,"comment":"Pédale molle"},
                  {"pointCode":"ECLAIRAGE","state":"OK","critical":false}]}""".formatted(veh))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.result").value("NON_CONFORME"));
        assertEquals(1, json(call(get("/api/v1/maintenance-orders?vehicleId=" + veh + "&open=true"), shop, null)).size(),
                "le défaut critique ouvre une demande d'intervention");
        call(post("/api/v1/tire-events"), shop, """
                {"vehicleId":"%s","kind":"MONTAGE","size":"265/65 R17","brand":"Michelin","position":"AVG","condition":"NEUF"}""".formatted(veh))
                .andExpect(status().isCreated()).andExpect(jsonPath("$[0].position").value("AVG"));

        // ------------------------------------------------------------ carburant
        String station = json(call(post("/api/v1/suppliers"), mgr, """
                {"code":"STATION-NLONGKAK","name":"Station Nlongkak","kind":"STATION"}""").andExpect(status().isCreated())).get("id").asText();
        String order = json(call(post("/api/v1/fuel-orders"), driver, """
                {"vehicleId":"%s","supplierId":"%s","authorizedLiters":60,"authorizedAmountXaf":51000,"validUntil":"%s","reason":"Mission Obala"}"""
                .formatted(veh, station, today.plusDays(7))).andExpect(status().isCreated())).get("id").asText();
        call(post("/api/v1/fuel-orders/" + order + "/submit"), driver, null).andExpect(status().isOk());
        assertTrue(json(call(get("/api/v1/notifications"), ctl, null)).toString().contains("CARBURANT"));
        call(post("/api/v1/fuel-orders/" + order + "/approve"), ctl, "{}").andExpect(status().isOk());
        String receipt = upload(driver, "FUEL_RECEIPT", org, "recu.jpg", JPEG, 201);
        Instant t1 = Instant.now().minus(2, ChronoUnit.DAYS);
        call(post("/api/v1/fuel-fills").header("Idempotency-Key", "fill-0001-" + UUID.randomUUID()), driver, """
                {"fuelOrderId":"%s","vehicleId":"%s","driverId":"%s","supplierId":"%s","filledAt":"%s","odometerKm":12700,
                 "liters":50,"unitPriceXaf":840,"fullTank":true,"receiptReference":"T-001","receiptFileId":"%s"}"""
                .formatted(order, veh, drv, station, t1, receipt)).andExpect(status().isCreated());
        call(post("/api/v1/fuel-fills").header("Idempotency-Key", "fill-0002-" + UUID.randomUUID()), driver, """
                {"exceptionReason":"Bon épuisé, plein autorisé par téléphone","vehicleId":"%s","driverId":"%s","supplierId":"%s",
                 "filledAt":"%s","odometerKm":13200,"liters":40,"unitPriceXaf":840,"fullTank":true,"receiptReference":"T-002"}"""
                .formatted(veh, drv, station, Instant.now().minus(1, ChronoUnit.HOURS))).andExpect(status().isCreated());
        call(get("/api/v1/fuel-orders"), mgr, null).andExpect(jsonPath("$[0].registration").value("LT 1234 CE"))
                .andExpect(jsonPath("$[0].status").exists());
        assertEquals(2, json(call(get("/api/v1/fuel-fills?vehicleId=" + veh), mgr, null)).size());
        call(get("/api/v1/files/" + receipt + "/content"), ctl, null).andExpect(status().isOk());   // valideur
        call(get("/api/v1/files/" + receipt + "/content"), shop, null).andExpect(status().isForbidden());

        // ------------------------------------------------------------ tableau de bord, coûts, budget
        JsonNode dash = json(call(get("/api/v1/dashboard"), mgr, null).andExpect(status().isOk()));
        assertEquals(1, dash.get("totalVehicles").asInt());
        assertEquals(1, dash.get("activeVehicles").asInt());
        assertEquals(8.0, dash.get("avgConsumption").asDouble(), 0.01, dash.toString());   // 40 L / 500 km
        assertTrue(dash.get("monthCostXaf").asDouble() > 0);
        assertEquals(6, dash.get("consumptionSeries").size());

        String csv = "categorie;activite;tache;t1;t2;t3;t4\nFREINAGE;Entretien;Plaquettes et disques;100 000;0;0;0\nINCONNUE;x;y;1;1;1;1\n";
        call(post("/api/v1/budget-lines/import?year=" + today.getYear() + "&orgUnitId=" + org + "&sourceFile=budget.csv")
                .contentType(MediaType.TEXT_PLAIN).content(csv), mgr, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(1)).andExpect(jsonPath("$.errors.length()").value(1));
        call(post("/api/v1/budget-lines/import?year=" + today.getYear() + "&orgUnitId=" + org + "&sourceFile=budget.csv")
                .contentType(MediaType.TEXT_PLAIN).content(csv), mgr, null)
                .andExpect(jsonPath("$.inserted").value(0)).andExpect(jsonPath("$.duplicates").value(1));     // NF-26
        JsonNode costs = json(call(get("/api/v1/costs?year=" + today.getYear()), ctl, null).andExpect(status().isOk()));
        assertEquals(75600.0, costs.get("fuelXaf").asDouble(), 0.01);
        assertEquals(85000.0, costs.get("maintenanceXaf").asDouble(), 0.01);
        assertEquals(45000.0, costs.get("oilXaf").asDouble(), 0.01);
        assertEquals(100000.0, costs.get("budgetXaf").asDouble(), 0.01);
        assertTrue(costs.get("categories").toString().contains("FREINAGE"));
        call(get("/api/v1/costs"), driver, null).andExpect(status().isForbidden());

        // ------------------------------------------------------------ rapports
        assertTrue(json(call(get("/api/v1/reports"), aud, null)).size() >= 8);
        String pleins = call(get("/api/v1/reports/pleins.csv?from=" + today.minusDays(7) + "&to=" + today), aud, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(pleins.startsWith("﻿") && pleins.contains("LT 1234 CE") && pleins.contains("Station Nlongkak"), pleins);
        String parc = call(get("/api/v1/reports/vehicules.csv"), mgr, null).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(parc.contains("Félix Kamga") && parc.contains("12600"), parc);
        call(get("/api/v1/reports/vehicules.csv"), driver, null).andExpect(status().isForbidden());

        // ------------------------------------------------------------ messagerie
        JsonNode contacts = json(call(get("/api/v1/conversations/contacts"), mgr, null).andExpect(status().isOk()));
        assertTrue(contacts.toString().contains("Félix Kamga"));
        String felixId = cd.get("user").get("id").asText();
        String conv = json(call(post("/api/v1/conversations"), mgr, mapper.writeValueAsString(Map.of("participantIds", java.util.List.of(felixId))))
                .andExpect(status().isCreated())).get("id").asText();
        assertEquals(conv, json(call(post("/api/v1/conversations"), mgr, mapper.writeValueAsString(Map.of("participantIds", java.util.List.of(felixId)))))
                .get("id").asText(), "la conversation directe est réutilisée");
        String photo = upload(mgr, "CHAT", null, "plan.png", PNG, 201);
        call(post("/api/v1/conversations/" + conv + "/messages"), mgr, """
                {"clientMessageId":"msg-001","body":"Voici le plan d'accès au site.","attachmentIds":["%s"]}""".formatted(photo))
                .andExpect(status().isCreated());
        call(post("/api/v1/conversations/" + conv + "/messages"), driver, """
                {"clientMessageId":"msg-002","body":"Je réutilise la pièce d'un autre","attachmentIds":["%s"]}""".formatted(photo))
                .andExpect(status().is4xxClientError());
        JsonNode msgs = json(call(get("/api/v1/conversations/" + conv + "/messages"), driver, null).andExpect(status().isOk()));
        assertEquals("plan.png", msgs.get(0).get("attachments").get(0).get("name").asText());
        call(get("/api/v1/files/" + photo + "/content"), driver, null).andExpect(status().isOk());
        call(get("/api/v1/files/" + photo + "/content"), aud, null).andExpect(status().isForbidden());

        // ------------------------------------------------------------ missions : annulation motivée
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        String mission = json(call(post("/api/v1/missions"), mgr, """
                {"vehicleId":"%s","driverId":"%s","purpose":"Relevé de compteurs","origin":"Yaoundé","destination":"Obala",
                 "plannedStart":"%s","plannedEnd":"%s"}""".formatted(veh, drv, start, start.plus(6, ChronoUnit.HOURS)))
                .andExpect(status().isCreated())).get("id").asText();
        call(post("/api/v1/missions/" + mission + "/authorize"), chef, null).andExpect(status().isOk());
        assertTrue(json(call(get("/api/v1/notifications"), driver, null)).toString().contains("Mission autorisée"));
        call(post("/api/v1/missions/" + mission + "/cancel"), chef, "{\"reason\":\"Site fermé ce jour-là\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        // ------------------------------------------------------------ écarts, journal, réinitialisation
        call(get("/api/v1/anomalies"), ctl, null).andExpect(status().isOk());
        JsonNode audit = json(call(get("/api/v1/admin/audit?limit=500"), aud, null).andExpect(status().isOk()));
        assertTrue(audit.toString().contains("\"EXPORT\"") && audit.toString().contains("\"STATUS_CLOTUREE\"")
                && audit.toString().contains("\"GRANT_ROLE\""));
        JsonNode grants = json(call(get("/api/v1/admin/users/" + ids.get("auditeur.centre") + "/grants"), admin, null));
        call(post("/api/v1/admin/users/" + ids.get("auditeur.centre") + "/grants/" + grants.get(0).get("id").asText() + "/revoke"), admin,
                "{\"reason\":\"Fin de mission d'audit\"}").andExpect(status().isNoContent());
        call(get("/api/v1/admin/audit?limit=5"), aud, null).andExpect(status().isForbidden());
        String reset = json(call(post("/api/v1/admin/users/" + ids.get("gestionnaire.centre") + "/password-reset"), admin,
                "{\"reason\":\"Oubli signalé au support\"}").andExpect(status().isOk())).get("temporaryPassword").asText();
        assertNotNull(reset);
        call(get("/api/v1/vehicles"), mgr, null).andExpect(status().isUnauthorized());     // sessions révoquées
        assertFalse(reset.equals(temp.get("gestionnaire.centre")));
    }
}
