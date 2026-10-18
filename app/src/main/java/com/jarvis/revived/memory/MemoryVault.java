package com.jarvis.revived.memory;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;

import com.jarvis.core.memory.MemoryStore;
import com.jarvis.core.memory.VolatileMemoryStore;

import java.io.File;
import java.security.GeneralSecurityException;
import java.security.ProviderException;

/**
 * Opens the encrypted memory vault. Call it off the main thread (Keystore
 * work, decryption and the one-time migration take a moment).
 *
 *  - first run of this version: copies the old UNENCRYPTED v0.1 database
 *    ("jarvis_memory.db") into the encrypted vault, then deletes the old file;
 *  - the vault key is certainly unusable (damaged key file, Keystore key gone
 *    or invalidated): the unreadable vault is deleted, a fresh one is started,
 *    and the user is told;
 *  - a Keystore error that may be temporary: memory stays in RAM for this
 *    session (the user is told nothing will be saved) and the vault is tried
 *    again next launch; after 3 such launches in a row it is replaced.
 *
 * Memory is never written unencrypted.
 */
public final class MemoryVault {

    private static final String TAG = "MemoryVault";
    static final String LEGACY_DB = "jarvis_memory.db";
    private static final String MIGRATING_DB = SqliteMemoryStore.DB_NAME + ".migrating";
    private static final String PREFS = "jarvis_vault";
    private static final String KEY_UNLOCK_FAILURES = "unlock_failures";
    private static final int MAX_UNLOCK_FAILURES = 3;
    private static final String FRESH_VAULT_NOTICE =
            "I couldn't unlock my saved memory on this phone, so I started a fresh, encrypted one.";

    private static volatile boolean nativeLoaded;

    /** The opened store plus anything the user should be told about it. */
    public static final class Opened {
        public final MemoryStore store;
        /** True when memory is encrypted on disk; false only for the RAM-only fallback. */
        public final boolean encrypted;
        /** One sentence for the conversation (e.g. "Your old memory was moved into the encrypted vault."), or "". */
        public final String notice;

        Opened(MemoryStore store, boolean encrypted, String notice) {
            this.store = store;
            this.encrypted = encrypted;
            this.notice = notice == null ? "" : notice;
        }
    }

    private MemoryVault() {
    }

    public static Opened open(Context context) {
        Context app = context.getApplicationContext();
        try {
            loadNative();
            String notice = "";

            if (app.getDatabasePath(SqliteMemoryStore.DB_NAME).exists() && !VaultKey.exists(app)) {
                // A vault whose key file is gone can never be opened again.
                Log.e(TAG, "Vault without a key file; starting a new vault");
                discardVault(app);
            }

            byte[] key;
            try {
                key = keyWithRetry(app);
                recordUnlock(app, true);
            } catch (VaultKey.LockedException certainlyGone) {
                Log.e(TAG, "Vault key can't be unwrapped any more; starting a new vault", certainlyGone);
                key = startFresh(app);
                notice = FRESH_VAULT_NOTICE;
            } catch (GeneralSecurityException | ProviderException maybeTemporary) {
                // Don't give up saved memory over what may be a passing Keystore problem: RAM only this
                // time and try again next launch. Only if it keeps failing is the vault replaced.
                if (recordUnlock(app, false) < MAX_UNLOCK_FAILURES) throw maybeTemporary;
                Log.e(TAG, "Vault key failed " + MAX_UNLOCK_FAILURES + " launches in a row; starting a new vault",
                        maybeTemporary);
                key = startFresh(app);
                notice = FRESH_VAULT_NOTICE;
            }

            String migrated = migrateLegacy(app, key);
            if (!migrated.isEmpty()) notice = notice.isEmpty() ? migrated : notice + " " + migrated;

            SqliteMemoryStore store = new SqliteMemoryStore(app, SqliteMemoryStore.DB_NAME, key, true);
            store.getWritableDatabase(); // open (and decrypt) now, so a problem shows up here, not mid-conversation
            return new Opened(store, true, notice);
        } catch (Throwable t) {
            Log.e(TAG, "Encrypted vault unavailable; memory will not be saved", t);
            return new Opened(new VolatileMemoryStore(), false,
                    "Secure storage isn't working on this phone right now, so I won't save anything you tell me "
                            + "this time.");
        }
    }

    /** True for the migration test in the UI / diagnostics: does an unencrypted v0.1 file still exist? */
    public static boolean hasLegacyPlaintext(Context context) {
        return context.getApplicationContext().getDatabasePath(LEGACY_DB).exists();
    }

    // ------------------------------------------------------------------

