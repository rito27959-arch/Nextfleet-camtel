package cm.camtel.nextfleet.mobile.tracking;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Diffusion interne (même processus) des événements de collecte vers les écrans, sur le fil d'interface. */
public final class TrackingBus {

    public interface Listener {
        void onTrackingChanged(Status status);
    }

    public static final class Status {
        public final boolean running;
        public final int points, pending, accepted, rejected;
        public final String lastError;

        public Status(boolean running, int points, int pending, int accepted, int rejected, String lastError) {
            this.running = running;
            this.points = points;
            this.pending = pending;
            this.accepted = accepted;
            this.rejected = rejected;
            this.lastError = lastError;
        }
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Status last = new Status(false, 0, 0, 0, 0, null);

    private TrackingBus() { }

    public static void register(Listener l) {
        LISTENERS.add(l);
        l.onTrackingChanged(last);
    }

    public static void unregister(Listener l) {
        LISTENERS.remove(l);
    }

    public static Status last() {
        return last;
    }

    static void post(Status s) {
        last = s;
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onTrackingChanged(s);
        });
    }
}
