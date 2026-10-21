package cm.camtel.nextfleet.desktop.data;

import cm.camtel.nextfleet.desktop.data.Models.Vehicle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Données FICTIVES des écrans de production pour le mode démonstration (formation, recette visuelle) : atelier, vidanges,
 * carburant, coûts, rapports. Aucune de ces données n'existe sur un serveur ; le badge DÉMO reste affiché.
 */
final class DemoProduction {

    private static final ZoneId DLA = ZoneId.of("Africa/Douala");
    private final Clock clock;
    private final List<Vehicle> vehicles;
    final List<Prod.Category> categories = List.of(new Prod.Category("MOTEUR", "Moteur"), new Prod.Category("FREINAGE", "Freinage"),
            new Prod.Category("PNEUMATIQUE", "Pneumatique"), new Prod.Category("VIDANGE", "Vidange"), new Prod.Category("ELECTRICITE", "Électricité"),
            new Prod.Category("TOLERIE", "Tôlerie"), new Prod.Category("AUTRE", "Autre"));
    final List<Prod.Supplier> suppliers = new CopyOnWriteArrayList<>(List.of(
            new Prod.Supplier(UUID.fromString("00000000-0000-0000-0000-00000000d001"), "ST-NLONGKAK", "Station Nlongkak (fictive)", "STATION"),
            new Prod.Supplier(UUID.fromString("00000000-0000-0000-0000-00000000d002"), "ST-BASTOS", "Station Bastos (fictive)", "STATION"),
            new Prod.Supplier(UUID.fromString("00000000-0000-0000-0000-00000000d003"), "GARAGE-CENTRAL", "Atelier central (fictif)", "GARAGE")));
    final List<Prod.WorkOrder> orders = new CopyOnWriteArrayList<>();
    final List<Prod.OilChange> oils = new CopyOnWriteArrayList<>();
    final List<Prod.FuelOrder> fuelOrders = new CopyOnWriteArrayList<>();
    final List<Prod.FuelFill> fills = new CopyOnWriteArrayList<>();

    DemoProduction(Clock clock, List<Vehicle> vehicles) {
        this.clock = clock;
        this.vehicles = vehicles;
        seed();
    }

    private Vehicle v(int i) {
        return vehicles.get(Math.min(i, vehicles.size() - 1));
    }

    private Instant ago(long hours) {
        return clock.instant().minus(Duration.ofHours(hours));
    }

    private static BigDecimal d(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }

    private void seed() {
        if (vehicles.isEmpty()) return;
        orders.add(work(v(0), "FREINAGE", "Freinage", "Bruit au freinage à l'avant", Prod.WorkStatus.DEMANDE, null, null, null, null, 5, "Paul Nkodo (fictif)"));
        orders.add(work(v(1), "MOTEUR", "Moteur", "Voyant moteur allumé, perte de puissance", Prod.WorkStatus.EN_COURS, "Atelier central (fictif)",
                d(84210), null, null, 30, "Aline Mvondo (fictif)"));
        orders.add(work(v(2), "PNEUMATIQUE", "Pneumatique", "Remplacement des deux pneus arrière", Prod.WorkStatus.PLANIFIEE, "Atelier central (fictif)",
                null, null, null, 52, "Aline Mvondo (fictif)"));
        orders.add(work(v(3), "ELECTRICITE", "Électricité", "Phare gauche hors service", Prod.WorkStatus.CLOTUREE, "Atelier central (fictif)",
                d(61340), BigDecimal.valueOf(18500), "Ampoule H4 et connecteur remplacés", 120, "Paul Nkodo (fictif)"));
        orders.add(work(v(4), "FREINAGE", "Freinage", "Plaquettes avant usées", Prod.WorkStatus.CLOTUREE, "Atelier central (fictif)",
                d(97400), BigDecimal.valueOf(85000), "Plaquettes et disques avant remplacés", 260, "Aline Mvondo (fictif)"));
        LocalDate today = LocalDate.now(clock.withZone(DLA));
        oils.add(new Prod.OilChange(UUID.randomUUID(), v(0).id(), v(0).registration(), today.minusDays(40), d(58200), "15W40",
                List.of("Filtre à huile", "Filtre à air"), 5000, 6, d(63200), today.minusDays(40).plusMonths(6), BigDecimal.valueOf(45000),
                "Atelier central (fictif)", "Maintenance (fictif)"));
        oils.add(new Prod.OilChange(UUID.randomUUID(), v(2).id(), v(2).registration(), today.minusDays(150), d(112400), "10W40",
                List.of("Filtre à huile"), 7500, 6, d(119900), today.minusDays(150).plusMonths(6), BigDecimal.valueOf(52000),
                "Atelier central (fictif)", "Maintenance (fictif)"));
        Prod.Supplier st = suppliers.get(0);
        fuelOrders.add(new Prod.FuelOrder(UUID.randomUUID(), "BON-" + today.toString().replace("-", "") + "-0003", v(1).id(), v(1).registration(),
                st.name(), "Félix Kamga (fictif)", null, BigDecimal.valueOf(60), BigDecimal.valueOf(50400), BigDecimal.ZERO, BigDecimal.ZERO,
                today.plusDays(7), Prod.FuelOrderStatus.SUBMITTED, "Mission Yaoundé → Obala", ago(2), false, 1));
        fuelOrders.add(new Prod.FuelOrder(UUID.randomUUID(), "BON-" + today.minusDays(2).toString().replace("-", "") + "-0001", v(0).id(),
                v(0).registration(), st.name(), "Paul Nkodo (fictif)", "Contrôleur (fictif)", BigDecimal.valueOf(70), BigDecimal.valueOf(58800),
                BigDecimal.valueOf(52), BigDecimal.valueOf(43680), today.plusDays(5), Prod.FuelOrderStatus.PARTIALLY_CONSUMED,
                "Tournée des sites", ago(50), false, 3));
        String[] regs = {v(0).registration(), v(3).registration(), v(4).registration(), v(1).registration()};
        double[][] f = {{52, 840, 58710}, {45.5, 840, 61380}, {61, 845, 97650}, {38, 840, 84100}};
        for (int i = 0; i < f.length; i++) {
            String reg = regs[f.length - 1 - i];
            Vehicle veh = vehicles.stream().filter(x -> x.registration().equals(reg)).findFirst().orElse(v(0));
            BigDecimal liters = BigDecimal.valueOf(f[i][0]);
            BigDecimal price = BigDecimal.valueOf(f[i][1]);
            fills.add(new Prod.FuelFill(UUID.randomUUID(), i == 0 ? fuelOrders.get(1).orderNumber() : null, veh.id(), veh.registration(),
                    "Félix Kamga (fictif)", st.name(), ago(6 + i * 30L), d(f[i][2]), liters, price,
                    liters.multiply(price).setScale(0, RoundingMode.HALF_UP), true, "T-" + (1040 + i), null,
                    i == 0 ? null : "Plein sur bon papier (reprise de l'ancien système)", "Conducteur (fictif)"));
        }
    }

    private Prod.WorkOrder work(Vehicle v, String code, String label, String title, Prod.WorkStatus st, String garage, BigDecimal km,
                                BigDecimal cost, String done, long hoursAgo, String by) {
        Instant created = ago(hoursAgo);
        Instant from = st == Prod.WorkStatus.EN_COURS || st == Prod.WorkStatus.CLOTUREE ? created.plus(Duration.ofHours(2)) : null;
        Instant to = st == Prod.WorkStatus.CLOTUREE ? created.plus(Duration.ofHours(26)) : null;
        return new Prod.WorkOrder(UUID.randomUUID(), v.id(), v.registration(), v.label(), code, label, title, st, garage, from, to, km, cost, done,
                null, st == Prod.WorkStatus.CLOTUREE ? "Maintenance (fictif)" : null, by, created, 1);
    }

    Prod.WorkOrder request(UUID vehicleId, String category, String title, String by) {
        Vehicle v = vehicles.stream().filter(x -> x.id().equals(vehicleId)).findFirst().orElseThrow();
        Prod.Category c = categories.stream().filter(x -> x.code().equals(category)).findFirst().orElse(categories.get(categories.size() - 1));
        Prod.WorkOrder o = work(v, c.code(), c.label(), title, Prod.WorkStatus.DEMANDE, null, null, null, null, 0, by);
        orders.add(0, o);
        return o;
    }

