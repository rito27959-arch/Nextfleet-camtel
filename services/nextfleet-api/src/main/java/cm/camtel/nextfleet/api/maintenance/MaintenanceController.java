package cm.camtel.nextfleet.api.maintenance;

import cm.camtel.nextfleet.api.maintenance.MaintenanceService.InspectionCreate;
import cm.camtel.nextfleet.api.maintenance.MaintenanceService.OilChangeCreate;
import cm.camtel.nextfleet.api.maintenance.MaintenanceService.OrderCreate;
import cm.camtel.nextfleet.api.maintenance.MaintenanceService.TireEventCreate;
import cm.camtel.nextfleet.api.maintenance.MaintenanceService.Transition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Atelier : interventions, vidanges, contrôles, pneumatiques. */
@RestController
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/maintenance/categories")
    public List<MaintenanceService.Category> categories() {
        return service.categories();
    }

    @GetMapping("/api/v1/maintenance-orders")
    public List<MaintenanceService.OrderView> orders(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) UUID vehicleId,
                                                     @RequestParam(defaultValue = "false") boolean open) {
        return service.orders(status, vehicleId, open);
    }

    @GetMapping("/api/v1/maintenance-orders/{id}")
    public ResponseEntity<MaintenanceService.OrderView> order(@PathVariable UUID id) {
        MaintenanceService.OrderView o = service.order(id);
        return ResponseEntity.ok().eTag("\"" + o.version() + "\"").body(o);
    }

    @PostMapping("/api/v1/maintenance-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceService.OrderView create(@RequestBody OrderCreate c) {
        return service.create(c);
    }

    @PostMapping("/api/v1/maintenance-orders/{id}/transition")
    public MaintenanceService.OrderView transition(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                                                   @RequestBody Transition t) {
        return service.transition(id, version(ifMatch), t);
    }

    @GetMapping("/api/v1/oil-changes")
    public List<MaintenanceService.OilChangeView> oilChanges(@RequestParam(required = false) UUID vehicleId) {
        return service.oilChanges(vehicleId);
    }

    @PostMapping("/api/v1/oil-changes")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceService.OilChangeView addOilChange(@RequestBody OilChangeCreate c) {
        return service.addOilChange(c);
    }

    @GetMapping("/api/v1/inspection-points")
    public List<MaintenanceService.InspectionPoint> points() {
        return service.points();
    }

    @GetMapping("/api/v1/inspections")
    public List<MaintenanceService.InspectionView> inspections(@RequestParam(required = false) UUID vehicleId) {
        return service.inspections(vehicleId);
    }

    @PostMapping("/api/v1/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceService.InspectionView addInspection(@RequestBody InspectionCreate c) {
        return service.addInspection(c);
    }

    @GetMapping("/api/v1/vehicles/{id}/tires")
    public List<MaintenanceService.TireView> tires(@PathVariable UUID id) {
        return service.tires(id);
    }

    @PostMapping("/api/v1/tire-events")
    @ResponseStatus(HttpStatus.CREATED)
    public List<MaintenanceService.TireView> tireEvent(@RequestBody TireEventCreate c) {
        return service.addTireEvent(c);
    }

    static long version(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("En-tête If-Match invalide");
        }
    }
}
