package cm.camtel.nextfleet.desktop.data;

import cm.camtel.nextfleet.desktop.data.Models.Alert;
import cm.camtel.nextfleet.desktop.data.Models.AlertKind;
import cm.camtel.nextfleet.desktop.data.Models.DashboardData;
import cm.camtel.nextfleet.desktop.data.Models.MonthlyConsumption;
import cm.camtel.nextfleet.desktop.data.Models.Page;
import cm.camtel.nextfleet.desktop.data.Models.Session;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Models.VehicleDraft;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;
import cm.camtel.nextfleet.domain.fleet.RegistrationNumber;
import cm.camtel.nextfleet.domain.fuel.ConsumptionResult;
import cm.camtel.nextfleet.domain.fuel.FuelFillRecord;
import cm.camtel.nextfleet.domain.fuel.FullToFullConsumptionCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Passerelle de DÉMONSTRATION : données entièrement fictives, générées de façon déterministe.
 * Elle n'est jamais utilisable en production (NF-04) : l'interface affiche en permanence le badge « DÉMO ».
 * La consommation du tableau de bord est réellement calculée par la méthode plein-à-plein du noyau métier
 * sur des pleins fictifs ; les autres indicateurs sont dérivés des mêmes données.
 */
public final class DemoFleetGateway implements FleetGateway {

    public static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    private static final String DEMO_PASSWORD = "demo";

    private final Clock clock;
    private final Duration latency;
    private final List<Vehicle> vehicles = new CopyOnWriteArrayList<>();
    private final List<Models.Driver> drivers = new CopyOnWriteArrayList<>();
    private final Map<UUID, List<FuelFillRecord>> fills = new LinkedHashMap<>();
    private final Map<UUID, BigDecimal> monthCostByVehicle = new LinkedHashMap<>();
    private final Map<UUID, BigDecimal> previousMonthCostByVehicle = new LinkedHashMap<>();
    private final FullToFullConsumptionCalculator calculator = new FullToFullConsumptionCalculator();
    private volatile Session session;
    private volatile boolean networkDown;

    public DemoFleetGateway(Clock clock, Duration latency) {
        this.clock = clock;
        this.latency = latency;
        seed();
    }

    /** Simule une coupure réseau pour démontrer l'état d'erreur (recette REC-UX-01). */
    public void setNetworkDown(boolean down) {
        this.networkDown = down;
    }

    @Override
    public boolean isDemo() {
        return true;
    }

    @Override
    public Session login(String login, char[] password) {
        pause();
        String l = login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
        boolean ok = DEMO_PASSWORD.equals(new String(password));
        java.util.Arrays.fill(password, '\0');
        String[] account = DemoOperations.ACCOUNTS.get(l);
        if (!ok || account == null) {
            throw new GatewayException(GatewayException.Kind.UNAUTHORIZED, "Identifiants invalides ou compte indisponible.");
        }
        List<String> roles = List.of(account[1]);
        String name = account[0];
        session = new Session("demo-" + UUID.randomUUID(), name, l, roles, true);
        ops().record(session, "LOGIN", "Session", "Connexion au poste bureau (démo)");
        return session;
    }

    @Override
    public void logout() {
        if (session != null) ops().record(session, "LOGOUT", "Session", "Session révoquée");
        session = null;
    }

    @Override
    public DashboardData dashboard() {
        pause();
        require(cm.camtel.nextfleet.domain.security.Permission.COST_READ);
        YearMonth current = YearMonth.now(clock.withZone(DOUALA));
        List<Vehicle> vehicles = this.vehicles.stream().filter(v -> v.status() != VehicleStatus.ARCHIVED).toList();
        int active = (int) vehicles.stream().filter(v -> v.status() == VehicleStatus.IN_SERVICE
                || v.status() == VehicleStatus.ON_MISSION || v.status() == VehicleStatus.AVAILABLE).count();
        LocalDate today = LocalDate.now(clock.withZone(DOUALA));
        int upcoming = (int) vehicles.stream().filter(v -> v.nextMaintenance() != null
                && !v.nextMaintenance().isAfter(today.plusDays(30))).count();

        List<MonthlyConsumption> series = monthlySeries(current);
        BigDecimal last = series.isEmpty() ? null : series.get(series.size() - 1).litersPer100Km();
        BigDecimal prev = series.size() < 2 ? null : series.get(series.size() - 2).litersPer100Km();
        BigDecimal cost = sum(monthCostByVehicle.values());
        BigDecimal prevCost = sum(previousMonthCostByVehicle.values());

        List<Vehicle> recent = vehicles.stream().sorted(Comparator.comparing(Vehicle::registration)).limit(5).toList();
        return new DashboardData(active, vehicles.size(), upcoming, 2, last, trend(last, prev),
                "Moyenne flotte, méthode plein-à-plein (périodes fiables uniquement)", cost, trend(cost, prevCost),
                current, series, alerts(today), recent, clock.instant());
    }

