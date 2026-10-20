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
        List<String> roles = switch (l) {
            case "gestionnaire" -> List.of("FLEET_MANAGER");
            case "controleur" -> List.of("CONTROLLER");
            case "auditeur" -> List.of("AUDITOR");
            default -> null;
        };
        if (!ok || roles == null) {
            throw new GatewayException(GatewayException.Kind.UNAUTHORIZED, "Identifiants invalides ou compte indisponible.");
        }
        String name = switch (l) {
            case "gestionnaire" -> "Gestionnaire (démo)";
            case "controleur" -> "Contrôleur (démo)";
            default -> "Auditeur (démo)";
        };
        session = new Session("demo-" + UUID.randomUUID(), name, l, roles, true);
        return session;
    }

    @Override
    public void logout() {
        session = null;
    }

    @Override
    public DashboardData dashboard() {
        pause();
        requireSession();
        YearMonth current = YearMonth.now(clock.withZone(DOUALA));
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
        requireSession();
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String qn = q.replaceAll("[^a-z0-9]", "").toUpperCase(Locale.ROOT);
        List<Vehicle> filtered = vehicles.stream()
                .filter(v -> status == null || v.status() == status)
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
        requireSession();
        return vehicles.stream().filter(v -> v.id().equals(id)).findFirst()
                .orElseThrow(() -> new GatewayException(GatewayException.Kind.NOT_FOUND, "Véhicule introuvable."));
    }

    @Override
    public Vehicle createVehicle(VehicleDraft d) {
        pause();
        Session s = requireSession();
        if (!s.has("FLEET_MANAGER")) {
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
        out.add(new Alert(AlertKind.INSURANCE, "Assurance à renouveler", "CE 1188 LT · Échéance dans 12 jours",
                "Dans 12 jours", "info"));
        return out;
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
