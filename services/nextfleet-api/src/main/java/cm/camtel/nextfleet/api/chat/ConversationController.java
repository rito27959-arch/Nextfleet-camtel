package cm.camtel.nextfleet.api.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    public record PostRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,80}$") String clientMessageId,
                              @NotBlank @Size(max = 8000) String body) { }

    public record ReadRequest(Long upTo) { }

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConversationService.ConversationView> list() {
        return service.list();
    }

    @GetMapping("/{id}/messages")
    public List<ConversationService.Line> messages(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after,
                                                   @RequestParam(defaultValue = "200") int limit) {
        return service.messages(id, after, limit);
    }

    /** 201 à la création, 200 pour une réémission reconnue (même message renvoyé). */
    @PostMapping("/{id}/messages")
    public ResponseEntity<ConversationService.Ack> post(@PathVariable UUID id, @Valid @RequestBody PostRequest r) {
        ConversationService.Ack a = service.post(id, r.clientMessageId(), r.body());
        return ResponseEntity.status(a.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(a);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id, @RequestBody(required = false) ReadRequest r) {
        service.markRead(id, r == null ? null : r.upTo());
    }
}