    @Override
    public Page<Vehicle> vehicles(String query, VehicleStatus status, int page, int size) {
        pause();
        require(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_READ);
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String qn = q.replaceAll("[^a-z0-9]", "").toUpperCase(Locale.ROOT);
        List<Vehicle> filtered = vehicles.stream()
                .filter(v -> status == VehicleStatus.ARCHIVED ? v.status() == VehicleStatus.ARCHIVED
                        : v.status() != VehicleStatus.ARCHIVED && (status == null || v.status() == status))
                .filter(v -> q.isEmpty()
                        || (!qn.isEmpty() && RegistrationNumber.of(v.registration()).normalized().contains(qn))
                        || v.label().toLowerCase(Locale.ROOT).contains(q)
                        || (v.driverName() != null && v.driverName().toLowerCase(Locale.ROOT).contains(q)))
                .sorted(Comparator.comparing(Vehicle::registration))
                .toList();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return new Page<>(filtered.subList(from, to), page, size, filtered.size());
    }

    @Override
    public Vehicle vehicle(UUID id) {
        pause();
        require(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_READ);
        return vehicles.stream().filter(v -> v.id().equals(id)).findFirst()
                .orElseThrow(() -> new GatewayException(GatewayException.Kind.NOT_FOUND, "Véhicule introuvable."));
    }

    @Override
    public Vehicle createVehicle(VehicleDraft d) {
        pause();
        Session s = requireSession();
        if (!s.can(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_WRITE)) {
            throw new GatewayException(GatewayException.Kind.FORBIDDEN, "Vous n'avez pas les droits nécessaires pour cette action.");
        }
        RegistrationNumber reg = RegistrationNumber.of(d.registration());
        boolean exists = vehicles.stream().anyMatch(v -> RegistrationNumber.of(v.registration()).sameVehicleAs(reg));
        if (exists) {
            throw new GatewayException(GatewayException.Kind.CONFLICT, "Cette immatriculation existe déjà (y compris archivée).");
        }
        Vehicle v = new Vehicle(UUID.randomUUID(), reg.raw().toUpperCase(Locale.ROOT), d.make(), d.model(), d.modelYear(),
                d.energy(), VehicleStatus.AVAILABLE, null, null, null, null, d.tankCapacityLiters(), null, d.category(), 0);
        vehicles.add(v);
        ops().record(s, "VEHICLE_CREATED", v.registration(), v.label());
        return v;
    }

    // ------------------------------------------------------------------ données fictives

