package cm.camtel.nextfleet.mobile;

import android.app.Application;
import android.graphics.Typeface;

import cm.camtel.nextfleet.mobile.data.Catalogue;
import cm.camtel.nextfleet.mobile.data.DemoRepository;
import cm.camtel.nextfleet.mobile.data.RemoteRepository;
import cm.camtel.nextfleet.mobile.data.Repository;
import cm.camtel.nextfleet.mobile.data.Session;
import cm.camtel.nextfleet.mobile.tracking.TrackingState;

/**
 * Application NexFleet Android (terrain) : session, dépôt de données (démonstration ou serveur), polices, catalogue 3D.
 * Le serveur reste la seule source de vérité ; l'application ne lit jamais la base directement.
 */
public final class NexFleetApp extends Application {

    private static NexFleetApp instance;
    private Repository repository;
    private Session session;
    private Typeface regular;
    private Typeface semibold;
    private Typeface display;
    private Catalogue catalogue;

    @Override
    public void onCreate() {
        super.onCreate();
        cm.camtel.nextfleet.mobile.ui.Ui.loadPreferences(this);
        instance = this;
        regular = getResources().getFont(R.font.inter_regular);
        semibold = getResources().getFont(R.font.inter_semibold);
        display = getResources().getFont(R.font.inter_display_bold);
    }

    public static NexFleetApp get() {
        return instance;
    }

    /** Dépôt selon le mode choisi à la connexion : démonstration (données fictives) ou serveur NexFleet. */
    public Repository repository(boolean demo, String apiUrl) {
        if (repository == null || repository.isDemo() != demo
                || (!demo && !apiUrl.equals(((RemoteRepository) repository).apiUrl()))) {
            repository = demo ? new DemoRepository() : new RemoteRepository(apiUrl);
        }
        return repository;
    }

    public Repository repository() {
        return repository;
    }

    public Session session() {
        return session;
    }

    public void setSession(Session s) {
        session = s;
    }

    public void logout() {
        if (repository != null) {
            try {
                repository.logout();
            } catch (RuntimeException ignored) {
                // déconnexion locale même si le serveur est injoignable
            }
        }
        TrackingState.of(this).clearSession();
        session = null;
    }

    public Catalogue catalogue() {
        if (catalogue == null) catalogue = Catalogue.load(this);
        return catalogue;
    }

    public Typeface regular() {
        return regular;
    }

    public Typeface semibold() {
        return semibold;
    }

    public Typeface display() {
        return display;
    }
}
