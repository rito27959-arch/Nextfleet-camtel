package cm.camtel.nextfleet.api.assistant;

import cm.camtel.nextfleet.domain.assistant.FleetAssistant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    public record AskRequest(@NotBlank @Size(max = 2000) String question) { }

    public record DecisionRequest(boolean enable, @Size(max = 80) String securityReviewRef, @Size(max = 120) String approvedBy,
                                  @NotBlank @Size(max = 500) String reason) { }

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @GetMapping("/activation")
    public AssistantService.ActivationView activation() {
        return service.activation();
    }

    @PostMapping("/activation")
    public AssistantService.ActivationView decide(@Valid @RequestBody DecisionRequest r) {
        return service.decide(r.enable(), r.securityReviewRef(), r.approvedBy(), r.reason());
    }

    @PostMapping("/ask")
    public FleetAssistant.Answer ask(@Valid @RequestBody AskRequest r) {
        return service.ask(r.question());
    }
}
