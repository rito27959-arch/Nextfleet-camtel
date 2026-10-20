package cm.camtel.nextfleet.mobile.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Catalogue 3D embarqué (assets/catalogue.json, partagé avec le bureau) : modèles à licence redistribuable. */
public final class Catalogue {

    public static final class Entry {
        public final String id, label, category, file, licence, author, source;

        Entry(String id, String label, String category, String file, String licence, String author, String source) {
            this.id = id;
            this.label = label;
            this.category = category;
            this.file = file;
            this.licence = licence;
            this.author = author;
            this.source = source;
        }
    }

    public final List<Entry> entries;

    private Catalogue(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static Catalogue load(Context context) {
        try (InputStream in = context.getAssets().open("catalogue.json")) {
            return parse(read(in));
        } catch (IOException | JSONException e) {
            return new Catalogue(List.of());
        }
    }

    public static Catalogue parse(String json) throws JSONException {
        JSONArray a = new JSONObject(json).getJSONArray("models");
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.getJSONObject(i);
            if (!m.optBoolean("redistributable", false)) continue;   // jamais de modèle non redistribuable
            out.add(new Entry(m.getString("id"), m.getString("label"), m.optString("category", ""), m.getString("file"),
                    m.optString("licence", ""), m.optString("author", ""), m.optString("source", "")));
        }
        return new Catalogue(out);
    }

    /** Modèle d'une catégorie de véhicule (Pick-up, SUV, 4x4, Berline, Minibus), sinon le premier. */
    public Entry forCategory(String category) {
        if (category != null) {
            String c = category.toLowerCase(Locale.ROOT);
            for (Entry e : entries) if (e.category.toLowerCase(Locale.ROOT).equals(c)) return e;
        }
        return entries.isEmpty() ? null : entries.get(0);
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
