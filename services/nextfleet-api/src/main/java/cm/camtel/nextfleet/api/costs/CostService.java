package cm.camtel.nextfleet.api.costs;

import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.identity.AccessService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Coûts réels (carburant, interventions clôturées, vidanges hors ordre — sans double comptage, NF-15) comparés au budget
 * importé (NF-26). Lecture : cost:read ou gestionnaire du parc. Montants en francs CFA (XAF).
 */
@Service
public class CostService {

    public record MonthRow(int month, BigDecimal fuelXaf, BigDecimal maintenanceXaf, BigDecimal oilXaf, BigDecimal totalXaf,
                           BigDecimal liters) { }

    public record CategoryRow(String code, String label, BigDecimal actualXaf, BigDecimal budgetXaf, BigDecimal consumedPercent) { }

    public record VehicleRow(UUID vehicleId, String registration, String label, BigDecimal fuelXaf, BigDecimal maintenanceXaf,
                             BigDecimal totalXaf, BigDecimal liters, int interventions) { }

    public record Summary(int year, BigDecimal totalXaf, BigDecimal fuelXaf, BigDecimal maintenanceXaf, BigDecimal oilXaf,
                          BigDecimal budgetXaf, BigDecimal liters, List<MonthRow> months, List<CategoryRow> categories,
                          List<VehicleRow> vehicles) { }

    public record BudgetLine(UUID id, int fiscalYear, UUID orgUnitId, String orgUnitName, String categoryCode, String categoryLabel,
                             String activity, String task, BigDecimal q1, BigDecimal q2, BigDecimal q3, BigDecimal q4,
                             BigDecimal annual, String sourceFile, int sourceRow) { }

    public record ImportResult(int read, int inserted, int duplicates, List<String> errors) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;

