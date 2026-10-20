package cm.camtel.nextfleet.mobile.tracking;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import cm.camtel.nextfleet.mobile.R;
import cm.camtel.nextfleet.mobile.ui.MainActivity;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Collecteur GPS de mission (service de premier plan, type « location », notification permanente).
 * Il ne tourne qu'entre le démarrage et la clôture d'une mission autorisée : sans mission active, il s'arrête aussitôt.
 * Positions → tampon chiffré → envoi par lots à la passerelle NexFleet (qui relaie à Traccar). Hors réseau, le
 * tampon conserve les points ; ils partent au retour du réseau. Les positions simulées (« mock ») sont ignorées.
 */
public final class TrackingService extends Service implements LocationListener {

    public static final String CHANNEL = "mission";
    private static final int NOTIFICATION_ID = 7001;
    private static final long UPLOAD_EVERY_MS = 20_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LocationManager locations;
    private FixStore store;
    private TrackingState state;
    private int points, accepted, rejected, mocks;
    private String lastError;
    private boolean running;

    private final Runnable uploadTick = new Runnable() {
        @Override
        public void run() {
            upload();
            handler.postDelayed(this, UPLOAD_EVERY_MS);
        }
    };

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, TrackingService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, TrackingService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locations = getSystemService(LocationManager.class);
        store = new FixStore(this);
        state = TrackingState.of(this);
        createChannel(this);
    }

    public static void createChannel(Context c) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.channel_mission), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.channel_mission_desc));
        ch.setShowBadge(false);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!state.active() || !hasLocationPermission()) {   // règle cardinale : pas de mission active, pas de collecte
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        if (!running) {
            running = true;
            requestUpdates();
            handler.postDelayed(uploadTick, UPLOAD_EVERY_MS);
            store.purgeOlderThan(Duration.ofDays(7));
            publish();
        }
        return START_STICKY;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestUpdates() {
        if (!hasLocationPermission()) return;
        try {
            if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000, 5f, this, Looper.getMainLooper());
            }
            if (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15_000, 25f, this, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            lastError = "Autorisation de localisation retirée";
            stopSelf();
        }
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle("Mission " + state.reference() + " en cours")
                .setContentText("Localisation active jusqu'à la clôture de la mission")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(open)
                .build();
    }

    // ------------------------------------------------------------------ positions

    @Override
    public void onLocationChanged(Location l) {
        String mission = state.missionId();
        Instant started = state.startedAt();
        if (mission == null || started == null) {
            stopSelf();
            return;
        }
        boolean mock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? l.isMock() : isFromMockProvider(l);
        if (mock) {   // contrôle anti-fraude : une position simulée n'est jamais enregistrée
            mocks++;
            lastError = mocks + " position(s) simulée(s) ignorée(s)";
            publish();
            return;
        }
        Instant at = Instant.ofEpochMilli(l.getTime());
        if (at.isBefore(started)) return;   // relevé antérieur au démarrage : hors mission
        FixCodec.Fix f = new FixCodec.Fix(UUID.randomUUID().toString(), at, l.getLatitude(), l.getLongitude(),
                l.hasAccuracy() ? l.getAccuracy() : 50.0, l.hasSpeed() ? (double) l.getSpeed() : null,
                l.hasBearing() ? (double) l.getBearing() : null);
        store.add(mission, f, state.demo());
        points++;
        publish();
    }

    @SuppressWarnings("deprecation")
    private static boolean isFromMockProvider(Location l) {
        return l.isFromMockProvider();
    }

    // Méthodes abstraites avant Android 11 : implémentées explicitement pour les appareils anciens.
    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // sans objet
    }

    @Override
    public void onProviderEnabled(String provider) {
        lastError = null;
        publish();
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            lastError = "GPS désactivé sur le téléphone";
            publish();
        }
    }

    // ------------------------------------------------------------------ envoi

    private void upload() {
        final String mission = state.missionId();
        final String token = state.token();
        final String gateway = state.gateway();
        if (state.demo() || mission == null || token == null || gateway == null) return;
        io.execute(() -> {
            List<FixCodec.Fix> batch = store.pending(mission, 200);
            if (batch.isEmpty()) return;
            try {
                Uploader.Result r = Uploader.send(gateway, token, batch);
                store.settle(r.settled);
                accepted += r.accepted + r.duplicates;
                rejected += r.rejected;
                lastError = null;
            } catch (Uploader.AuthException e) {
                lastError = e.getMessage();
            } catch (IOException e) {
                lastError = "Hors réseau : positions conservées sur le téléphone";
            }
            publish();
        });
    }

    private void publish() {
        String mission = state.missionId();
        int pending = mission == null ? 0 : store.pendingCount(mission);
        TrackingBus.post(new TrackingBus.Status(running, points, pending, accepted, rejected, lastError));
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(uploadTick);
        try {
            locations.removeUpdates(this);
        } catch (SecurityException ignored) {
            // déjà retiré
        }
        io.shutdown();
        TrackingBus.post(new TrackingBus.Status(false, points, 0, accepted, rejected, lastError));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Après la clôture : envoie les points relevés pendant la mission et restés en tampon (jeton de vidange).
     * Appelé à la clôture puis à chaque ouverture de l'application tant qu'il reste des points.
     */
    public static void drain(Context c) {
        TrackingState st = TrackingState.of(c);
        String mission = st.drainMission(), token = st.drainToken(), gateway = st.drainGateway();
        if (mission == null || token == null || gateway == null) return;
        new Thread(() -> {
            FixStore store = new FixStore(c);
            try {
                for (int i = 0; i < 50; i++) {
                    List<FixCodec.Fix> batch = store.pending(mission, 200);
                    if (batch.isEmpty()) {
                        st.drained();
                        return;
                    }
                    store.settle(Uploader.send(gateway, token, batch).settled);
                }
            } catch (Uploader.AuthException e) {
                st.drained();   // jeton expiré : la passerelle n'accepterait plus ces points
            } catch (IOException e) {
                // hors réseau : nouvel essai à la prochaine ouverture
            } finally {
                store.close();
            }
        }, "nexfleet-drain").start();
    }
}
