package cm.camtel.nextfleet.api.legacy;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Reprise de l'ancienne application : aperçu (rien n'est écrit), import confirmé par l'empreinte, historique. Administrateur seulement. */
@RestController
@RequestMapping("/api/v1/admin/legacy-import")
public class LegacyImportController {

    private final LegacyImportService service;

    public LegacyImportController(LegacyImportService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public LegacyImportService.Report preview(@RequestPart("file") MultipartFile file) throws IOException {
        return service.preview(file.getOriginalFilename(), file.getBytes());
    }

    /** Les mots de passe provisoires des comptes recréés ne s'affichent qu'ici, une seule fois. */
    @PostMapping
    public ResponseEntity<LegacyImportService.Report> commit(@RequestPart("file") MultipartFile file, @RequestParam("sha256") String sha256)
            throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.commit(file.getOriginalFilename(), file.getBytes(), sha256));
    }

    @GetMapping
    public List<LegacyImportService.PastImport> history() {
        return service.history();
    }
}
