package cm.camtel.nextfleet.desktop.data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Petit cache LRU des réponses GET et de leur ETag, en mémoire seulement (rien n'est écrit sur le disque du poste). */
final class EtagCache {

    record Saved(String etag, byte[] body) { }

    private final Map<String, Saved> map;

    EtagCache(int capacity) {
        map = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Saved> e) {
                return size() > capacity;
            }
        };
    }

    synchronized Saved get(String key) {
        return map.get(key);
    }

    synchronized void put(String key, String etag, byte[] body) {
        if (body.length > 2_000_000) return; // les gros contenus ne sont pas gardés
        map.put(key, new Saved(etag, body));
    }

    synchronized void clear() {
        map.clear();
    }
}
