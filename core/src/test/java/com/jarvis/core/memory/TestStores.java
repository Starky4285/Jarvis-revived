package com.jarvis.core.memory;

/** Lets tests in other packages get a RAM-only store. */
public final class TestStores {
    private TestStores() {
    }

    public static MemoryStore inMemory() {
        return new VolatileMemoryStore();
    }
}
