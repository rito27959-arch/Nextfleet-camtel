package cm.camtel.nextfleet.domain.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RoleMatrixTest {

    /** La matrice Java doit être la copie exacte des INSERT role_permission des migrations (V1 + V5). */
    @Test
    void javaMatrixEqualsSqlSeeds() throws IOException {
        Path migrations = locate("infra/db/migrations");
        Map<String, Set<String>> sql = new HashMap<>();
        Set<String> sqlPermissions = new HashSet<>();
        Pattern rp = Pattern.compile("\\('([A-Z_]+)','([a-z_]+:[a-z_]+)'\\)");
        Pattern perm = Pattern.compile("\\('([a-z_]+:[a-z_]+)','");
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path f : files.sorted().toList()) {
                String text = Files.readString(f);
                int i = text.indexOf("INSERT INTO role_permission");
                while (i >= 0) {
                    String block = text.substring(i, text.indexOf(';', i));
                    Matcher m = rp.matcher(block);
                    while (m.find()) sql.computeIfAbsent(m.group(1), k -> new HashSet<>()).add(m.group(2));
                    i = text.indexOf("INSERT INTO role_permission", i + 1);
                }
                int j = text.indexOf("INSERT INTO permission(");
                while (j >= 0) {
                    Matcher m = perm.matcher(text.substring(j, text.indexOf(';', j)));
                    while (m.find()) sqlPermissions.add(m.group(1));
                    j = text.indexOf("INSERT INTO permission(", j + 1);
                }
            }
        }
        assertEquals(sql, Role.matrix(), "matrice Java ≠ base");
        assertEquals(sqlPermissions, Stream.of(Permission.values()).map(p -> p.code).collect(Collectors.toSet()));
    }

    @Test
    void structuralRulesOfTheHabilitationMatrix() {
        assertTrue(Role.ADMIN.permissions().stream().allMatch(p -> p.code.startsWith("admin:")),
                "administrateur : aucun pouvoir métier implicite");
        assertTrue(Role.AUDITOR.permissions().stream().noneMatch(p -> p.code.matches(".*:(write|create|approve|archive|manage|drive|review|validate|use)")),
                "auditeur : lecture et export seulement");
        assertFalse(Role.DRIVER.has(Permission.FUEL_ORDER_APPROVE), "conducteur : aucune auto-approbation");
        assertTrue(Role.CONTROLLER.has(Permission.ANOMALY_REVIEW));
        assertFalse(Role.CONTROLLER.has(Permission.FUEL_ORDER_CREATE), "contrôleur distinct du demandeur");
        assertEquals(Set.of(), Role.union(Set.of("INCONNU")), "code inconnu : refus par défaut");
    }

    private static Path locate(String relative) {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve(relative))) return p.resolve(relative);
        }
        String root = System.getProperty("nf.root");
        if (root != null) return Path.of(root, relative);
        throw new IllegalStateException("Dossier introuvable : " + relative);
    }
}
