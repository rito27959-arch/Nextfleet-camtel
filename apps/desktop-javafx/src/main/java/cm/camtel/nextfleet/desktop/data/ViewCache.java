package cm.camtel.nextfleet.desktop.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dernières données affichées par chaque écran, pour le réafficher instantanément quand on y revient
 * (« affichage immédiat puis actualisation », comme les apps iOS). En mémoire uniquement, propre à la session :
 * vidé à la connexion, à la déconnexion et après chaque modification acceptée par le serveur.
 */
public final class ViewCache {

    private ViewCache() { }

    private static final Map<String, Object> DATA = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static volatile boolean enabled;

    /** Active (serveur réel) ou désactive (démonstration, déconnexion) le cache, en le vidant. */
    public static void reset(boolean enable) {
        DATA.clear();
        GENERATION.incrementAndGet();
        enabled = enable;
    }

    public static void invalidate() {
        DATA.clear();
        GENERATION.incrementAndGet();
    }

    public static long generation() {
        return GENERATION.get();
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        return enabled ? (T) DATA.get(key) : null;
    }

    /** Ne garde la valeur que si aucune modification n'est intervenue depuis le lancement de l'appel. */
    public static void put(String key, Object value, long generationAtStart) {
        if (enabled && value != null && GENERATION.get() == generationAtStart) DATA.put(key, value);
    }
}
