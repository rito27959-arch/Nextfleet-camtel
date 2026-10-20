package cm.camtel.nextfleet.domain.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exécute un jeu de règles versionnées et dédoublonne les constatations par clé stable. */
public final class RuleEngine {

    private final List<FuelRule> rules;

    public RuleEngine(List<FuelRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RuleEngine standard() {
        return new RuleEngine(StandardFuelRules.all());
    }

    public List<Finding> run(FuelControlContext ctx) {
        Map<String, Finding> unique = new LinkedHashMap<>();
        for (FuelRule rule : rules) {
            for (Finding f : rule.evaluate(ctx)) {
                unique.putIfAbsent(f.dedupKey(), f);
            }
        }
        return new ArrayList<>(unique.values());
    }

    public List<FuelRule> rules() {
        return rules;
    }
}
