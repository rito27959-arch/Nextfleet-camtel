package cm.camtel.nextfleet.api.dues;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class DueController {

    public record DocumentCreate(@NotBlank String docType, @Size(max = 80) String reference, LocalDate validFrom,
                                 @NotNull LocalDate validUntil, UUID fileId) { }

    private final DueService service;

    public DueController(DueService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/due-dates")
    public List<DueService.DueView> upcoming(@RequestParam(defaultValue = "60") int days) {
        return service.upcoming(days);
    }

    @GetMapping("/api/v1/vehicles/{id}/due-dates")
    public List<DueService.DueView> vehicle(@PathVariable UUID id) {
        return service.forVehicle(id);
    }

    @GetMapping("/api/v1/vehicles/{id}/documents")
    public List<DueService.DocumentView> documents(@PathVariable UUID id) {
        return service.documents(id);
    }

    @PostMapping("/api/v1/vehicles/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DueService.DocumentView addDocument(@PathVariable UUID id, @Valid @RequestBody DocumentCreate d) {
        return service.addDocument(id, d.docType(), d.reference(), d.validFrom(), d.validUntil(), d.fileId());
    }
}
