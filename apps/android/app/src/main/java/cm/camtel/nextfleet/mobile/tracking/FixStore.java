package cm.camtel.nextfleet.mobile.tracking;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tampon local des positions (hors ligne) : chaque point est chiffré (AES-GCM, clé du Keystore) avant écriture.
 * Seuls l'identifiant, la mission et l'horodatage restent en clair pour trier et purger. Purge automatique après
 * 7 jours (minimisation) ; un point traité par la passerelle (accepté, doublon ou refusé) n'est plus jamais renvoyé.
 */
public final class FixStore extends SQLiteOpenHelper {

    private final Crypto.Box box;

    public FixStore(Context c) {
        this(c, Crypto.keystore());
    }

    public FixStore(Context c, Crypto.Box box) {
        super(c.getApplicationContext(), "nexfleet_fixes.db", null, 1);
        this.box = box;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE fix (id TEXT PRIMARY KEY, mission TEXT NOT NULL, recorded_at INTEGER NOT NULL, "
                + "payload BLOB NOT NULL, state INTEGER NOT NULL DEFAULT 0)");   // 0 = à envoyer, 1 = traité, 2 = local (démo)
        db.execSQL("CREATE INDEX ix_fix_mission ON fix(mission, recorded_at)");
        db.execSQL("CREATE INDEX ix_fix_state ON fix(state, recorded_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // version 1 : rien à migrer
    }

    public void add(String missionId, FixCodec.Fix f, boolean localOnly) {
        try {
            ContentValues v = new ContentValues();
            v.put("id", f.clientFixId);
            v.put("mission", missionId);
            v.put("recorded_at", f.recordedAt.toEpochMilli());
            v.put("payload", box.seal(FixCodec.encode(f)));
            v.put("state", localOnly ? 2 : 0);
            getWritableDatabase().insertWithOnConflict("fix", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        } catch (GeneralSecurityException | JSONException e) {
            throw new IllegalStateException("Position non enregistrée", e);
        }
    }

    /** Points à envoyer, les plus anciens d'abord. */
    public List<FixCodec.Fix> pending(String missionId, int limit) {
        return query("mission = ? AND state = 0", new String[]{missionId}, limit);
    }

    /** Trace de la mission (tous états), pour l'affichage sur l'appareil. */
    public List<FixCodec.Fix> track(String missionId) {
        return query("mission = ?", new String[]{missionId}, 20_000);
    }

    public int pendingCount(String missionId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM fix WHERE mission = ? AND state = 0", new String[]{missionId})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void settle(List<String> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("state", 1);
            for (String id : ids) db.update("fix", v, "id = ?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void purgeOlderThan(Duration age) {
        long limit = Instant.now().minus(age).toEpochMilli();
        getWritableDatabase().delete("fix", "recorded_at < ? AND state <> 0", new String[]{String.valueOf(limit)});
    }

    private List<FixCodec.Fix> query(String where, String[] args, int limit) {
        List<FixCodec.Fix> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("fix", new String[]{"payload"}, where, args, null, null, "recorded_at", String.valueOf(limit))) {
            while (c.moveToNext()) {
                try {
                    out.add(FixCodec.decode(box.open(c.getBlob(0))));
                } catch (GeneralSecurityException | JSONException e) {
                    // point illisible (clé changée après réinstallation) : ignoré, jamais déchiffré en clair ailleurs
                }
            }
        }
        return out;
    }
}
