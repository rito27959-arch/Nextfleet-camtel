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

    private void oil(Vehicle v, int daysAgo, double kmBefore, String label, List<String> filters, Integer ik, Integer im, double cost) {
        LocalDate on = LocalDate.now(clock.withZone(DLA)).minusDays(daysAgo);
        BigDecimal km = v.odometerKm() == null ? d(0) : d(v.odometerKm().doubleValue() - kmBefore);
        oils.add(new Prod.OilChange(UUID.randomUUID(), v.id(), v.registration(), on, km, label, filters, ik, im,
                ik == null ? null : km.add(BigDecimal.valueOf(ik)), im == null ? null : on.plusMonths(im), BigDecimal.valueOf(cost),
                "Atelier central (fictif)", "Maintenance (fictif)", v.direction()));
    }

    /** Même règle que le serveur : en retard, à prévoir (≤ 1 000 km ou ≤ 30 jours), jamais, sans intervalle, à jour. */
    List<Prod.OilStatus> oilStatus(String direction) {
        LocalDate today = LocalDate.now(clock.withZone(DLA));
        List<Prod.OilStatus> out = new ArrayList<>();
        for (Vehicle v : vehicles) {
            if (v.status() == Models.VehicleStatus.ARCHIVED) continue;
            if (direction != null && !direction.equals(v.direction())) continue;
            Prod.OilChange last = oils.stream().filter(o -> o.vehicleId().equals(v.id()))
                    .max(java.util.Comparator.comparing(Prod.OilChange::doneOn)).orElse(null);
            BigDecimal cur = v.odometerKm();
            BigDecimal rest = last == null || last.nextDueKm() == null || cur == null ? null : last.nextDueKm().subtract(cur);
            Long days = last == null || last.nextDueOn() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(today, last.nextDueOn());
            Prod.OilState st;
            if (last == null) st = Prod.OilState.AUCUNE;
            else if ((rest != null && rest.signum() < 0) || (days != null && days < 0)) st = Prod.OilState.EN_RETARD;
            else if ((rest != null && rest.compareTo(BigDecimal.valueOf(1000)) <= 0) || (days != null && days <= 30)) st = Prod.OilState.A_PREVOIR;
            else if (last.nextDueKm() == null && last.nextDueOn() == null) st = Prod.OilState.SANS_INTERVALLE;
            else st = Prod.OilState.A_JOUR;
            out.add(new Prod.OilStatus(v.id(), v.registration(), v.label(), v.direction(), v.siteLabel(), cur,
                    last == null ? null : last.doneOn(), last == null ? null : last.odometerKm(), last == null ? null : last.oilLabel(),
                    last == null ? null : last.nextDueKm(), last == null ? null : last.nextDueOn(), rest, days, st));
        }
        out.sort(java.util.Comparator.comparingInt((Prod.OilStatus o) -> o.state().ordinal())
                .thenComparing(o -> o.remainingKm() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : o.remainingKm()));
        return out;
    }

    Prod.OilChange addOil(Prod.OilChangeDraft d, String author) {
        Vehicle v = vehicles.stream().filter(x -> x.id().equals(d.vehicleId())).findFirst().orElseThrow();
        List<String> f = new ArrayList<>();
        if (d.oilFilter()) f.add("Filtre à huile");
        if (d.fuelFilter()) f.add("Filtre à carburant");
        if (d.airFilter()) f.add("Filtre à air");
        Prod.OilChange o = new Prod.OilChange(UUID.randomUUID(), v.id(), v.registration(), d.doneOn(), d.odometerKm(), d.oilLabel(), f,
                d.intervalKm(), d.intervalMonths(), d.intervalKm() == null ? null : d.odometerKm().add(BigDecimal.valueOf(d.intervalKm())),
                d.intervalMonths() == null ? null : d.doneOn().plusMonths(d.intervalMonths()), d.costXaf(), d.garageLabel(), author, v.direction());
        oils.add(0, o);
        return o;
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
        // vidanges cohérentes avec le compteur de chaque véhicule : à prévoir, en retard (date, km), à jour, sans intervalle
        oil(v(0), 220, 9300, "15W40", List.of("Filtre à huile"), 5000, 6, 42000);
        oil(v(0), 40, 4300, "15W40", List.of("Filtre à huile", "Filtre à air"), 5000, 6, 45000);
        oil(v(2), 200, 6400, "10W40", List.of("Filtre à huile"), 7500, 6, 52000);
        oil(v(3), 20, 1300, "15W40", List.of("Filtre à huile", "Filtre à carburant"), 5000, 6, 47500);
        oil(v(5), 60, 2100, "5W30", List.of("Filtre à huile", "Filtre à air", "Liquide de refroidissement"), 10000, 12, 68000);
        oil(v(6), 10, 700, "15W40", List.of(), null, null, 39000);
        oil(v(7), 90, 6500, "15W40", List.of("Filtre à huile"), 5000, 6, 45000);
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
            new Prod.ReportInfo("vehicules", "Parc automobile", "Véhicules actifs : identité, statut, direction, affectation, conducteur attitré, kilométrage.", false, true),
            new Prod.ReportInfo("conducteurs", "Conducteurs", "Conducteurs actifs, permis et CNI avec leurs échéances.", false, false),
            new Prod.ReportInfo("pleins", "Pleins de carburant", "Pleins de la période : bon, station, litres, prix, compteur, reçu.", true, true),
            new Prod.ReportInfo("bons", "Bons de carburant", "Bons émis sur la période : demandeur, valideur, autorisé et consommé.", true, true),
            new Prod.ReportInfo("interventions", "Interventions d'atelier", "Ordres de la période : catégorie, statut, immobilisation, coût, visa.", true, true),
            new Prod.ReportInfo("vidanges", "Vidanges", "Vidanges de la période : compteur, huile, éléments remplacés, prochaine échéance, coût et garage.", true, true),
            new Prod.ReportInfo("etat-vidanges", "État des vidanges", "Pour chaque véhicule : dernière vidange, prochaine échéance (km et date), kilomètres restants et état.", false, true),
            new Prod.ReportInfo("missions", "Missions", "Missions de la période : objet, trajet prévu, conducteur, dates réelles.", true, true),
            new Prod.ReportInfo("couts-vehicules", "Coûts par véhicule", "Carburant, interventions clôturées et vidanges hors ordre, par véhicule.", true, true),
            new Prod.ReportInfo("echeances", "Échéances", "Documents véhicules, permis et CNI expirés ou à échéance sous 90 jours.", false, true),
            new Prod.ReportInfo("anomalies", "Écarts à vérifier", "Écarts détectés sur la période et leur statut d'instruction.", true, true));

    byte[] report(String code) {
        return report(code, null, null, null, null, "csv", "Utilisateur de démonstration");
    }

    /** Rapport fictif en CSV ou en PDF (même mise en page que le serveur), filtré sur une direction si demandée. */
    byte[] report(String code, LocalDate from, LocalDate to, String direction, UUID vehicleId, String format, String author) {
        LocalDate today = LocalDate.now(clock.withZone(DLA));
        LocalDate f = from == null ? today.withDayOfMonth(1) : from, t = to == null ? today : to;
        java.util.function.Predicate<String> inDir = d -> direction == null || direction.equals(d);
        Vehicle only = vehicleId == null ? null : vehicles.stream().filter(v -> v.id().equals(vehicleId)).findFirst().orElse(null);
        java.util.function.Predicate<String> inVeh = reg -> only == null || only.registration().equals(reg);
        java.util.function.Function<UUID, Vehicle> byId = id -> vehicles.stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
        List<String> labels;
        List<Object[]> rows = new ArrayList<>();
        switch (code) {
            case "pleins" -> {
                labels = List.of("Date", "Véhicule", "Direction", "Station", "Litres", "Montant (XAF)", "Reçu");
                for (Prod.FuelFill x : fills) {
                    Vehicle v = byId.apply(x.vehicleId());
                    LocalDate on = x.filledAt().atZone(DLA).toLocalDate();
                    if (v == null || !inDir.test(v.direction()) || !inVeh.test(v.registration()) || on.isBefore(f) || on.isAfter(t)) continue;
                    rows.add(new Object[]{on, x.registration(), v.direction(), x.supplierName(), x.liters(), x.amountXaf(), x.receiptReference()});
                }
            }
            case "vidanges" -> {
                labels = List.of("Date", "Véhicule", "Direction", "Compteur (km)", "Huile", "Éléments remplacés", "Prochaine (km)", "Prochaine (date)",
                        "Coût (XAF)", "Garage");
                for (Prod.OilChange o : oils.stream().sorted(java.util.Comparator.comparing(Prod.OilChange::doneOn)).toList()) {
                    if (!inDir.test(o.direction()) || !inVeh.test(o.registration()) || o.doneOn().isBefore(f) || o.doneOn().isAfter(t)) continue;
                    rows.add(new Object[]{o.doneOn(), o.registration(), o.direction(), o.odometerKm(), o.oilLabel(), String.join(", ", o.filters()).toLowerCase(),
                            o.nextDueKm(), o.nextDueOn(), o.costXaf(), o.garageLabel()});
                }
            }
            case "etat-vidanges" -> {
                labels = List.of("Véhicule", "Modèle", "Direction", "Affectation", "Compteur actuel (km)", "Dernière vidange", "Prochaine (km)",
                        "Reste (km)", "Prochaine (date)", "État");
                for (Prod.OilStatus o : oilStatus(direction)) if (inVeh.test(o.registration())) rows.add(new Object[]{o.registration(), o.model(), o.direction(), o.siteLabel(),
                        o.currentKm(), o.lastDoneOn(), o.nextDueKm(), o.remainingKm(), o.nextDueOn(), o.state().label});
            }
            default -> {
                labels = List.of("Immatriculation", "Marque", "Modèle", "Statut", "Direction", "Affectation", "Kilométrage");
                for (Vehicle v : vehicles) {
                    if (v.status() == Models.VehicleStatus.ARCHIVED || !inDir.test(v.direction()) || !inVeh.test(v.registration())) continue;
                    rows.add(new Object[]{v.registration(), v.make(), v.model(), v.status().label, v.direction(), v.siteLabel(), v.odometerKm()});
                }
            }
        }
        java.time.format.DateTimeFormatter day = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        if ("pdf".equals(format)) {
            List<cm.camtel.nextfleet.domain.report.PdfReport.Column> cols = new ArrayList<>();
            BigDecimal[] sums = new BigDecimal[labels.size()];
            for (int c = 0; c < labels.size(); c++) {
                String l = labels.get(c);
                final int col = c;
                boolean num = rows.stream().anyMatch(r -> r[col] instanceof BigDecimal);
                boolean total = num && ((l.contains("(XAF)") && !l.startsWith("Prix")) || l.startsWith("Litres"));
                if (total) sums[c] = BigDecimal.ZERO;
                cols.add(new cm.camtel.nextfleet.domain.report.PdfReport.Column(l, num, total));
            }
            List<List<String>> lines = new ArrayList<>();
            for (Object[] r : rows) {
                List<String> line = new ArrayList<>();
                for (int c = 0; c < r.length; c++) {
                    Object v = r[c];
                    if (sums[c] != null && v instanceof BigDecimal b) sums[c] = sums[c].add(b);
                    line.add(v == null ? "" : v instanceof LocalDate d ? day.format(d) : v instanceof BigDecimal b ? pdfNumber(b) : v.toString());
                }
                lines.add(line);
            }
            List<String> totals = new ArrayList<>();
            for (BigDecimal b : sums) totals.add(b == null ? null : pdfNumber(b));
            Prod.ReportInfo info = REPORTS.stream().filter(r -> r.code().equals(code)).findFirst().orElse(REPORTS.get(0));
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(clock.withZone(DLA));
            List<String> ctx = List.of((info.period() ? "Période : du " + day.format(f) + " au " + day.format(t) : "Situation au " + day.format(today))
                            + "   ·   " + (only != null ? "Véhicule : " + only.registration() : direction == null ? "Toutes les directions" : "Direction : " + direction)
                            + "   ·   " + rows.size()
                            + (rows.size() > 1 ? " lignes" : " ligne"),
                    "Édité le " + day.format(now) + " à " + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(now) + " par " + author
                            + " — DONNÉES FICTIVES (mode démonstration)");
            return cm.camtel.nextfleet.domain.report.PdfReport.render(new cm.camtel.nextfleet.domain.report.PdfReport.Meta(info.title(), info.description(),
                    ctx, "NexFleet — mode démonstration : données fictives, aucune valeur réelle."), cols, lines, totals);
        }
        StringBuilder sb = new StringBuilder("\uFEFF").append(String.join(";", labels)).append("\r\n");
        for (Object[] r : rows) {
            for (int c = 0; c < r.length; c++) {
                Object v = r[c];
                sb.append(c > 0 ? ";" : "").append(v == null ? "" : v instanceof LocalDate d ? day.format(d)
                        : v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString().replace('.', ',') : v.toString().replace(';', ','));
            }
            sb.append("\r\n");
        }
        sb.append("Données fictives (mode démonstration)\r\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String pdfNumber(BigDecimal b) {
        String plain = b.setScale(Math.min(Math.max(b.stripTrailingZeros().scale(), 0), 2), RoundingMode.HALF_UP).toPlainString();
        boolean neg = plain.startsWith("-");
        if (neg) plain = plain.substring(1);
        int dot = plain.indexOf('.');
        String ip = dot < 0 ? plain : plain.substring(0, dot), dp = dot < 0 ? "" : "," + plain.substring(dot + 1);
        if (ip.length() > 4) {
            StringBuilder g = new StringBuilder();
            for (int i = 0; i < ip.length(); i++) {
                if (i > 0 && (ip.length() - i) % 3 == 0) g.append('\u00A0');
                g.append(ip.charAt(i));
            }
            ip = g.toString();
        }
        return (neg ? "-" : "") + ip + dp;
    }
}
