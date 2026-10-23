package cm.camtel.nextfleet.api.costs;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CostController {

    private final CostService service;

    public CostController(CostService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/costs")
    public CostService.Summary summary(@RequestParam(required = false) Integer year, @RequestParam(required = false) UUID orgUnitId) {
        return service.summary(year == null ? CostService.currentYear() : year, orgUnitId);
    }

    @GetMapping("/api/v1/budget-lines")
    public List<CostService.BudgetLine> budget(@RequestParam(required = false) Integer year) {
        return service.budget(year == null ? CostService.currentYear() : year);
    }

    @GetMapping("/api/v1/costs/legacy")
    public List<CostService.LegacyOperation> legacy(@RequestParam(required = false) Integer year) {
        return service.legacyOperations(year == null ? CostService.currentYear() : year);
    }

    /** Corps : le texte CSV (UTF-8). Réimport sans doublon. */
    @PostMapping(value = "/api/v1/budget-lines/import", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public CostService.ImportResult importBudget(@RequestParam int year, @RequestParam UUID orgUnitId,
                                                 @RequestParam(required = false) String sourceFile, @RequestBody String csv) {
        if (csv.length() > 2_000_000) throw new IllegalArgumentException("Fichier trop volumineux (2 Mo maximum).");
        return service.importBudget(year, orgUnitId, sourceFile, csv);
    }
}