    /** One retry: a Keystore that is still starting up (right after boot) can fail once. */
    private static byte[] keyWithRetry(Context app) throws Exception {
        try {
            return VaultKey.sqlcipherKey(app);
        } catch (GeneralSecurityException | ProviderException first) {
            Thread.sleep(400);
            return VaultKey.sqlcipherKey(app);
        }
    }

    /** Forgets the unreadable vault and its key, and creates a new key. */
    private static byte[] startFresh(Context app) throws Exception {
        discardVault(app);
        recordUnlock(app, true);
        return VaultKey.sqlcipherKey(app);
    }

    /** Deletes the vault files and the key. Without the key the old file could never be read anyway. */
    private static void discardVault(Context app) {
        deleteDbFiles(app, SqliteMemoryStore.DB_NAME);
        VaultKey.discard(app);
    }

    /** Counts launches in a row where the key couldn't be unwrapped. Returns the new count. */
    private static int recordUnlock(Context app, boolean ok) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int before = prefs.getInt(KEY_UNLOCK_FAILURES, 0);
        int after = ok ? 0 : before + 1;
        if (after != before) prefs.edit().putInt(KEY_UNLOCK_FAILURES, after).commit();
        return after;
    }

    private static synchronized void loadNative() {
        if (!nativeLoaded) {
            System.loadLibrary("sqlcipher");
            nativeLoaded = true;
        }
    }

    /**
     * Copies v0.1's plain database into a temporary encrypted file, renames it
     * into place, then deletes the plain file. A crash at any point leaves
     * either the old file (retried next start) or a complete vault.
     */
    private static String migrateLegacy(Context app, byte[] key) {
        File legacy = app.getDatabasePath(LEGACY_DB);
        File vault = app.getDatabasePath(SqliteMemoryStore.DB_NAME);
        if (!legacy.exists()) return "";
        if (vault.exists()) {
            // Earlier migration finished but the old file wasn't deleted yet.
            deleteDbFiles(app, LEGACY_DB);
            return "";
        }

        deleteDbFiles(app, MIGRATING_DB);
        int facts = 0;
        android.database.sqlite.SQLiteDatabase plain = null;
        SqliteMemoryStore target = null;
        try {
            plain = android.database.sqlite.SQLiteDatabase.openDatabase(legacy.getPath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            target = new SqliteMemoryStore(app, MIGRATING_DB, key, false);
            net.zetetic.database.sqlcipher.SQLiteDatabase enc = target.getWritableDatabase();
            enc.beginTransaction();
            try {
                facts = copy(plain, enc, "facts",
                        new String[]{"id", "fact_key", "label", "value", "source", "created_at", "superseded_at"});
                copy(plain, enc, "protected_items", new String[]{"item_key", "label", "value_encrypted", "created_at"});
                copy(plain, enc, "episodes", new String[]{"id", "role", "text", "ts"});
                enc.setTransactionSuccessful();
            } finally {
                enc.endTransaction();
            }
            target.close();
            target = null;
            plain.close();
            plain = null;

            File tmp = app.getDatabasePath(MIGRATING_DB);
            if (!tmp.renameTo(vault)) throw new IllegalStateException("rename failed");
            deleteDbFiles(app, LEGACY_DB);
            Log.d(TAG, "Migrated the unencrypted v0.1 memory into the vault (" + facts + " facts)");
            return facts > 0 ? "I moved what I remember into the new encrypted vault." : "";
        } catch (RuntimeException e) {
            Log.e(TAG, "Migration failed; the old file is kept and will be retried", e);
            if (target != null) target.close();
            if (plain != null) plain.close();
            deleteDbFiles(app, MIGRATING_DB);
            return "";
        }
    }

    private static int copy(android.database.sqlite.SQLiteDatabase from,
                            net.zetetic.database.sqlcipher.SQLiteDatabase to,
                            String table, String[] columns) {
        int rows = 0;
        Cursor c;
        try {
            c = from.query(table, columns, null, null, null, null, null);
        } catch (RuntimeException missingTable) {
            return 0;
        }
        try {
            while (c.moveToNext()) {
                ContentValues v = new ContentValues();
                for (int i = 0; i < columns.length; i++) {
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: v.put(columns[i], c.getLong(i)); break;
                        case Cursor.FIELD_TYPE_NULL: v.putNull(columns[i]); break;
                        default: v.put(columns[i], c.getString(i));
                    }
                }
                to.insertOrThrow(table, null, v);
                rows++;
            }
        } finally {
            c.close();
        }
        return rows;
    }

    private static void deleteDbFiles(Context app, String name) {
        File db = app.getDatabasePath(name);
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(db.getPath() + suffix).delete();
        }
    }
}