    public CostService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    List<UUID> readScope() {
        Set<UUID> s = new LinkedHashSet<>(access.scopeFor("cost:read"));
        s.addAll(access.scopeFor("vehicle:write"));
        return new ArrayList<>(s);
    }

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public Summary summary(int year, UUID orgUnitId) {
        List<UUID> scope = readScope();
        if (scope.isEmpty()) {
            audit.recordDenied(access.currentUser().userId(), "cost", String.valueOf(year), "cost:read");
            throw new AccessDeniedException("Accès refusé");
        }
        if (orgUnitId != null) {
            if (!scope.contains(orgUnitId)) access.require("cost:read", orgUnitId, "cost", orgUnitId.toString());
            scope = List.of(orgUnitId);
        }
        MapSqlParameterSource p = new MapSqlParameterSource("s", scope).addValue("y", year);
        Map<Integer, BigDecimal[]> months = new LinkedHashMap<>();
        for (int m = 1; m <= 12; m++) months.put(m, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        jdbc.query("""
                SELECT extract(month FROM f.filled_at AT TIME ZONE 'Africa/Douala')::int, sum(f.amount), sum(f.liters)
                  FROM fuel_fill f JOIN vehicle v ON v.id = f.vehicle_id
                 WHERE v.org_unit_id IN (:s) AND extract(year FROM f.filled_at AT TIME ZONE 'Africa/Douala') = :y GROUP BY 1""", p, rs -> {
            BigDecimal[] a = months.get(rs.getInt(1));
            a[0] = rs.getBigDecimal(2);
            a[3] = rs.getBigDecimal(3);
        });
        jdbc.query("""
                SELECT extract(month FROM coalesce(mo.immobilised_to, mo.created_at) AT TIME ZONE 'Africa/Douala')::int, sum(mo.cost_xaf)
                  FROM maintenance_order mo JOIN vehicle v ON v.id = mo.vehicle_id
                 WHERE v.org_unit_id IN (:s) AND mo.status = 'CLOTUREE' AND mo.cost_xaf IS NOT NULL
                   AND extract(year FROM coalesce(mo.immobilised_to, mo.created_at) AT TIME ZONE 'Africa/Douala') = :y GROUP BY 1""", p, rs -> {
            months.get(rs.getInt(1))[1] = rs.getBigDecimal(2);
        });
        jdbc.query("""
                SELECT extract(month FROM oc.done_on)::int, sum(oc.cost_xaf) FROM oil_change oc JOIN vehicle v ON v.id = oc.vehicle_id
                 WHERE v.org_unit_id IN (:s) AND oc.maintenance_order_id IS NULL AND oc.cost_xaf IS NOT NULL
                   AND extract(year FROM oc.done_on) = :y GROUP BY 1""", p, rs -> {
            months.get(rs.getInt(1))[2] = rs.getBigDecimal(2);
        });
        List<MonthRow> monthRows = new ArrayList<>();
        BigDecimal fuel = BigDecimal.ZERO, maint = BigDecimal.ZERO, oil = BigDecimal.ZERO, liters = BigDecimal.ZERO;
        for (var e : months.entrySet()) {
            BigDecimal[] a = e.getValue();
            monthRows.add(new MonthRow(e.getKey(), a[0], a[1], a[2], a[0].add(a[1]).add(a[2]), a[3]));
            fuel = fuel.add(a[0]);
            maint = maint.add(a[1]);
            oil = oil.add(a[2]);
            liters = liters.add(a[3]);
        }

        // par catégorie : carburant rattaché à « Carburant » (hors référentiel atelier), interventions par catégorie, vidanges à VIDANGE
        Map<String, CategoryRow> cats = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.code, c.label,
                       coalesce((SELECT sum(mo.cost_xaf) FROM maintenance_order mo JOIN vehicle v ON v.id = mo.vehicle_id
                                  WHERE mo.category_code = c.code AND v.org_unit_id IN (:s) AND mo.status = 'CLOTUREE'
                                    AND extract(year FROM coalesce(mo.immobilised_to, mo.created_at) AT TIME ZONE 'Africa/Douala') = :y), 0)
                     + CASE WHEN c.code = 'VIDANGE' THEN coalesce((SELECT sum(oc.cost_xaf) FROM oil_change oc JOIN vehicle v ON v.id = oc.vehicle_id
                                  WHERE v.org_unit_id IN (:s) AND oc.maintenance_order_id IS NULL AND extract(year FROM oc.done_on) = :y), 0) ELSE 0 END,
                       coalesce((SELECT sum(b.annual_amount) FROM budget_plan_line b WHERE b.category_code = c.code
                                  AND b.org_unit_id IN (:s) AND b.fiscal_year = :y), 0)
                  FROM maintenance_category c ORDER BY c.label""", p, rs -> {
            BigDecimal actual = rs.getBigDecimal(3);
            BigDecimal budget = rs.getBigDecimal(4);
            if (actual.signum() == 0 && budget.signum() == 0) return;
            cats.put(rs.getString(1), new CategoryRow(rs.getString(1), rs.getString(2), actual, budget,
                    budget.signum() > 0 ? actual.multiply(BigDecimal.valueOf(100)).divide(budget, 1, java.math.RoundingMode.HALF_UP) : null));
        });
        List<CategoryRow> categories = new ArrayList<>();
        if (fuel.signum() > 0) categories.add(new CategoryRow("CARBURANT", "Carburant", fuel, BigDecimal.ZERO, null));
        categories.addAll(cats.values());
        BigDecimal budget = jdbc.queryForObject("SELECT coalesce(sum(annual_amount), 0) FROM budget_plan_line WHERE org_unit_id IN (:s) AND fiscal_year = :y",
                p, BigDecimal.class);

        List<VehicleRow> vehicles = jdbc.query("""
                SELECT v.id, v.registration, v.make || ' ' || v.model,
                       coalesce(f.amount, 0), coalesce(m.cost, 0) + coalesce(o.cost, 0), coalesce(f.liters, 0), coalesce(m.n, 0)
                  FROM vehicle v
                  LEFT JOIN (SELECT vehicle_id, sum(amount) amount, sum(liters) liters FROM fuel_fill
                              WHERE extract(year FROM filled_at AT TIME ZONE 'Africa/Douala') = :y GROUP BY vehicle_id) f ON f.vehicle_id = v.id
                  LEFT JOIN (SELECT vehicle_id, sum(cost_xaf) cost, count(*) n FROM maintenance_order
                              WHERE status = 'CLOTUREE' AND extract(year FROM coalesce(immobilised_to, created_at) AT TIME ZONE 'Africa/Douala') = :y
                              GROUP BY vehicle_id) m ON m.vehicle_id = v.id
                  LEFT JOIN (SELECT vehicle_id, sum(cost_xaf) cost FROM oil_change
                              WHERE maintenance_order_id IS NULL AND extract(year FROM done_on) = :y GROUP BY vehicle_id) o ON o.vehicle_id = v.id
                 WHERE v.org_unit_id IN (:s) AND (f.amount IS NOT NULL OR m.cost IS NOT NULL OR o.cost IS NOT NULL)
                 ORDER BY coalesce(f.amount, 0) + coalesce(m.cost, 0) + coalesce(o.cost, 0) DESC LIMIT 200""", p,
                (rs, i) -> new VehicleRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(4).add(rs.getBigDecimal(5)), rs.getBigDecimal(6), rs.getInt(7)));
        return new Summary(year, fuel.add(maint).add(oil), fuel, maint, oil, budget, liters, monthRows, categories, vehicles);
    }

    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public List<BudgetLine> budget(int year) {
        List<UUID> scope = readScope();
        if (scope.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT b.id, b.fiscal_year, b.org_unit_id, o.name, b.category_code, c.label, b.activity, b.task,
                       b.q1_amount, b.q2_amount, b.q3_amount, b.q4_amount, b.annual_amount, b.source_file, b.source_row
                  FROM budget_plan_line b JOIN organisation_unit o ON o.id = b.org_unit_id JOIN maintenance_category c ON c.code = b.category_code
                 WHERE b.org_unit_id IN (:s) AND b.fiscal_year = :y ORDER BY o.name, c.label, b.source_file, b.source_row""",
                new MapSqlParameterSource("s", scope).addValue("y", year), (rs, i) -> new BudgetLine(rs.getObject(1, UUID.class),
                        rs.getInt(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12),
                        rs.getBigDecimal(13), rs.getString(14), rs.getInt(15)));
    }

