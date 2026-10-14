package com.jarvis.core.memory;

/** Lets tests in other packages get the test-only in-memory store. */
public final class TestStores {
    private TestStores() {
    }

    public static MemoryStore inMemory() {
        return new InMemoryMemoryStore();
    }
}