    Prod.WorkOrder transition(UUID id, long version, Prod.WorkTransition t, String by) {
        Prod.WorkOrder o = orders.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new FleetGateway.GatewayException(FleetGateway.GatewayException.Kind.NOT_FOUND, "Intervention introuvable."));
        if (o.version() != version) throw new FleetGateway.GatewayException(FleetGateway.GatewayException.Kind.CONFLICT, "Version obsolète : rechargez.");
        if (!o.status().next().contains(t.status())) {
            throw new FleetGateway.GatewayException(FleetGateway.GatewayException.Kind.VALIDATION, "Passage non permis.");
        }
        if (t.status() == Prod.WorkStatus.CLOTUREE && (t.odometerKm() == null || t.costXaf() == null || t.workDone() == null || t.workDone().isBlank())) {
            throw new FleetGateway.GatewayException(FleetGateway.GatewayException.Kind.VALIDATION,
                    "Clôture impossible, il manque : kilométrage, coût ou travaux réalisés.");
        }
        Instant now = clock.instant();
        Prod.WorkOrder n = new Prod.WorkOrder(o.id(), o.vehicleId(), o.registration(), o.vehicleLabel(), o.categoryCode(), o.categoryLabel(), o.title(),
                t.status(), t.garageLabel() != null ? t.garageLabel() : o.garageLabel(),
                t.status() == Prod.WorkStatus.EN_COURS ? now : o.immobilisedFrom(),
                t.status() == Prod.WorkStatus.CLOTUREE || t.status() == Prod.WorkStatus.ANNULEE ? now : o.immobilisedTo(),
                t.odometerKm() != null ? t.odometerKm() : o.odometerKm(), t.costXaf() != null ? t.costXaf() : o.costXaf(),
                t.workDone() != null && !t.workDone().isBlank() ? t.workDone() : o.workDone(), t.proofFileId(),
                t.status() == Prod.WorkStatus.CLOTUREE ? by : o.technicalVisaBy(), o.requestedBy(), o.createdAt(), o.version() + 1);
        orders.replaceAll(x -> x.id().equals(id) ? n : x);
        return n;
    }

    Prod.CostSummary costs(int year) {
        BigDecimal[] fuel = new BigDecimal[13], work = new BigDecimal[13], oil = new BigDecimal[13], lit = new BigDecimal[13];
        for (int m = 1; m <= 12; m++) {
            fuel[m] = BigDecimal.ZERO;
            work[m] = BigDecimal.ZERO;
            oil[m] = BigDecimal.ZERO;
            lit[m] = BigDecimal.ZERO;
        }
        LocalDate today = LocalDate.now(clock.withZone(DLA));
        if (year == today.getYear()) {
            // historique fictif cohérent : dépenses mensuelles jusqu'au mois courant
            for (int m = 1; m <= today.getMonthValue(); m++) {
                fuel[m] = BigDecimal.valueOf(380_000 + (m * 37_000L) % 120_000);
                lit[m] = fuel[m].divide(BigDecimal.valueOf(840), 0, RoundingMode.HALF_UP);
                work[m] = BigDecimal.valueOf(m % 3 == 0 ? 210_000 : 95_000 + (m * 13_000L) % 60_000);
                oil[m] = BigDecimal.valueOf(m % 2 == 0 ? 45_000 : 0);
            }
        }
        List<Prod.CostMonth> months = new ArrayList<>();
        BigDecimal tf = BigDecimal.ZERO, tw = BigDecimal.ZERO, to = BigDecimal.ZERO, tl = BigDecimal.ZERO;
        for (int m = 1; m <= 12; m++) {
            months.add(new Prod.CostMonth(m, fuel[m], work[m], oil[m], fuel[m].add(work[m]).add(oil[m]), lit[m]));
            tf = tf.add(fuel[m]);
            tw = tw.add(work[m]);
            to = to.add(oil[m]);
            tl = tl.add(lit[m]);
        }
        BigDecimal budget = year == today.getYear() ? BigDecimal.valueOf(9_600_000) : BigDecimal.ZERO;
        List<Prod.CostCategory> cats = new ArrayList<>();
        if (tf.signum() > 0) {
            cats.add(new Prod.CostCategory("CARBURANT", "Carburant", tf, BigDecimal.ZERO, null));
            cats.add(cat("FREINAGE", "Freinage", tw.multiply(BigDecimal.valueOf(0.35)), BigDecimal.valueOf(900_000)));
            cats.add(cat("MOTEUR", "Moteur", tw.multiply(BigDecimal.valueOf(0.4)), BigDecimal.valueOf(1_100_000)));
            cats.add(cat("PNEUMATIQUE", "Pneumatique", tw.multiply(BigDecimal.valueOf(0.25)), BigDecimal.valueOf(500_000)));
            cats.add(cat("VIDANGE", "Vidange", to, BigDecimal.valueOf(400_000)));
        }
        List<Prod.CostVehicle> vs = new ArrayList<>();
        if (tf.signum() > 0) {
            for (int i = 0; i < Math.min(vehicles.size(), 6); i++) {
                Vehicle v = vehicles.get(i);
                BigDecimal share = BigDecimal.valueOf(0.28 - i * 0.035);
                BigDecimal fv = tf.multiply(share).setScale(0, RoundingMode.HALF_UP);
                BigDecimal wv = tw.add(to).multiply(share).setScale(0, RoundingMode.HALF_UP);
                vs.add(new Prod.CostVehicle(v.id(), v.registration(), v.label(), fv, wv, fv.add(wv), fv.divide(BigDecimal.valueOf(840), 0, RoundingMode.HALF_UP),
                        3 - i / 2));
            }
        }
        return new Prod.CostSummary(year, tf.add(tw).add(to), tf, tw, to, budget, tl, months, cats, vs);
    }

    private static Prod.CostCategory cat(String code, String label, BigDecimal actual, BigDecimal budget) {
        BigDecimal a = actual.setScale(0, RoundingMode.HALF_UP);
        return new Prod.CostCategory(code, label, a, budget, a.multiply(BigDecimal.valueOf(100)).divide(budget, 1, RoundingMode.HALF_UP));
    }

    static final List<Prod.ReportInfo> REPORTS = List.of(
            new Prod.ReportInfo("vehicules", "Parc automobile", "Véhicules actifs : identité, statut, entité, conducteur attitré, kilométrage.", false),
            new Prod.ReportInfo("conducteurs", "Conducteurs", "Conducteurs actifs, permis et CNI avec leurs échéances.", false),
            new Prod.ReportInfo("pleins", "Pleins de carburant", "Pleins de la période : bon, station, litres, prix, compteur, reçu.", true),
            new Prod.ReportInfo("bons", "Bons de carburant", "Bons émis sur la période : demandeur, valideur, autorisé et consommé.", true),
            new Prod.ReportInfo("interventions", "Interventions d'atelier", "Ordres de la période : catégorie, statut, immobilisation, coût, visa.", true),
            new Prod.ReportInfo("missions", "Missions", "Missions de la période : objet, trajet prévu, conducteur, dates réelles.", true),
            new Prod.ReportInfo("couts-vehicules", "Coûts par véhicule", "Carburant, interventions clôturées et vidanges hors ordre, par véhicule.", true),
            new Prod.ReportInfo("echeances", "Échéances", "Documents véhicules, permis et CNI expirés ou à échéance sous 90 jours.", false),
            new Prod.ReportInfo("anomalies", "Écarts à vérifier", "Écarts détectés sur la période et leur statut d'instruction.", true));

    byte[] report(String code) {
        StringBuilder sb = new StringBuilder("﻿");
        if ("pleins".equals(code)) {
            sb.append("Date;Véhicule;Station;Litres;Montant (XAF);Reçu\r\n");
            for (Prod.FuelFill f : fills) {
                sb.append(f.filledAt().atZone(DLA).toLocalDate()).append(';').append(f.registration()).append(';').append(f.supplierName()).append(';')
                        .append(f.liters().toPlainString().replace('.', ',')).append(';').append(f.amountXaf().toPlainString()).append(';')
                        .append(f.receiptReference()).append("\r\n");
            }
        } else {
            sb.append("Immatriculation;Marque;Modèle;Statut\r\n");
            for (Vehicle v : vehicles) sb.append(v.registration()).append(';').append(v.make()).append(';').append(v.model()).append(';')
                    .append(v.status().label).append("\r\n");
        }
        sb.append("Données fictives (mode démonstration)\r\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