    /**
     * Import CSV du budget (séparateur « ; », en-tête obligatoire) :
     * {@code categorie;activite;tache;t1;t2;t3;t4}. Un réimport du même fichier n'ajoute aucune ligne (NF-26).
     * Montants : chiffres, espaces de milliers et virgule décimale acceptés.
     */
    @Transactional(noRollbackFor = AccessDeniedException.class)
    public ImportResult importBudget(int year, UUID orgUnitId, String sourceFile, String csv) {
        access.require("vehicle:write", orgUnitId, "budget_plan_line", "import");
        if (year < 2000 || year > 2100) throw new IllegalArgumentException("Exercice invalide.");
        String file = sourceFile == null || sourceFile.isBlank() ? "import.csv" : sourceFile.strip();
        String[] lines = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length < 2) throw new IllegalArgumentException("Fichier vide : une ligne d'en-tête puis une ligne par tâche.");
        Set<String> categories = new LinkedHashSet<>(jdbc.queryForList("SELECT code FROM maintenance_category", new MapSqlParameterSource(), String.class));
        UUID batch = UUID.randomUUID();
        int read = 0, inserted = 0, dup = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            read++;
            String[] c = line.split(";", -1);
            if (c.length < 7) {
                errors.add("Ligne " + (i + 1) + " : 7 colonnes attendues (categorie;activite;tache;t1;t2;t3;t4).");
                continue;
            }
            String cat = c[0].strip().toUpperCase();
            if (!categories.contains(cat)) {
                errors.add("Ligne " + (i + 1) + " : catégorie inconnue « " + c[0].strip() + " ».");
                continue;
            }
            if (c[2].isBlank()) {
                errors.add("Ligne " + (i + 1) + " : tâche manquante.");
                continue;
            }
            try {
                BigDecimal[] q = new BigDecimal[4];
                for (int k = 0; k < 4; k++) q[k] = amount(c[3 + k]);
                int n = jdbc.update("""
                        INSERT INTO budget_plan_line(fiscal_year, org_unit_id, category_code, activity, task, q1_amount, q2_amount,
                                                     q3_amount, q4_amount, source_file, source_row, import_batch_id)
                        VALUES (:y, :o, :c, :a, :t, :q1, :q2, :q3, :q4, :f, :r, :b)
                        ON CONFLICT (fiscal_year, org_unit_id, source_file, source_row) DO NOTHING""",
                        new MapSqlParameterSource("y", year).addValue("o", orgUnitId).addValue("c", cat)
                                .addValue("a", c[1].isBlank() ? null : c[1].strip()).addValue("t", c[2].strip())
                                .addValue("q1", q[0]).addValue("q2", q[1]).addValue("q3", q[2]).addValue("q4", q[3])
                                .addValue("f", file).addValue("r", i + 1).addValue("b", batch));
                if (n == 1) inserted++;
                else dup++;
            } catch (IllegalArgumentException e) {
                errors.add("Ligne " + (i + 1) + " : " + e.getMessage());
            }
        }
        audit.record(access.currentUser().userId(), "BUDGET_IMPORT", "budget_plan_line", batch.toString(), file, null,
                Map.of("year", year, "orgUnitId", orgUnitId.toString(), "read", read, "inserted", inserted, "duplicates", dup,
                        "errors", errors.size()));
        return new ImportResult(read, inserted, dup, errors.size() > 50 ? errors.subList(0, 50) : errors);
    }

    static BigDecimal amount(String raw) {
        String s = raw == null ? "" : raw.strip().replace(" ", "").replace(" ", "").replace(" ", "");
        if (s.isEmpty()) return BigDecimal.ZERO;
        s = s.replace(',', '.');
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.signum() < 0) throw new IllegalArgumentException("montant négatif « " + raw.strip() + " ».");
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("montant illisible « " + raw.strip() + " ».");
        }
    }

    public static int currentYear() {
        return LocalDate.now(java.time.ZoneId.of("Africa/Douala")).getYear();
    }
}