    private void seed() {
        Object[][] rows = {
                {"CE 4401 LT", "Toyota", "Hilux", 2022, VehicleStatus.IN_SERVICE, "J. Nguema", 18, "Yaoundé", 42850, 80, 12, "Pick-up"},
                {"LT 5520 CE", "Renault", "Duster", 2021, VehicleStatus.ON_MISSION, "A. Mbarga", 72, "Douala", 61200, 50, 64, "SUV"},
                {"BK 7280 LT", "Toyota", "Prado", 2020, VehicleStatus.MAINTENANCE, "S. Fone", 65, "Yaoundé", 98400, 87, 2, "SUV"},
                {"CE 1188 LT", "Peugeot", "301", 2019, VehicleStatus.IN_SERVICE, "M. Fotsing", 41, "Bafoussam", 112300, 50, 21, "Berline"},
                {"LT 7710 CE", "Mitsubishi", "L200", 2018, VehicleStatus.OUT_OF_SERVICE, "P. Tchoua", 0, "Atelier CAMTEL", 154900, 75, null, "Pick-up"},
                {"CE 3205 KJ", "Toyota", "Land Cruiser", 2023, VehicleStatus.IN_SERVICE, "R. Etoa", 55, "Yaoundé", 22100, 130, 95, "4x4"},
                {"LT 9120 AC", "Nissan", "Navara", 2021, VehicleStatus.AVAILABLE, null, 90, "Douala", 51700, 80, 40, "Pick-up"},
                {"CE 4210 MK", "Toyota", "Hiace", 2020, VehicleStatus.ON_MISSION, "L. Abena", 33, "Kribi", 133500, 70, 18, "Minibus"},
                {"OU 1180 PL", "Hyundai", "Tucson", 2022, VehicleStatus.IN_SERVICE, "C. Ngono", 60, "Bafoussam", 38800, 62, 77, "SUV"},
                {"CE 9021 BC", "Suzuki", "Jimny", 2024, VehicleStatus.AVAILABLE, null, null, "Yaoundé", 8400, 40, 150, "4x4"},
                {"LT 6603 QP", "Toyota", "Corolla", 2019, VehicleStatus.IN_SERVICE, "E. Ondoa", 47, "Douala", 99000, 50, 9, "Berline"},
                {"CE 7302 RT", "Isuzu", "D-Max", 2021, VehicleStatus.ON_MISSION, "F. Kamga", 28, "Ebolowa", 70500, 76, 33, "Pick-up"},
        };
        LocalDate today = LocalDate.now(clock.withZone(DOUALA));
        int i = 0;
        for (Object[] r : rows) {
            UUID id = UUID.nameUUIDFromBytes(("demo-" + r[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Integer daysToMaint = (Integer) r[10];
            vehicles.add(new Vehicle(id, (String) r[0], (String) r[1], (String) r[2], (Integer) r[3], "DIESEL",
                    (VehicleStatus) r[4], (String) r[5], (Integer) r[6], (String) r[7],
                    BigDecimal.valueOf((Integer) r[8]), BigDecimal.valueOf((Integer) r[9]),
                    daysToMaint == null ? null : today.plusDays(daysToMaint), (String) r[11], 1));
            seedFills(id, i++, BigDecimal.valueOf((Integer) r[8]));
        }
        String[][] people = {
                {"Jean Nguema", "MAT-0412", "CE 4401 LT", "Yaoundé", "40", "620"},
                {"Alain Mbarga", "MAT-0388", "LT 5520 CE", "Douala", "210", "15"},
                {"Serge Fone", "MAT-0275", "BK 7280 LT", "Yaoundé", "-6", "300"},
                {"Martin Fotsing", "MAT-0517", "CE 1188 LT", "Bafoussam", "18", "-30"},
                {"Paul Tchoua", "MAT-0109", "LT 7710 CE", "Douala", "400", "800"},
                {"René Etoa", "MAT-0623", "CE 3205 KJ", "Yaoundé", "95", "130"},
                {"Lucie Abena", "MAT-0731", "CE 4210 MK", "Kribi", "27", "540"},
                {"Claire Ngono", "MAT-0802", "OU 1180 PL", "Bafoussam", "365", "720"},
                {"Éric Ondoa", "MAT-0455", "LT 6603 QP", "Douala", "150", "60"},
                {"Félix Kamga", "MAT-0690", "CE 7302 RT", "Ebolowa", "12", "410"},
                {"Brice Atangana", "MAT-0911", null, "Yaoundé", "700", null},
        };
        for (String[] p : people) {
            drivers.add(new Models.Driver(UUID.nameUUIDFromBytes(("drv-" + p[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    p[0], p[1], "6 99 00 " + p[1].substring(4, 6) + " " + p[1].substring(6), "CM-" + p[1].substring(4) + "-B",
                    List.of("B", p[1].endsWith("2") || p[1].endsWith("8") ? "C" : "B1"),
                    today.plusDays(Long.parseLong(p[4])), p[5] == null ? null : today.plusDays(Long.parseLong(p[5])), p[2], p[3]));
        }
    }

    /** Pleins fictifs sur 7 mois : un plein complet toutes les ~2 semaines, parfois un partiel intermédiaire. */
    private void seedFills(UUID id, int index, BigDecimal odometerNow) {
        Instant now = clock.instant();
        List<FuelFillRecord> list = new ArrayList<>();
        int periods = (int) Math.min(15, odometerNow.longValue() / 700);   // un véhicule récent a moins d'historique
        BigDecimal km = odometerNow.subtract(BigDecimal.valueOf(periods * 600L));
        // Consommation cible légèrement décroissante dans le temps, différente par véhicule.
        double base = 9.4 + (index % 4) * 0.35;
        BigDecimal monthCost = BigDecimal.ZERO, prevMonthCost = BigDecimal.ZERO;
        YearMonth current = YearMonth.now(clock.withZone(DOUALA));
        for (int p = 0; p <= periods; p++) {
            Instant at = now.minus((long) (periods - p) * 14, ChronoUnit.DAYS).minus(index * 3L, ChronoUnit.HOURS);
            double target = base - p * 0.07;
            int distance = 520 + ((p * 37 + index * 11) % 160);
            BigDecimal liters = p == 0 ? BigDecimal.valueOf(45)
                    : BigDecimal.valueOf(target * distance / 100.0).setScale(2, RoundingMode.HALF_UP);
            if (p > 0) {
                km = km.add(BigDecimal.valueOf(distance));
                if (p % 3 == 0) {   // plein partiel intermédiaire : inclus dans la période suivante
                    BigDecimal part = liters.multiply(BigDecimal.valueOf(0.4)).setScale(2, RoundingMode.HALF_UP);
                    list.add(new FuelFillRecord(id + "-P" + p + "a", at.minus(4, ChronoUnit.DAYS),
                            km.subtract(BigDecimal.valueOf(distance / 2)), part, false));
                    liters = liters.subtract(part);
                }
            }
            // Le véhicule n°5 a un relevé manquant : la période correspondante doit être « insuffisante ».
            BigDecimal odo = (index == 4 && p == periods - 1) ? null : km;
            list.add(new FuelFillRecord(id + "-P" + p, at, odo, liters, true));
            YearMonth ym = YearMonth.from(at.atZone(DOUALA));
            BigDecimal cost = liters.multiply(BigDecimal.valueOf(840));   // prix fictif XAF/L
            if (ym.equals(current)) monthCost = monthCost.add(cost);
            if (ym.equals(current.minusMonths(1))) prevMonthCost = prevMonthCost.add(cost);
        }
        fills.put(id, list);
        // Coûts du mois : carburant fictif + entretien fictif forfaitaire par véhicule
        monthCostByVehicle.put(id, monthCost.add(BigDecimal.valueOf(index % 3 == 0 ? 210_000 : 95_000)));
        previousMonthCostByVehicle.put(id, prevMonthCost.add(BigDecimal.valueOf(index % 3 == 0 ? 260_000 : 110_000)));
    }

    /** Moyenne mensuelle des périodes plein-à-plein CALCULÉES se terminant dans le mois (6 derniers mois). */
    List<MonthlyConsumption> monthlySeries(YearMonth current) {
        Map<YearMonth, List<BigDecimal>> byMonth = new LinkedHashMap<>();
        Map<YearMonth, Integer> insufficient = new LinkedHashMap<>();
        for (int m = 5; m >= 0; m--) {
            byMonth.put(current.minusMonths(m), new ArrayList<>());
            insufficient.put(current.minusMonths(m), 0);
        }
        for (List<FuelFillRecord> list : fills.values()) {
            Map<String, Instant> at = new LinkedHashMap<>();
            list.forEach(f -> at.put(f.id(), f.filledAt()));
            for (ConsumptionResult r : calculator.allPeriods(list)) {
                if (!r.isCalculated()) {
                    continue;
                }
                YearMonth ym = YearMonth.from(at.get(r.endFillId()).atZone(DOUALA));
                if (byMonth.containsKey(ym)) byMonth.get(ym).add(r.litersPer100Km());
            }
            // périodes insuffisantes : comptées pour la note de qualité
            List<ConsumptionResult> all = calculator.allPeriods(list);
            for (ConsumptionResult r : all) {
                if (r.isCalculated()) continue;
                list.stream().filter(f -> f.odometerKm() == null).findFirst().ifPresent(f -> {
                    YearMonth ym = YearMonth.from(f.filledAt().atZone(DOUALA));
                    insufficient.computeIfPresent(ym, (k, v) -> v + 1);
                });
            }
        }
        List<MonthlyConsumption> out = new ArrayList<>();
        byMonth.forEach((ym, values) -> {
            if (values.isEmpty()) {
                out.add(new MonthlyConsumption(ym, null, "Données insuffisantes"));
            } else {
                BigDecimal avg = sum(values).divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
                int ins = insufficient.get(ym);
                out.add(new MonthlyConsumption(ym, avg, ins > 0 ? ins + " période(s) exclue(s) faute de relevé" : "Fiable"));
            }
        });
        return out;
    }

    private List<Alert> alerts(LocalDate today) {
        List<Alert> out = new ArrayList<>();
        vehicles.stream().filter(v -> v.nextMaintenance() != null && !v.nextMaintenance().isAfter(today.plusDays(15)))
                .sorted(Comparator.comparing(Vehicle::nextMaintenance)).limit(1)
                .forEach(v -> out.add(new Alert(AlertKind.MAINTENANCE, "Entretien à prévoir",
                        v.registration() + " · " + v.label(), dueLabel(today, v.nextMaintenance()), "danger")));
        vehicles.stream().filter(v -> v.fuelLevelPercent() != null && v.fuelLevelPercent() > 0 && v.fuelLevelPercent() < 20)
                .limit(1).forEach(v -> out.add(new Alert(AlertKind.FUEL_LOW, "Niveau de carburant bas",
                        v.registration() + " · Réservoir déclaré à " + v.fuelLevelPercent() + " %", "Hier", "warning")));
        // Échéance documentaire la plus proche : issue de la même source que l'écran Échéances (NF-11)
        dueDates(30).stream().filter(d -> d.vehicle() && d.level() != Models.DueLevel.A_JOUR).findFirst()
                .ifPresent(d -> out.add(new Alert(AlertKind.INSURANCE, d.kind().label + (d.level() == Models.DueLevel.EXPIRE
                        ? " expirée" : " à renouveler"), d.subject() + " · " + d.subjectDetail(), dueLabel(today, d.dueOn()),
                        d.level() == Models.DueLevel.EXPIRE ? "danger" : "info")));
        return out;
    }

    // ------------------------------------------------------------------ notifications (NF-24, démo)

    private final java.util.Set<String> readIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public List<Models.NotificationItem> notifications() {
        require(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_READ);
        Instant now = clock.instant();
        List<Models.NotificationItem> out = new ArrayList<>();
        int i = 0;
        for (Models.DueItem d : dueDates(30)) {
            if (d.level() == Models.DueLevel.A_JOUR) continue;
            String id = "due-" + d.kind() + "-" + d.subject();
            String title = d.kind().label + (d.level() == Models.DueLevel.EXPIRE ? " expiré" : " à renouveler");
            if (d.kind() == Models.DueKind.ASSURANCE || d.kind() == Models.DueKind.VIGNETTE || d.kind() == Models.DueKind.CNI) {
                title = d.kind().label + (d.level() == Models.DueLevel.EXPIRE ? " expirée" : " à renouveler");
            }
            String body = d.level() == Models.DueLevel.EXPIRE
                    ? d.subject() + " : échéance dépassée depuis " + (-d.daysLeft()) + " jour" + (d.daysLeft() < -1 ? "s." : ".")
                    : d.subject() + " : échéance le " + d.dueOn().format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", Locale.FRANCE)) + ".";
            out.add(new Models.NotificationItem(id, Models.NotificationKind.ECHEANCE, title, body,
                    now.minus(Duration.ofMinutes(7L + i * 97L)), readIds.contains(id),
                    d.vehicle() ? "VEHICLES" : "DRIVERS", d.subject()));
            i++;
        }
        vehicles.stream().filter(v -> v.status() == VehicleStatus.MAINTENANCE).forEach(v -> out.add(new Models.NotificationItem(
                "maint-" + v.registration(), Models.NotificationKind.ENTRETIEN, "Entretien requis",
                v.registration() + " (" + v.label() + ") est signalé en entretien.", now.minus(Duration.ofHours(3)),
                readIds.contains("maint-" + v.registration()), "VEHICLES", v.registration())));
        vehicles.stream().filter(v -> v.fuelLevelPercent() != null && v.fuelLevelPercent() > 0 && v.fuelLevelPercent() < 20)
                .forEach(v -> out.add(new Models.NotificationItem("fuel-" + v.registration(), Models.NotificationKind.CARBURANT,
                        "Niveau de carburant bas", v.registration() + " : niveau déclaré à " + v.fuelLevelPercent() + " %.",
                        now.minus(Duration.ofHours(26)), readIds.contains("fuel-" + v.registration()), "VEHICLES", v.registration())));
        out.add(new Models.NotificationItem("sys-demo", Models.NotificationKind.SYSTEME, "Mode démonstration",
                "Les données affichées sont fictives et ne sont jamais envoyées en production.", now.minus(Duration.ofDays(3)),
                readIds.contains("sys-demo"), null, null));
        out.sort(Comparator.comparing(Models.NotificationItem::at).reversed());
        return out;
    }

    @Override
    public void markNotificationsRead(java.util.Collection<String> ids) {
        readIds.addAll(ids);
    }

    @Override
    public Vehicle archiveVehicle(UUID id, long expectedVersion, String reason) {
        pause();
        Session s = requireSession();
        if (!s.can(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_ARCHIVE)) {
            throw new GatewayException(GatewayException.Kind.FORBIDDEN, "Vous n'avez pas les droits nécessaires pour cette action.");
        }
        Vehicle v = vehicle(id);
        if (v.version() != expectedVersion) {
            throw new GatewayException(GatewayException.Kind.CONFLICT,
                    "Le véhicule a été modifié par un autre utilisateur. Rechargez-le avant de recommencer.");
        }
        Vehicle archived = new Vehicle(v.id(), v.registration(), v.make(), v.model(), v.modelYear(), v.energy(),
                VehicleStatus.ARCHIVED, v.driverName(), v.fuelLevelPercent(), v.lastKnownPlace(), v.odometerKm(),
                v.tankCapacityLiters(), v.nextMaintenance(), v.category(), v.version() + 1);
        vehicles.replaceAll(x -> x.id().equals(id) ? archived : x);
        ops().record(s, "VEHICLE_ARCHIVED", v.registration(), "Motif : " + reason);
        return archived;
    }

    // ------------------------------------------------------------------ conducteurs et échéances (parité ancien NexFleet)

    @Override
    public List<Models.Driver> drivers(String query) {
        pause();
        require(cm.camtel.nextfleet.domain.security.Permission.DRIVER_READ);
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return drivers.stream().filter(d -> q.isEmpty() || d.fullName().toLowerCase(Locale.ROOT).contains(q)
                        || d.staffNumber().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(Models.Driver::fullName)).toList();
    }

    @Override
    public List<Models.DueItem> dueDates(int thresholdDays) {
        Session s = require(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_READ);
        boolean people = s.can(cm.camtel.nextfleet.domain.security.Permission.DRIVER_READ);   // permis et CNI : données personnelles réservées
        LocalDate today = LocalDate.now(clock.withZone(DOUALA));
        List<Models.DueItem> out = new ArrayList<>();
        for (Vehicle v : vehicles) {
            if (v.status() == VehicleStatus.ARCHIVED) continue;
            out.addAll(vehicleDue(v, today, thresholdDays));
        }
        for (Models.Driver d : people ? drivers : List.<Models.Driver>of()) {
            out.add(due(Models.DueKind.PERMIS, d.fullName(), "Permis " + d.licenseNumber(), false, d.licenseExpiresOn(), today, thresholdDays));
            if (d.idCardExpiresOn() != null) {
                out.add(due(Models.DueKind.CNI, d.fullName(), "Matricule " + d.staffNumber(), false, d.idCardExpiresOn(), today, thresholdDays));
            }
        }
        out.sort(Comparator.comparing(Models.DueItem::dueOn).thenComparing(Models.DueItem::subject));
        return out;
    }

    @Override
    public List<Models.DueItem> vehicleDueDates(UUID vehicleId) {
        pause();
        require(cm.camtel.nextfleet.domain.security.Permission.VEHICLE_READ);
        LocalDate today = LocalDate.now(clock.withZone(DOUALA));
        return vehicle(vehicleId) == null ? List.of() : vehicleDue(vehicle(vehicleId), today, 30);
    }

    private List<Models.DueItem> vehicleDue(Vehicle v, LocalDate today, int threshold) {
        int h = Math.abs(v.registration().hashCode());
        List<Models.DueItem> out = new ArrayList<>();
        out.add(due(Models.DueKind.ASSURANCE, v.registration(), v.label(), true, today.plusDays((h % 240) - 20), today, threshold));
        out.add(due(Models.DueKind.VIGNETTE, v.registration(), v.label(), true, today.plusDays(((h / 7) % 300) - 8), today, threshold));
        out.add(due(Models.DueKind.CONTROLE_TECHNIQUE, v.registration(), v.label(), true, today.plusDays(((h / 13) % 200) + 3), today, threshold));
        return out;
    }

    private static Models.DueItem due(Models.DueKind k, String subject, String detail, boolean vehicle, LocalDate on,
                                      LocalDate today, int threshold) {
        long days = ChronoUnit.DAYS.between(today, on);
        Models.DueLevel level = days < 0 ? Models.DueLevel.EXPIRE : days <= threshold ? Models.DueLevel.A_ECHEANCE : Models.DueLevel.A_JOUR;
        return new Models.DueItem(k, subject, detail, vehicle, on, days, level);
    }

    private static String dueLabel(LocalDate today, LocalDate due) {
        long d = ChronoUnit.DAYS.between(today, due);
        return d <= 0 ? "Aujourd'hui" : d == 1 ? "Demain" : "Dans " + d + " jours";
    }

    private static BigDecimal trend(BigDecimal cur, BigDecimal prev) {
        if (cur == null || prev == null || prev.signum() == 0) return null;
        return cur.subtract(prev).multiply(BigDecimal.valueOf(100)).divide(prev, 0, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(java.util.Collection<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------ lot 2 (démo) : délégation à DemoOperations

    private volatile DemoOperations ops;

    private DemoOperations ops() {
        DemoOperations o = ops;
        if (o == null) {
            synchronized (this) {
                if (ops == null) ops = new DemoOperations(this, clock);
                o = ops;
            }
        }
        return o;
    }

    List<Vehicle> allVehicles() {
        return List.copyOf(vehicles);
    }

    List<FuelFillRecord> fillsOf(UUID id) {
        return fills.getOrDefault(id, List.of());
    }

    @Override
    public List<Ops.Mission> missions() {
        pause();
        return ops().missions(requireSession());
    }

    @Override
    public List<cm.camtel.nextfleet.domain.gps.GpsFix> missionTrack(UUID missionId) {
        pause();
        return ops().track(requireSession(), missionId);
    }

    @Override
    public Ops.Mission startMission(UUID missionId, String noticeVersion) {
        pause();
        return ops().start(requireSession(), missionId, noticeVersion);
    }

    @Override
    public Ops.Mission finishMission(UUID missionId) {
        pause();
        return ops().finish(requireSession(), missionId);
    }

    @Override
    public List<Ops.Conversation> conversations() {
        pause();
        return ops().conversations(requireSession());
    }

    @Override
    public List<Ops.ChatLine> messages(String conversationId) {
        pause();
        return ops().messages(requireSession(), conversationId);
    }

    @Override
    public Ops.ChatLine sendMessage(String conversationId, String clientMessageId, String body) {
        pause();
        return ops().send(requireSession(), conversationId, clientMessageId, body);
    }

    @Override
    public void markConversationRead(String conversationId) {
        ops().markRead(requireSession(), conversationId);
    }

    @Override
    public cm.camtel.nextfleet.domain.assistant.AssistantActivation.State assistantState() {
        requireSession();
        return ops().assistantState();
    }

    @Override
    public cm.camtel.nextfleet.domain.assistant.FleetAssistant.Answer askAssistant(String question) {
        pause();
        return ops().ask(requireSession(), question);
    }

    @Override
    public cm.camtel.nextfleet.domain.assistant.AssistantActivation.State decideAssistant(boolean enable, String reviewRef,
                                                                                         String approvedBy, String reason) {
        pause();
        return ops().decide(requireSession(), enable, reviewRef, approvedBy, reason);
    }

    @Override
    public List<cm.camtel.nextfleet.domain.assistant.AssistantActivation.State> assistantDecisions() {
        return ops().decisions(requireSession());
    }

    @Override
    public List<Ops.UserAccount> users() {
        pause();
        return ops().users(requireSession());
    }

    @Override
    public List<Ops.AuditEntry> auditTrail() {
        pause();
        return ops().audit(requireSession());
    }

    private Session require(cm.camtel.nextfleet.domain.security.Permission p) {
        Session s = requireSession();
        if (!s.can(p)) throw new GatewayException(GatewayException.Kind.FORBIDDEN, "Vous n'avez pas les droits nécessaires pour cette action.");
        return s;
    }

    private Session requireSession() {
        Session s = session;
        if (s == null) throw new GatewayException(GatewayException.Kind.UNAUTHORIZED, "Session expirée. Reconnectez-vous.");
        return s;
    }

    private void pause() {
        if (networkDown) {
            throw new GatewayException(GatewayException.Kind.NETWORK,
                    "Serveur injoignable. Vérifiez la connexion réseau ; aucune donnée n'a été modifiée.");
        }
        if (!latency.isZero()) {
            try {
                Thread.sleep(latency.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
