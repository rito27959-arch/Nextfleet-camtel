package cm.camtel.nextfleet.desktop.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Catalogue 3D embarqué (shared/assets3d/catalogue.json, copié dans les ressources). Règle du cahier : un modèle dont
 * la licence n'autorise pas la redistribution n'est jamais chargé, même s'il figure par erreur dans le fichier.
 */
public final class ModelCatalogue {

    public static final String BASE = "/cm/camtel/nextfleet/desktop/assets3d/";

    public record Entry(String id, String label, String category, String file, String licence, String spdx, String author,
                        String source, List<String> matches) { }

    private final List<Entry> entries;

    private ModelCatalogue(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static ModelCatalogue load() throws IOException {
        try (InputStream in = ModelCatalogue.class.getResourceAsStream(BASE + "catalogue.json")) {
            if (in == null) throw new IOException("Catalogue 3D absent des ressources");
            return parse(in.readAllBytes());
        }
    }

    public static ModelCatalogue parse(byte[] json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(json);
        List<Entry> out = new ArrayList<>();
        for (JsonNode m : root.path("models")) {
            if (!m.path("redistributable").asBoolean(false)) continue;
            String file = m.path("file").asText("");
            if (file.isEmpty() || file.contains("..") || file.startsWith("/")) continue;
            List<String> matches = new ArrayList<>();
            m.path("matches").forEach(x -> matches.add(x.asText()));
            out.add(new Entry(m.path("id").asText(), m.path("label").asText(), m.path("category").asText(), file,
                    m.path("licence").asText(), m.path("spdx").asText(), m.path("author").asText(), m.path("source").asText(), matches));
        }
        return new ModelCatalogue(out);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Modèle d'une catégorie de véhicule (Pick-up, SUV…), sinon d'après la marque et le modèle. */
    public Optional<Entry> forVehicle(String category, String makeModel) {
        if (category != null) {
            for (Entry e : entries) if (e.category().equalsIgnoreCase(category)) return Optional.of(e);
        }
        if (makeModel != null) {
            String mm = makeModel.toLowerCase(Locale.ROOT);
            for (Entry e : entries) {
                for (String x : e.matches()) if (mm.contains(x.toLowerCase(Locale.ROOT))) return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    public byte[] bytes(Entry e) throws IOException {
        try (InputStream in = ModelCatalogue.class.getResourceAsStream(BASE + e.file())) {
            if (in == null) throw new IOException("Fichier 3D absent : " + e.file());
            return in.readAllBytes();
        }
    }
}
