package com.jarvis.revived.memory;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;

import com.jarvis.core.memory.Episode;
import com.jarvis.core.memory.Fact;
import com.jarvis.core.memory.MemoryStore;
import com.jarvis.core.memory.ProtectedItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The one memory database: three tables, all values bound as parameters
 * (never concatenated into SQL).
 *
 * The WHOLE file is encrypted with SQLCipher (AES-256): facts, notes, the
 * conversation log and every index. The key comes from {@link VaultKey} and is
 * wrapped by the Android Keystore. Protected values (passwords, PINs) are
 * additionally AES-GCM encrypted with their own Keystore key before they are
 * written. Open it through {@link MemoryVault}, which also migrates the old
 * unencrypted database once.
 */
public final class SqliteMemoryStore extends SQLiteOpenHelper implements MemoryStore {

    private static final String TAG = "MemoryStore";
    /** Encrypted database. (v0.1 used an unencrypted "jarvis_memory.db"; see MemoryVault.) */
    static final String DB_NAME = "jarvis_vault.db";
    static final int DB_VERSION = 1;

    private static final String[] FACT_COLUMNS =
            {"id", "fact_key", "label", "value", "source", "created_at", "superseded_at"};

    SqliteMemoryStore(Context context, String dbName, byte[] key, boolean writeAheadLogging) {
        super(context.getApplicationContext(), dbName, key, null, DB_VERSION, 0, null, null, writeAheadLogging);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE facts ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "fact_key TEXT NOT NULL,"
                + "label TEXT NOT NULL,"
                + "value TEXT NOT NULL,"
                + "source TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL,"
                + "superseded_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_facts_key ON facts(fact_key, superseded_at)");

        db.execSQL("CREATE TABLE protected_items ("
                + "item_key TEXT PRIMARY KEY,"
                + "label TEXT NOT NULL,"
                + "value_encrypted TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE episodes ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "role TEXT NOT NULL,"
                + "text TEXT NOT NULL,"
                + "ts INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 only. Future versions migrate here, never drop user data.
    }

    // ---------------------------------------------------------------- facts

    @Override
    public Fact activeFact(String key) {
        try (Cursor c = getReadableDatabase().query("facts", FACT_COLUMNS,
                "fact_key = ? AND superseded_at = 0", new String[]{key}, null, null, "id DESC", "1")) {
            return c.moveToFirst() ? fact(c) : null;
        }
    }

    @Override
    public List<Fact> activeFacts() {
        return facts("superseded_at = 0", null);
    }

    @Override
    public List<Fact> history(String key) {
        return facts("fact_key = ?", new String[]{key});
    }

    @Override
    public long insertFact(String key, String label, String value, String source, long now) {
        ContentValues v = new ContentValues();
        v.put("fact_key", key);
        v.put("label", label);
        v.put("value", value);
        v.put("source", source == null ? "" : source);
        v.put("created_at", now);
        v.put("superseded_at", 0L);
        return getWritableDatabase().insertOrThrow("facts", null, v);
    }

    @Override
    public void supersedeFact(long id, long now) {
        ContentValues v = new ContentValues();
        v.put("superseded_at", now);
        getWritableDatabase().update("facts", v, "id = ?", new String[]{String.valueOf(id)});
    }

    @Override
    public void deleteFactRow(long id) {
        getWritableDatabase().delete("facts", "id = ?", new String[]{String.valueOf(id)});
    }

    @Override
    public void reactivateFact(long id) {
        ContentValues v = new ContentValues();
        v.put("superseded_at", 0L);
        getWritableDatabase().update("facts", v, "id = ?", new String[]{String.valueOf(id)});
    }

    @Override
    public int deleteKey(String key) {
        return getWritableDatabase().delete("facts", "fact_key = ?", new String[]{key});
    }

    // ------------------------------------------------------------- protected

    @Override
    public void putProtected(String key, String label, String value, long now) {
        ContentValues v = new ContentValues();
        v.put("item_key", key);
        v.put("label", label);
        v.put("value_encrypted", KeystoreCipher.encrypt(value)); // throws -> nothing stored
        v.put("created_at", now);
        getWritableDatabase().insertWithOnConflict("protected_items", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public ProtectedItem getProtected(String key) {
        try (Cursor c = getReadableDatabase().query("protected_items",
                new String[]{"item_key", "label", "value_encrypted", "created_at"},
                "item_key = ?", new String[]{key}, null, null, null)) {
            return c.moveToFirst() ? protectedItem(c) : null;
        }
    }

    @Override
    public List<ProtectedItem> listProtected() {
        List<ProtectedItem> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("protected_items",
                new String[]{"item_key", "label", "value_encrypted", "created_at"},
                null, null, null, null, "created_at DESC")) {
            while (c.moveToNext()) out.add(protectedItem(c));
        }
        return out;
    }

    @Override
    public boolean deleteProtected(String key) {
        return getWritableDatabase().delete("protected_items", "item_key = ?", new String[]{key}) > 0;
    }

    // -------------------------------------------------------------- episodes

    @Override
    public void appendEpisode(Episode.Role role, String text, long now) {
        ContentValues v = new ContentValues();
        v.put("role", role.name());
        v.put("text", text);
        v.put("ts", now);
        getWritableDatabase().insert("episodes", null, v);
    }

    @Override
    public List<Episode> recentEpisodes(int limit) {
        List<Episode> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("episodes", new String[]{"id", "role", "text", "ts"},
                null, null, null, null, "id DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                Episode.Role role;
                try {
                    role = Episode.Role.valueOf(c.getString(1));
                } catch (IllegalArgumentException e) {
                    role = Episode.Role.USER;
                }
                out.add(new Episode(c.getLong(0), role, c.getString(2), c.getLong(3)));
            }
        }
        return out;
    }

    @Override
    public void pruneEpisodes(int keep) {
        getWritableDatabase().execSQL(
                "DELETE FROM episodes WHERE id NOT IN (SELECT id FROM episodes ORDER BY id DESC LIMIT ?)",
                new Object[]{keep});
    }

    // ------------------------------------------------------------ everything

    @Override
    public void deleteAll() {
        inTransaction(() -> {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("facts", null, null);
            db.delete("protected_items", null, null);
            db.delete("episodes", null, null);
        });
    }

    @Override
    public void inTransaction(Runnable block) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            block.run();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // --------------------------------------------------------------- helpers

    private List<Fact> facts(String where, String[] args) {
        List<Fact> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("facts", FACT_COLUMNS, where, args, null, null,
                "created_at DESC, id DESC")) {
            while (c.moveToNext()) out.add(fact(c));
        }
        return out;
    }

    private static Fact fact(Cursor c) {
        return new Fact(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getLong(5), c.getLong(6));
    }

    private static ProtectedItem protectedItem(Cursor c) {
        String value;
        try {
            value = KeystoreCipher.decrypt(c.getString(2));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Could not decrypt a protected item");
            value = "";
        }
        return new ProtectedItem(c.getString(0), c.getString(1), value, c.getLong(3));
    }
}
