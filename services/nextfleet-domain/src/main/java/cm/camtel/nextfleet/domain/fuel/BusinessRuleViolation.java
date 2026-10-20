package cm.camtel.nextfleet.domain.fuel;

/** Violation d'une règle métier identifiée ; l'API la traduit en HTTP 409/422 avec le code de règle. */
public class BusinessRuleViolation extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String ruleCode;

    public BusinessRuleViolation(String ruleCode, String message) {
        super(ruleCode + " : " + message);
        this.ruleCode = ruleCode;
    }

    public String ruleCode() {
        return ruleCode;
    }
}
