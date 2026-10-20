package cm.camtel.nextfleet.domain.rules;

import java.util.List;

/** Règle de contrôle versionnée. Une modification de logique ou de seuil incrémente {@link #version()}. */
public interface FuelRule {
    String id();

    int version();

    String label();

    List<Finding> evaluate(FuelControlContext ctx);
}
