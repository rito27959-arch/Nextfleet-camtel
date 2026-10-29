package cm.camtel.nextfleet.api.legacy;

import cm.camtel.nextfleet.api.admin.AccountService;
import cm.camtel.nextfleet.api.audit.AuditService;
import cm.camtel.nextfleet.api.identity.AccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reprise des données de l'ancienne application (base SQLite « parc.db ») dans NexFleet.
 *
 * <p>Un seul chemin de code pour l'aperçu et l'import : l'aperçu exécute la reprise complète dans une transaction
 * annulée à la fin, de sorte qu'il annonce exactement ce que fera l'import (et révèle toute ligne refusée par la base
 * avant d'avoir rien écrit). L'import s'exécute en une transaction : tout ou rien.
 *
 * <p>Réimport sans doublon : chaque ligne d'origine est inscrite dans {@code legacy_map} ; un véhicule (immatriculation),
 * un conducteur (matricule), une entité (code) ou un compte (identifiant) déjà présents sont réutilisés, jamais dupliqués
 * ni écrasés. Ce qui n'a pas d'équivalent est archivé tel quel ({@code legacy_archive}). Les secrets de l'ancienne
 * application (clés d'API, empreintes de mots de passe) ne sont jamais repris.
 */
@Service
public class LegacyImportService {

    static final String SOURCE = "ancien-nexfleet";
    private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
    private static final Pattern DAY = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record CreatedAccount(String login, String displayName, String temporaryPassword) { }

    public record Report(String sha256, boolean committed, Map<String, Integer> counts, List<String> warnings,
                         List<CreatedAccount> accounts) { }

    public record PastImport(UUID id, String fileName, String sha256, String importedBy, OffsetDateTime importedAt, Object summary) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final AccountService accounts;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public LegacyImportService(NamedParameterJdbcTemplate jdbc, AccessService access, AuditService audit, AccountService accounts,
                               PlatformTransactionManager tm, ObjectMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.accounts = accounts;
        this.tx = new TransactionTemplate(tm);
        this.json = json;
    }

    /** Aperçu : reprise complète puis annulation. Rien n'est écrit ; l'empreinte sert à confirmer le même fichier. */
    public Report preview(String fileName, byte[] content) {
        return run(fileName, content, null, true);
    }

    /** Import réel du fichier dont l'aperçu a été vu (même empreinte SHA-256). */
    public Report commit(String fileName, byte[] content, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) throw new IllegalArgumentException("Faites d'abord l'aperçu du fichier.");
        return run(fileName, content, expectedSha256.strip().toLowerCase(Locale.ROOT), false);
    }

    public List<PastImport> history() {
        UUID org = adminOrg();
        access.require("admin:settings", org, "legacy_import", "list");
        return jdbc.query("""
                SELECT i.id, i.file_name, i.file_sha256, u.display_name, i.imported_at, i.summary::text
                  FROM legacy_import i JOIN app_user u ON u.id = i.imported_by ORDER BY i.imported_at DESC""",
                (rs, n) -> {
                    Object summary;
                    try {
                        summary = json.readTree(rs.getString(6));
                    } catch (IOException e) {
                        summary = null;
                    }
                    return new PastImport(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getObject(5, OffsetDateTime.class), summary);
                });
    }

    // ------------------------------------------------------------------ exécution

    private Report run(String fileName, byte[] content, String expectedSha, boolean dryRun) {
        UUID org = adminOrg();
        access.require("admin:settings", org, "legacy_import", "new");
        if (content == null || content.length < 100) throw new IllegalArgumentException("Fichier vide ou illisible.");
        String sha = sha256(content);
        if (expectedSha != null && !expectedSha.equals(sha)) {
            throw new IllegalArgumentException("Ce n'est pas le fichier dont vous avez vu l'aperçu : refaites l'aperçu.");
        }
        Legacy data = read(content);
        Report report = tx.execute(status -> {
            Run r = new Run(org, sha, fileName, data);
            r.execute();
            if (dryRun) status.setRollbackOnly();
            return new Report(sha, !dryRun, r.counts, r.warnings, dryRun ? List.of() : r.created);
        });
        return report;
    }

    private UUID adminOrg() {
        return jdbc.queryForObject("SELECT org_unit_id FROM app_user WHERE id = :u",
                new MapSqlParameterSource("u", access.currentUser().userId()), UUID.class);
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ lecture de la base SQLite

    /** Tables de l'ancienne application, en mémoire (quelques milliers de lignes au plus). */
    record Legacy(Map<String, List<Map<String, Object>>> tables) {
        List<Map<String, Object>> rows(String t) {
            return tables.getOrDefault(t, List.of());
        }

        boolean has(String t) {
            return tables.containsKey(t);
        }
    }

    private static final List<String> TABLES = List.of("users", "vehicles", "settings", "odometer", "documents", "tires", "drivers",
            "controls", "maintenance", "vidanges", "budget_plan", "audit_log", "chat_history", "fuel_fillups", "costs");

    static Legacy read(byte[] content) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("nexfleet-reprise-", ".db");
            Files.write(tmp, content);
            org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
            cfg.setReadOnly(true);
            Map<String, List<Map<String, Object>>> tables = new HashMap<>();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath(), cfg.toProperties());
                 Statement st = c.createStatement()) {
                Set<String> present = new HashSet<>();
                try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                    while (rs.next()) present.add(rs.getString(1));
                }
                if (!present.contains("vehicles") || !present.contains("drivers")) {
                    throw new IllegalArgumentException("Ce fichier n'est pas une base de l'ancienne application (tables « vehicles » et « drivers » absentes).");
                }
                for (String t : TABLES) {
                    if (!present.contains(t)) continue;
                    List<Map<String, Object>> rows = new ArrayList<>();
                    try (ResultSet rs = st.executeQuery("SELECT * FROM \"" + t + "\" ORDER BY id")) {
                        ResultSetMetaData md = rs.getMetaData();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnName(i).toLowerCase(Locale.ROOT), rs.getObject(i));
                            rows.add(row);
                        }
                    } catch (SQLException e) {
                        // table sans colonne id (settings) : lecture sans tri
                        try (ResultSet rs = st.executeQuery("SELECT * FROM \"" + t + "\"")) {
                            ResultSetMetaData md = rs.getMetaData();
                            while (rs.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnName(i).toLowerCase(Locale.ROOT), rs.getObject(i));
                                rows.add(row);
                            }
                        }
                    }
                    tables.put(t, rows);
                }
            }
            return new Legacy(tables);
        } catch (SQLException e) {
            throw new IllegalArgumentException("Base SQLite illisible : " + e.getMessage());
        } catch (IOException e) {
            throw new IllegalStateException("Fichier temporaire impossible", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // nettoyage au mieux
                }
            }
        }
    }

    // ------------------------------------------------------------------ une reprise

    private final class Run {
        final UUID org;
        final String sha;
        final String fileName;
        final Legacy data;
        final UUID importId = UUID.randomUUID();
        final UUID me = access.currentUser().userId();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
        final List<CreatedAccount> created = new ArrayList<>();
        final Map<String, UUID> orgs = new HashMap<>();
        final Map<Long, UUID> vehicles = new HashMap<>();
        final Map<Long, UUID> drivers = new HashMap<>();
        final Map<String, UUID> suppliers = new HashMap<>();

        Run(UUID org, String sha, String fileName, Legacy data) {
            this.org = org;
            this.sha = sha;
            this.fileName = fileName;
            this.data = data;
        }

        void execute() {
            secrets();
            vehicles();
            drivers();
            maintenance();
            oilChanges();
            controls();
            fuel();
            budget();
            archives();
            users();
            if (!data.rows("documents").isEmpty() || !data.rows("tires").isEmpty() || !data.rows("odometer").isEmpty()) {
                warnings.add("Documents, pneus ou relevés de compteur détaillés de l'ancienne application : contenu archivé, fichiers non fournis.");
            }
            Map<String, Object> summary = new LinkedHashMap<>(counts);
            summary.put("avertissements", warnings.size());
            jdbc.update("""
                    INSERT INTO legacy_import(id, source, file_name, file_sha256, imported_by, summary)
                    VALUES (:id, :src, :f, :sha, :me, CAST(:s AS jsonb))""",
                    new MapSqlParameterSource("id", importId).addValue("src", SOURCE).addValue("f", fileName).addValue("sha", sha)
                            .addValue("me", me).addValue("s", toJson(summary)));
            audit.record(me, "IMPORT", "legacy_import", importId.toString(), "Reprise de l'ancienne application", null, summary);
        }

        // -------------------------------------------------------------- secrets jamais repris

        void secrets() {
            for (Map<String, Object> s : data.rows("settings")) {
                String key = str(s.get("key"));
                if (key != null && key.toLowerCase(Locale.ROOT).contains("key") && !blank(s.get("value"))) {
                    warnings.add("Paramètre « " + key + " » de l'ancienne application non repris (secret). S'il s'agit d'une vraie clé, révoquez-la "
                            + "auprès du fournisseur : elle a circulé avec le fichier.");
                }
            }
            if (!data.rows("users").isEmpty()) {
                warnings.add("Empreintes de mots de passe de l'ancienne application non reprises : chaque compte repris reçoit un mot de passe provisoire.");
            }
        }

        // -------------------------------------------------------------- véhicules, entités, documents

        void vehicles() {
            Set<String> vins = new HashSet<>();
            for (Map<String, Object> v : data.rows("vehicles")) {
                long sid = id(v);
                UUID mapped = mapped("vehicles", sid);
                if (mapped != null) {
                    vehicles.put(sid, mapped);
                    count("vehicules_deja_repris");
                    continue;
                }
                String registration = str(v.get("immatriculation"));
                if (registration == null) {
                    archive("vehicles", sid, v);
                    map("vehicles", sid, null);
                    count("vehicules_ecartes");
                    warnings.add("Véhicule n° " + sid + " sans immatriculation : archivé, non repris.");
                    continue;
                }
                List<UUID> existing = jdbc.queryForList("""
                        SELECT id FROM vehicle WHERE registration_normalized = upper(regexp_replace(:r, '[^A-Za-z0-9]', '', 'g'))""",
                        new MapSqlParameterSource("r", registration), UUID.class);
                if (!existing.isEmpty()) {
                    vehicles.put(sid, existing.get(0));
                    map("vehicles", sid, existing.get(0));
                    count("vehicules_deja_presents");
                    continue;
                }
                String vin = str(v.get("num_chassis"));
                if (vin != null) {
                    String n = vin.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
                    Integer taken = jdbc.queryForObject("SELECT count(*) FROM vehicle WHERE vin_normalized = :n", new MapSqlParameterSource("n", n), Integer.class);
                    if (n.isEmpty() || !vins.add(n) || (taken != null && taken > 0)) {
                        warnings.add(registration + " : numéro de châssis « " + vin + " » déjà utilisé par un autre véhicule, non repris.");
                        vin = null;
                    }
                }
                UUID unit = unit(str(v.get("direction")));
                Integer year = integer(v.get("annee"));
                Integer hp = integer(v.get("cv"));
                UUID id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO vehicle(id, registration, vin, make, model, model_year, vehicle_type, energy, engine_number, status,
                                            org_unit_id, direction, site_label, fiscal_hp, registration_card_no, category, created_at)
                        VALUES (:id, :reg, :vin, :make, :model, :year, :type, :energy, :engine, :status, :org, :dir, :site, :hp, :card,
                                :cat, :created)""",
                        new MapSqlParameterSource("id", id).addValue("reg", registration).addValue("vin", vin)
                                .addValue("make", orDefault(str(v.get("marque")), "Non renseignée"))
                                .addValue("model", orDefault(str(v.get("modele")), "Non renseigné"))
                                .addValue("year", year != null && year >= 1950 && year <= 2100 ? year : null)
                                .addValue("type", str(v.get("type"))).addValue("energy", energy(str(v.get("carburant"))))
                                .addValue("engine", str(v.get("num_moteur"))).addValue("status", status(str(v.get("statut"))))
                                .addValue("org", unit).addValue("dir", str(v.get("direction"))).addValue("site", str(v.get("affectation")))
                                .addValue("hp", hp != null && hp >= 1 && hp <= 99 ? hp : null).addValue("card", str(v.get("carte_grise")))
                                .addValue("cat", category(str(v.get("marque")), str(v.get("modele"))))
                                .addValue("created", timestamp(v.get("created_at"))));
                vehicles.put(sid, id);
                map("vehicles", sid, id);
                count("vehicules");
                Integer km = integer(v.get("kilometrage"));
                if (km != null && km > 0) {
                    jdbc.update("""
                            INSERT INTO vehicle_odometer(vehicle_id, read_at, value_km, source, author_id, justification)
                            VALUES (:v, now(), :km, 'IMPORT', :me, 'Reprise de l''ancienne application')""",
                            new MapSqlParameterSource("v", id).addValue("km", km).addValue("me", me));
                    count("releves_compteur");
                }
                document(id, "ASSURANCE", v.get("assurance_exp"), null);
                document(id, "VIGNETTE", v.get("vignette_exp"), str(v.get("vignette_note")));
                document(id, "CONTROLE_TECHNIQUE", v.get("ctrl_tech_exp"), str(v.get("ct_note")));
                String card = str(v.get("carte_grise"));
                String cardNote = str(v.get("carte_grise_note"));
                document(id, "CARTE_GRISE", v.get("carte_grise_exp"), card == null ? cardNote : cardNote == null ? card : card + " · " + cardNote);
                if (str(v.get("photo")) != null || v.get("lat") != null) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("immatriculation", registration);
                    extra.put("photo", v.get("photo"));
                    extra.put("photo_thumb", v.get("photo_thumb"));
                    extra.put("lat", v.get("lat"));
                    extra.put("lng", v.get("lng"));
                    archive("vehicles", sid, extra);
                    count("photos_archivees");
                }
            }
            if (counts.getOrDefault("photos_archivees", 0) > 0) {
                warnings.add(counts.get("photos_archivees") + " photo(s) de véhicule : seuls les noms de fichiers figurent dans la base, "
                        + "les images ne sont pas reprises. Ajoutez-les depuis la fiche du véhicule.");
            }
        }

        void document(UUID vehicle, String type, Object until, String reference) {
            LocalDate d = date(until);
            if (d == null) return;
            jdbc.update("INSERT INTO vehicle_document(vehicle_id, doc_type, reference, valid_until) VALUES (:v, :t, :r, :d)",
                    new MapSqlParameterSource("v", vehicle).addValue("t", type).addValue("r", reference).addValue("d", d));
            count("documents_vehicules");
        }

        /** Entité « direction » de l'ancienne application, rattachée à l'entité de l'administrateur ; réutilisée si son code existe. */
        UUID unit(String direction) {
            if (direction == null) return org;
            String code = direction.strip().toUpperCase(Locale.ROOT);
            return orgs.computeIfAbsent(code, c -> {
                List<UUID> found = jdbc.queryForList("SELECT id FROM organisation_unit WHERE upper(code) = :c", new MapSqlParameterSource("c", c), UUID.class);
                if (!found.isEmpty()) return found.get(0);
                UUID id = UUID.randomUUID();
                jdbc.update("INSERT INTO organisation_unit(id, code, name, parent_id) VALUES (:id, :c, :n, :p)",
                        new MapSqlParameterSource("id", id).addValue("c", c).addValue("n", direction.strip()).addValue("p", org));
                count("entites");
                return id;
            });
        }

        // -------------------------------------------------------------- conducteurs et permis

        void drivers() {
            for (Map<String, Object> d : data.rows("drivers")) {
                long sid = id(d);
                UUID mapped = mapped("drivers", sid);
                if (mapped != null) {
                    drivers.put(sid, mapped);
                    count("conducteurs_deja_repris");
                    continue;
                }
                String full = (orDefault(str(d.get("prenom")), "") + " " + orDefault(str(d.get("nom")), "")).strip();
                String staff = str(d.get("matricule"));
                if (staff == null) {
                    staff = "ANC-" + sid;
                    warnings.add(full + " : pas de matricule dans l'ancienne application, matricule provisoire " + staff + " (à corriger).");
                }
                List<UUID> existing = jdbc.queryForList("SELECT id FROM driver WHERE upper(staff_number) = upper(:s)",
                        new MapSqlParameterSource("s", staff), UUID.class);
                if (!existing.isEmpty()) {
                    drivers.put(sid, existing.get(0));
                    map("drivers", sid, existing.get(0));
                    count("conducteurs_deja_presents");
                    continue;
                }
                String email = str(d.get("email"));
                if (email != null && !EMAIL.matcher(email).matches()) {
                    warnings.add(full + " : e-mail « " + email + " » invalide, non repris.");
                    email = null;
                }
                UUID id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO driver(id, staff_number, full_name, phone, email, org_unit_id, id_card_number, id_card_expires_on, created_at)
                        VALUES (:id, :s, :n, :p, :e, :o, :cni, :cnie, :c)""",
                        new MapSqlParameterSource("id", id).addValue("s", staff).addValue("n", full.isEmpty() ? staff : full)
                                .addValue("p", str(d.get("telephone"))).addValue("e", email).addValue("o", org)
                                .addValue("cni", str(d.get("cni"))).addValue("cnie", date(d.get("cni_exp")))
                                .addValue("c", timestamp(d.get("created_at"))));
                drivers.put(sid, id);
                map("drivers", sid, id);
                count("conducteurs");
                String license = str(d.get("permis"));
                LocalDate until = date(d.get("permis_exp"));
                if (license != null && until != null) {
                    jdbc.update("INSERT INTO driver_license(driver_id, license_number, categories, expires_on) VALUES (:d, :n, '{}', :u)",
                            new MapSqlParameterSource("d", id).addValue("n", license.toUpperCase(Locale.ROOT)).addValue("u", until));
                    count("permis");
                } else {
                    warnings.add(full + " : permis " + (license == null ? "non renseigné" : "sans date de fin") + " dans l'ancienne application.");
                }
            }
            if (counts.getOrDefault("conducteurs", 0) > 0) {
                warnings.add("Conducteurs repris sans compte : pour leur confier des missions, liez chaque fiche à un compte (Comptes et rôles).");
            }
        }

        // -------------------------------------------------------------- interventions, vidanges, contrôles, pleins

        void maintenance() {
            for (Map<String, Object> m : data.rows("maintenance")) {
                String notes = str(m.get("notes"));
                String type = orDefault(str(m.get("type")), "Intervention");
                work("maintenance", id(m), m.get("vehicle_id"), type, type + (notes == null ? "" : " — " + cut(notes, 100)),
                        notes, m.get("date"), m.get("cout"), str(m.get("garage")));
            }
            for (Map<String, Object> k : data.rows("costs")) {
                if (k.get("vehicle_id") == null) {
                    if (mapped("costs", id(k)) == null && !isMapped("costs", id(k))) {
                        archive("costs", id(k), k);
                        map("costs", id(k), null);
                        count("couts_sans_vehicule_archives");
                    }
                    continue;
                }
                String label = orDefault(str(k.get("label")), "Dépense");
                work("costs", id(k), k.get("vehicle_id"), str(k.get("category")), label, orDefault(str(k.get("notes")), label),
                        k.get("date"), k.get("amount"), str(k.get("provider")));
            }
        }

        void work(String table, long sid, Object vehicle, String category, String title, String notes, Object day, Object cost, String garage) {
            if (isMapped(table, sid)) {
                count("interventions_deja_reprises");
                return;
            }
            UUID v = vehicle(vehicle, table, sid);
            if (v == null) return;
            LocalDate d = date(day);
            Timestamp at = d == null ? null : Timestamp.from(d.atTime(12, 0).atZone(DOUALA).toInstant());
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO maintenance_order(id, vehicle_id, category_code, title, status, garage_label, immobilised_from, immobilised_to,
                                                  cost_xaf, work_done, requested_by, legacy_import_id, created_at)
                    VALUES (:id, :v, :cat, :t, 'CLOTUREE', :g, CAST(:d AS timestamptz), CAST(:d AS timestamptz), :cost, :w, :me, :imp,
                            coalesce(CAST(:d AS timestamptz), now()))""",
                    new MapSqlParameterSource("id", id).addValue("v", v).addValue("cat", maintenanceCategory(category))
                            .addValue("t", cut(title, 200)).addValue("g", garage).addValue("d", at)
                            .addValue("cost", money(cost)).addValue("w", notes == null ? title : notes).addValue("me", me).addValue("imp", importId));
            map(table, sid, id);
            count("interventions");
        }

        void oilChanges() {
            for (Map<String, Object> o : data.rows("vidanges")) {
                long sid = id(o);
                if (isMapped("vidanges", sid)) {
                    count("vidanges_deja_reprises");
                    continue;
                }
                UUID v = vehicle(o.get("vehicle_id"), "vidanges", sid);
                LocalDate d = date(o.get("date"));
                if (v == null) continue;
                if (d == null) {
                    archive("vidanges", sid, o);
                    map("vidanges", sid, null);
                    count("vidanges_ecartees");
                    continue;
                }
                Integer km = integer(o.get("km"));
                Integer interval = integer(o.get("km_interval"));
                Integer next = integer(o.get("prochaine_km"));
                if ((interval == null || interval <= 0) && next != null && km != null && next > km) interval = next - km;
                BigDecimal cost = money(o.get("cout"));
                BigDecimal liters = decimal(o.get("quantite"));
                UUID id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO oil_change(id, vehicle_id, done_on, odometer_km, oil_label, oil_liters, oil_filter, fuel_filter,
                                               transmission_oil, coolant, washer_fluid, interval_km, cost_xaf, garage_label, author_id, created_at)
                        VALUES (:id, :v, :d, :km, :oil, :l, :of, :ff, :to, :co, :wf, :i, :cost, :g, :me, :c)""",
                        new MapSqlParameterSource("id", id).addValue("v", v).addValue("d", d).addValue("km", km == null || km < 0 ? 0 : km)
                                .addValue("oil", str(o.get("huile"))).addValue("l", liters != null && liters.signum() > 0 ? liters : null)
                                .addValue("of", flag(o.get("filtre_huile")) || flag(o.get("filtre"))).addValue("ff", flag(o.get("filtre_gasoil")))
                                .addValue("to", flag(o.get("huile_transmission"))).addValue("co", flag(o.get("liquide_refroidissement")))
                                .addValue("wf", flag(o.get("liquide_laveglace"))).addValue("i", interval != null && interval > 0 ? interval : null)
                                .addValue("cost", cost != null && cost.signum() > 0 ? cost : null).addValue("g", str(o.get("garage")))
                                .addValue("me", me).addValue("c", timestamp(o.get("created_at"))));
                map("vidanges", sid, id);
                count("vidanges");
            }
        }

        void controls() {
            Map<String, String> points = new HashMap<>();
            for (Map<String, Object> p : jdbc.queryForList("SELECT code, label FROM inspection_point", Map.of())) {
                points.put(norm((String) p.get("label")), (String) p.get("code"));
            }
            for (Map<String, Object> k : data.rows("controls")) {
                long sid = id(k);
                if (isMapped("controls", sid)) {
                    count("controles_deja_repris");
                    continue;
                }
                UUID v = vehicle(k.get("vehicle_id"), "controls", sid);
                LocalDate d = date(k.get("date"));
                if (v == null) continue;
                if (d == null) {
                    archive("controls", sid, k);
                    map("controls", sid, null);
                    count("controles_ecartes");
                    continue;
                }
                String created = str(k.get("created_at"));
                LocalDateTime when = created != null && created.startsWith(d.toString()) && created.length() >= 19
                        ? LocalDateTime.parse(created.substring(0, 19)) : d.atTime(12, 0);
                UUID id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO inspection(id, vehicle_id, driver_id, kind, inspected_at, result, notes, author_id, created_at)
                        VALUES (:id, :v, :d, :k, :at, :r, :n, :me, :c)""",
                        new MapSqlParameterSource("id", id).addValue("v", v).addValue("d", drivers.get(longOrNull(k.get("driver_id"))))
                                .addValue("k", inspectionKind(str(k.get("type")))).addValue("at", Timestamp.from(when.atZone(DOUALA).toInstant()))
                                .addValue("r", inspectionResult(str(k.get("resultat")))).addValue("n", str(k.get("notes")))
                                .addValue("me", me).addValue("c", timestamp(k.get("created_at"))));
                String items = str(k.get("items"));
                if (items != null) {
                    try {
                        json.readTree(items).fields().forEachRemaining(e -> {
                            String code = points.get(norm(e.getKey()));
                            String state = itemState(e.getValue().asText());
                            if (code != null && state != null) {
                                jdbc.update("INSERT INTO inspection_item(inspection_id, point_code, state, comment) VALUES (:i, :p, :s, :c)",
                                        new MapSqlParameterSource("i", id).addValue("p", code).addValue("s", state)
                                                .addValue("c", "DEFAUT".equals(state) ? "Défaut relevé dans l'ancienne application" : null));
                            }
                        });
                    } catch (IOException e) {
                        warnings.add("Contrôle n° " + sid + " : détail des 12 points illisible, seul le résultat global est repris.");
                    }
                }
                map("controls", sid, id);
                count("controles");
            }
        }

        void fuel() {
            for (Map<String, Object> f : data.rows("fuel_fillups")) {
                long sid = id(f);
                if (isMapped("fuel_fillups", sid)) {
                    count("pleins_deja_repris");
                    continue;
                }
                UUID v = vehicle(f.get("vehicle_id"), "fuel_fillups", sid);
                if (v == null) continue;
                BigDecimal liters = decimal(f.get("liters"));
                BigDecimal amount = money(f.get("amount"));
                LocalDate d = date(f.get("date"));
                if (liters == null || liters.signum() <= 0 || amount == null || amount.signum() <= 0 || d == null) {
                    archive("fuel_fillups", sid, f);
                    map("fuel_fillups", sid, null);
                    count("pleins_incomplets_archives");
                    continue;
                }
                liters = liters.setScale(2, RoundingMode.HALF_UP);
                BigDecimal price = amount.divide(liters, 2, RoundingMode.HALF_UP);
                Integer km = integer(f.get("km"));
                UUID id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO fuel_fill(id, exception_reason, vehicle_id, driver_id, supplier_id, filled_at, odometer_km, liters, unit_price,
                                              amount, full_tank, receipt_reference, entered_by, created_at)
                        VALUES (:id, 'Reprise de l''ancienne application (plein saisi sans bon)', :v, :d, :s, :at, :km, :l, :p, :a, false, :ref, :me, :c)""",
                        new MapSqlParameterSource("id", id).addValue("v", v).addValue("d", drivers.get(longOrNull(f.get("driver_id"))))
                                .addValue("s", supplier(orDefault(str(f.get("station")), "Station non renseignée")))
                                .addValue("at", Timestamp.from(d.atTime(12, 0).atZone(DOUALA).toInstant()))
                                .addValue("km", km != null && km > 0 ? km : null).addValue("l", liters).addValue("p", price).addValue("a", amount)
                                .addValue("ref", "ANCIEN-" + sid).addValue("me", me).addValue("c", timestamp(f.get("created_at"))));
                map("fuel_fillups", sid, id);
                count("pleins");
            }
        }

        UUID supplier(String name) {
            String key = name.strip().toUpperCase(Locale.ROOT);
            return suppliers.computeIfAbsent(key, k -> {
                List<UUID> found = jdbc.queryForList("SELECT id FROM supplier WHERE upper(btrim(name)) = :n AND kind = 'STATION'",
                        new MapSqlParameterSource("n", k), UUID.class);
                if (!found.isEmpty()) return found.get(0);
                String base = "ST-" + ascii(k).replaceAll("[^A-Z0-9]+", "-").replaceAll("^-|-$", "");
                if (base.length() > 40) base = base.substring(0, 40);
                String code = base;
                for (int i = 2; jdbc.queryForObject("SELECT count(*) FROM supplier WHERE code = :c", new MapSqlParameterSource("c", code), Integer.class) > 0; i++) {
                    code = base + "-" + i;
                }
                UUID id = UUID.randomUUID();
                jdbc.update("INSERT INTO supplier(id, code, name, kind) VALUES (:id, :c, :n, 'STATION')",
                        new MapSqlParameterSource("id", id).addValue("c", code).addValue("n", name.strip()));
                count("stations");
                return id;
            });
        }

        // -------------------------------------------------------------- budget

        void budget() {
            for (Map<String, Object> b : data.rows("budget_plan")) {
                long sid = id(b);
                if (isMapped("budget_plan", sid)) {
                    count("budget_deja_repris");
                    continue;
                }
                String domain = str(b.get("domaine"));
                Integer year = integer(b.get("annee"));
                String task = str(b.get("tache"));
                if (domain != null && !"parc_auto".equals(domain) || year == null || year < 2000 || year > 2100 || task == null) {
                    archive("budget_plan", sid, b);
                    map("budget_plan", sid, null);
                    count("budget_hors_parc_archive");
                    continue;
                }
                BigDecimal[] q = {money0(b.get("q1_montant")), money0(b.get("q2_montant")), money0(b.get("q3_montant")), money0(b.get("q4_montant"))};
                BigDecimal annual = money0(b.get("montant_annuel"));
                BigDecimal sum = q[0].add(q[1]).add(q[2]).add(q[3]);
                if (sum.signum() == 0 && annual.signum() > 0) {
                    BigDecimal quarter = annual.divide(BigDecimal.valueOf(4), 2, RoundingMode.DOWN);
                    q = new BigDecimal[]{quarter, quarter, quarter, annual.subtract(quarter.multiply(BigDecimal.valueOf(3)))};
                    count("budget_reparti_par_trimestre");
                } else if (annual.signum() > 0 && sum.subtract(annual).abs().compareTo(BigDecimal.ONE) > 0) {
                    warnings.add("Budget « " + cut(task, 60) + " » : total annuel " + annual.toPlainString() + " ≠ somme des trimestres "
                            + sum.toPlainString() + " ; les trimestres sont repris.");
                }
                UUID id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO budget_plan_line(id, fiscal_year, org_unit_id, category_code, activity, task, q1_amount, q2_amount,
                                                     q3_amount, q4_amount, source_file, source_row, import_batch_id)
                        VALUES (:id, :y, :o, :c, :a, :t, :q1, :q2, :q3, :q4, :f, :r, :imp)""",
                        new MapSqlParameterSource("id", id).addValue("y", year).addValue("o", org)
                                .addValue("c", maintenanceCategory(str(b.get("categorie_std")))).addValue("a", str(b.get("activite")))
                                .addValue("t", task).addValue("q1", q[0]).addValue("q2", q[1]).addValue("q3", q[2]).addValue("q4", q[3])
                                .addValue("f", orDefault(str(b.get("source_fichier")), SOURCE)).addValue("r", sid).addValue("imp", importId));
                map("budget_plan", sid, id);
                count("lignes_budget");
            }
            if (counts.getOrDefault("budget_hors_parc_archive", 0) > 0) {
                warnings.add(counts.get("budget_hors_parc_archive") + " ligne(s) de budget hors parc automobile (bâtiment, fournitures…) : "
                        + "conservées dans l'archive de reprise, hors du budget du parc.");
            }
        }

        // -------------------------------------------------------------- archives et comptes

        void archives() {
            for (String t : List.of("audit_log", "chat_history", "documents", "tires", "odometer")) {
                for (Map<String, Object> r : data.rows(t)) {
                    long sid = id(r);
                    if (isMapped(t, sid)) continue;
                    archive(t, sid, r);
                    map(t, sid, null);
                    count(switch (t) {
                        case "audit_log" -> "journal_ancien_archive";
                        case "chat_history" -> "assistant_ancien_archive";
                        default -> t + "_archives";
                    });
                }
            }
        }

        /**
         * Comptes de l'ancienne application : recréés SANS rôle, avec un mot de passe provisoire (les anciennes empreintes
         * ne sont pas compatibles et ne sont pas reprises). L'administrateur attribue ensuite les rôles. Comptes de test écartés.
         */
        void users() {
            for (Map<String, Object> u : data.rows("users")) {
                long sid = id(u);
                if (isMapped("users", sid)) {
                    count("comptes_deja_repris");
                    continue;
                }
                Map<String, Object> safe = new LinkedHashMap<>(u);
                safe.remove("password_hash");
                archive("users", sid, safe);
                String name = orDefault(str(u.get("username")), "compte-" + sid);
                String login = login(name);
                if (login.contains("test")) {
                    map("users", sid, null);
                    count("comptes_test_ecartes");
                    warnings.add("Compte « " + name + " » : compte de test, non recréé.");
                    continue;
                }
                Integer taken = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE login_normalized = :l",
                        new MapSqlParameterSource("l", login), Integer.class);
                if (taken != null && taken > 0) {
                    map("users", sid, null);
                    count("comptes_deja_presents");
                    warnings.add("Compte « " + name + " » : l'identifiant « " + login + " » existe déjà dans NexFleet, non recréé.");
                    continue;
                }
                String email = str(u.get("email"));
                AccountService.Created c = accounts.create(login, titleCase(name), email != null && EMAIL.matcher(email).matches() ? email : null,
                        null, org, List.of(), null, null);
                map("users", sid, c.user().id());
                created.add(new CreatedAccount(login, c.user().displayName(), c.temporaryPassword()));
                count("comptes");
            }
            if (counts.getOrDefault("comptes", 0) > 0) {
                warnings.add(counts.get("comptes") + " compte(s) recréé(s) sans rôle : attribuez les rôles dans Comptes et rôles, "
                        + "et remettez à chacun son mot de passe provisoire.");
            }
        }

        // -------------------------------------------------------------- correspondances

        UUID vehicle(Object legacyId, String table, long sid) {
            Long lid = longOrNull(legacyId);
            UUID v = lid == null ? null : vehicles.get(lid);
            if (v == null) {
                archive(table, sid, Map.of("raison", "véhicule d'origine introuvable", "vehicle_id", String.valueOf(legacyId)));
                map(table, sid, null);
                count(table + "_sans_vehicule");
                warnings.add("Ligne " + table + " n° " + sid + " : véhicule d'origine introuvable, archivée.");
            }
            return v;
        }

        UUID mapped(String table, long sid) {
            List<UUID> r = jdbc.queryForList("SELECT target_id FROM legacy_map WHERE source = :s AND table_name = :t AND source_id = :id AND target_id IS NOT NULL",
                    new MapSqlParameterSource("s", SOURCE).addValue("t", table).addValue("id", sid), UUID.class);
            return r.isEmpty() ? null : r.get(0);
        }

        boolean isMapped(String table, long sid) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM legacy_map WHERE source = :s AND table_name = :t AND source_id = :id",
                    new MapSqlParameterSource("s", SOURCE).addValue("t", table).addValue("id", sid), Integer.class);
            return n != null && n > 0;
        }

        void map(String table, long sid, UUID target) {
            jdbc.update("""
                    INSERT INTO legacy_map(source, table_name, source_id, target_id, import_id) VALUES (:s, :t, :id, :target, :imp)
                    ON CONFLICT DO NOTHING""",
                    new MapSqlParameterSource("s", SOURCE).addValue("t", table).addValue("id", sid).addValue("target", target).addValue("imp", importId));
        }

        void archive(String table, long sid, Map<String, ?> payload) {
            jdbc.update("""
                    INSERT INTO legacy_archive(import_id, table_name, source_id, payload) VALUES (:imp, :t, :id, CAST(:p AS jsonb))
                    ON CONFLICT DO NOTHING""",
                    new MapSqlParameterSource("imp", importId).addValue("t", table).addValue("id", sid).addValue("p", toJson(payload)));
        }

        void count(String key) {
            counts.merge(key, 1, Integer::sum);
        }
    }

    // ------------------------------------------------------------------ conversions

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static long id(Map<String, Object> row) {
        Long l = longOrNull(row.get("id"));
        if (l == null) throw new IllegalArgumentException("Ligne sans identifiant dans l'ancienne base.");
        return l;
    }

    static Long longOrNull(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && s.strip().matches("-?\\d+")) return Long.parseLong(s.strip());
        return null;
    }

    static Integer integer(Object o) {
        if (o instanceof Number n) return (int) Math.round(n.doubleValue());
        if (o instanceof String s) {
            try {
                return (int) Math.round(Double.parseDouble(s.strip().replace(',', '.')));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static BigDecimal decimal(Object o) {
        if (o instanceof Number n) return new BigDecimal(n.toString());
        if (o instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.strip().replace(" ", "").replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static BigDecimal money(Object o) {
        BigDecimal d = decimal(o);
        return d == null ? null : d.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal money0(Object o) {
        BigDecimal d = money(o);
        return d == null ? BigDecimal.ZERO : d;
    }

    static boolean flag(Object o) {
        Long l = longOrNull(o);
        return l != null && l != 0;
    }

    static boolean blank(Object o) {
        if (o == null) return true;
        String s = o.toString().strip();
        return s.isEmpty() || s.equalsIgnoreCase("undefined") || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("none");
    }

    static String str(Object o) {
        return blank(o) ? null : o.toString().strip();
    }

    static String orDefault(String s, String d) {
        return s == null ? d : s;
    }

    static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    static LocalDate date(Object o) {
        String s = str(o);
        if (s == null) return null;
        Matcher m = DAY.matcher(s);
        if (!m.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Timestamp timestamp(Object o) {
        String s = str(o);
        if (s == null) return Timestamp.from(java.time.Instant.now());
        try {
            return Timestamp.from(LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s).atZone(DOUALA).toInstant());
        } catch (RuntimeException e) {
            LocalDate d = date(s);
            return Timestamp.from((d == null ? java.time.Instant.now() : d.atTime(12, 0).atZone(DOUALA).toInstant()));
        }
    }

    static String ascii(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    static String norm(String s) {
        return s == null ? "" : ascii(s).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** « NICOLAS DJIMELI » → « nicolas.djimeli » (règle des identifiants NexFleet). */
    static String login(String name) {
        String l = ascii(name).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", ".").replaceAll("[^a-z0-9._@-]", "");
        l = l.replaceAll("^[^a-z0-9]+", "");
        if (l.length() < 3) l = (l + "-ancien").substring(0, Math.max(3, l.length() + 7));
        return l.length() > 64 ? l.substring(0, 64) : l;
    }

    static String titleCase(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : name.strip().toLowerCase(Locale.FRANCE).split("\\s+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    static String energy(String s) {
        return switch (norm(s)) {
            case "diesel", "gasoil", "gazole" -> "DIESEL";
            case "essence", "super" -> "ESSENCE";
            case "hybride" -> "HYBRIDE";
            case "electrique" -> "ELECTRIQUE";
            case "gpl" -> "GPL";
            default -> "AUTRE";
        };
    }

    static String status(String s) {
        return switch (norm(s)) {
            case "en service", "en mission" -> "IN_SERVICE";
            case "en maintenance", "en reparation" -> "MAINTENANCE";
            case "en panne", "hors service", "immobilise" -> "OUT_OF_SERVICE";
            default -> "AVAILABLE";
        };
    }

    static String category(String make, String model) {
        String t = norm((make == null ? "" : make) + " " + (model == null ? "" : model));
        for (String k : List.of("hilux", "pick", "cabine", "l200", "navara", "ranger", "d-max")) if (t.contains(k)) return "Pick-up";
        for (String k : List.of("rav", "prado", "fortuner", "cruiser")) if (t.contains(k)) return "SUV";
        for (String k : List.of("pegas", "yaris", "corolla", "camry")) if (t.contains(k)) return "Berline";
        if (t.contains("bus")) return "Minibus";
        if (t.contains("moto")) return "Moto";
        return null;
    }

    static String maintenanceCategory(String s) {
        return switch (norm(s)) {
            case "freinage" -> "FREINAGE";
            case "pneumatique", "pneus" -> "PNEUMATIQUE";
            case "moteur" -> "MOTEUR";
            case "vidange" -> "VIDANGE";
            case "batterie" -> "BATTERIE";
            case "tolerie" -> "TOLERIE";
            case "pompes" -> "POMPES";
            case "electricite" -> "ELECTRICITE";
            case "administratif" -> "ADMINISTRATIF";
            default -> "AUTRE";
        };
    }

    static String inspectionKind(String s) {
        String t = norm(s);
        if (t.contains("depart") || t.contains("avant")) return "AVANT_MISSION";
        if (t.contains("retour") || t.contains("apres")) return "APRES_MISSION";
        if (t.contains("technique") || t.contains("periodique")) return "PERIODIQUE";
        return "INOPINE";
    }

    static String inspectionResult(String s) {
        String t = norm(s);
        if (t.startsWith("non")) return "NON_CONFORME";
        if (t.equals("conforme")) return "CONFORME";
        return "RESERVES";
    }

    static String itemState(String s) {
        String t = norm(s);
        if (t.equals("conforme") || t.equals("ok")) return "OK";
        if (t.contains("surveiller")) return "A_SURVEILLER";
        if (t.startsWith("non") || t.contains("defaut")) return "DEFAUT";
        return null;
    }
}
