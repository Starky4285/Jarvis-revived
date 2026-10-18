package com.jarvis.revived.memory;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The vault's database key.
 *
 * 32 random bytes encrypt the whole SQLCipher database. They are stored only
 * in wrapped form (AES-256-GCM) under a key that lives inside the Android
 * Keystore (hardware-backed on modern phones) and can never be exported. A copy
 * of the app's files, without this phone's Keystore, cannot be decrypted.
 *
 * The wrapping key does not require the screen to be unlocked, so background
 * work (indexing photos or mail while the phone charges at night) can still
 * open the vault. A stricter "lock with the screen" mode is a planned option.
 */
final class VaultKey {

    private static final String TAG = "VaultKey";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "jarvis_vault_wrap_v1";
    private static final String FILE_NAME = "vault/db.key";
    private static final byte FORMAT_VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int TAG_BITS = 128;

    /** The stored key exists but can certainly never be unwrapped on this phone again. */
    static final class LockedException extends Exception {
        LockedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private VaultKey() {
    }

    static boolean exists(Context context) {
        return file(context).isFile();
    }

    /**
     * The SQLCipher key as a raw-key literal ({@code x'<64 hex>'}), creating
     * the key on first use. SQLCipher then skips its slow password stretching:
     * the key is already 256 random bits.
     */
    static synchronized byte[] sqlcipherKey(Context context) throws LockedException, GeneralSecurityException, IOException {
        byte[] raw = exists(context) ? unwrap(context) : create(context);
        try {
            StringBuilder sb = new StringBuilder(4 + raw.length * 2).append("x'");
            for (byte b : raw) sb.append(String.format("%02X", b & 0xFF));
            return sb.append('\'').toString().getBytes(StandardCharsets.US_ASCII);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /** Forgets the stored key (after the vault itself has been moved aside). */
    static synchronized void discard(Context context) {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception e) {
            Log.e(TAG, "Could not delete the wrapping key", e);
        }
    }

    // ------------------------------------------------------------------

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static byte[] create(Context context) throws GeneralSecurityException, IOException {
        byte[] raw = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(raw);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(true));
        byte[] iv = cipher.getIV();
        byte[] wrapped = cipher.doFinal(raw);

        byte[] blob = new byte[2 + iv.length + wrapped.length];
        blob[0] = FORMAT_VERSION;
        blob[1] = (byte) iv.length;
        System.arraycopy(iv, 0, blob, 2, iv.length);
        System.arraycopy(wrapped, 0, blob, 2 + iv.length, wrapped.length);

        File target = file(context);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create vault folder");
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(blob);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot store the vault key");
        }
        return raw;
    }

    /**
     * {@link LockedException} only when the key is certainly gone (damaged key
     * file, wrapping key missing or invalidated, authentication tag wrong).
     * Other Keystore errors may be temporary (a Keystore still starting after
     * boot) and are thrown as they are, so the caller can try again later
     * instead of giving up the vault.
     */
    private static byte[] unwrap(Context context) throws LockedException, GeneralSecurityException, IOException {
        byte[] blob = Files.readAllBytes(file(context).toPath());
        if (blob.length < 2 || blob[0] != FORMAT_VERSION) throw new LockedException("Unknown key format", null);
        int ivLen = blob[1] & 0xFF;
        if (ivLen == 0 || blob.length < 2 + ivLen + KEY_BYTES) throw new LockedException("Truncated key file", null);
        SecretKey key = wrappingKey(false);
        if (key == null) throw new LockedException("Wrapping key missing from the Keystore", null);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(blob, 2, 2 + ivLen)));
            byte[] raw = cipher.doFinal(blob, 2 + ivLen, blob.length - 2 - ivLen);
            if (raw.length != KEY_BYTES) {
                Arrays.fill(raw, (byte) 0);
                throw new LockedException("Wrong key length", null);
            }
            return raw;
        } catch (AEADBadTagException | KeyPermanentlyInvalidatedException e) {
            throw new LockedException("The vault key cannot be unwrapped on this phone", e);
        }
    }

    /** Existing wrapping key, or a new one when {@code create} (StrongBox if the phone has it). */
    private static SecretKey wrappingKey(boolean create) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        if (!create) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(true);
            } catch (ProviderException | GeneralSecurityException e) {
                // No StrongBox chip (StrongBoxUnavailableException is a ProviderException): use the TEE.
                Log.w(TAG, "StrongBox unavailable, using the TEE: " + e.getClass().getSimpleName());
            }
        }
        return generate(false);
    }

    private static SecretKey generate(boolean strongBox) throws GeneralSecurityException {
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spec.setIsStrongBoxBacked(true);
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(spec.build());
        return gen.generateKey();
    }
}
