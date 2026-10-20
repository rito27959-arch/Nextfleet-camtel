package cm.camtel.nextfleet.api.live;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/fleet")
public class FleetLiveController {

    private final FleetLiveService service;
    private final LiveFeedListener feed;
    private final LiveSocketHandler socket;
    private final TraccarClient traccar;

    public FleetLiveController(FleetLiveService service, LiveFeedListener feed, LiveSocketHandler socket, TraccarClient traccar) {
        this.service = service;
        this.feed = feed;
        this.socket = socket;
        this.traccar = traccar;
    }

    /** Dernière position et état de chaque véhicule en mission du périmètre. */
    @GetMapping("/live")
    public List<FleetLiveService.VehicleLive> live() {
        return service.live();
    }

    /** État des composants temps réel (pour l'écran Réglages et la supervision). */
    @GetMapping("/live/status")
    public Map<String, Object> status() {
        return Map.of("databaseFeed", feed.connected(), "liveClients", socket.clientCount(),
                "traccarConfigured", traccar.configured(), "liveThresholdSeconds", 120);
    }
}
