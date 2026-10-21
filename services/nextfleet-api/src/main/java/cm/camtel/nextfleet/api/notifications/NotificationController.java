package cm.camtel.nextfleet.api.notifications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class NotificationController {

    public record ReadRequest(@NotNull @Size(max = 200) List<String> ids) { }

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/notifications")
    public List<NotificationService.NotificationView> list() {
        return service.list();
    }

    @PostMapping("/api/v1/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@Valid @RequestBody ReadRequest r) {
        service.markRead(r.ids());
    }
}
