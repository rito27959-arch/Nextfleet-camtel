package cm.camtel.nextfleet.api.files;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Pièces justificatives : dépôt (multipart, champ « file »), métadonnées et téléchargement contrôlé. */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService service;

    public FileController(FileService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileService.FileView> upload(@RequestParam String purpose,
                                                       @RequestParam(required = false) UUID orgUnitId,
                                                       @RequestParam(required = false) UUID vehicleId,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > FileService.MAX_BYTES) throw new IllegalArgumentException("Fichier trop volumineux (10 Mo maximum).");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.upload(purpose, orgUnitId, vehicleId, file.getOriginalFilename(), file.getBytes()));
    }

    @GetMapping("/{id}")
    public FileService.FileView meta(@PathVariable UUID id) {
        return service.metadata(id);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        FileService.FileContent f = service.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.meta().mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.meta().originalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ETAG, "\"" + f.meta().sha256() + "\"")
                .body(f.content());
    }
}
