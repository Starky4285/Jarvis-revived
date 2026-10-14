package com.jarvis.core.memory;

/**
 * A secret (password, PIN, account number...). Lives in a separate lane:
 * never spoken, never put in a model prompt, never written to the episode log.
 * The Android store encrypts the value with an Android Keystore key.
 */
public final class ProtectedItem {

    public final String key;
    public final String label;
    public final String value;
    public final long createdAt;

    public ProtectedItem(String key, String label, String value, long createdAt) {
        this.key = key;
        this.label = label;
        this.value = value;
        this.createdAt = createdAt;
    }
}
