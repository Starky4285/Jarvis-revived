package com.jarvis.core.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class DeferredMemoryStoreTest {

    @Test
    public void opensOnFirstUseOnlyOnce() {
        AtomicInteger opens = new AtomicInteger();
        VolatileMemoryStore real = new VolatileMemoryStore();
        DeferredMemoryStore store = new DeferredMemoryStore(() -> {
            opens.incrementAndGet();
            return real;
        });
        assertFalse(store.isOpen());
        assertEquals(0, opens.get());

        long id = store.insertFact("name", "name", "Dava", "test", 1L);
        assertTrue(store.isOpen());
        assertEquals(id, store.activeFact("name").id);
        assertEquals("Dava", real.activeFact("name").value);
        assertSame(real, store.open());
        assertEquals(1, opens.get());
    }

    @Test
    public void failedOpenFallsBackToRamOnly() {
        DeferredMemoryStore store = new DeferredMemoryStore(() -> {
            throw new IllegalStateException("no keystore");
        });
        store.insertFact("city", "city", "Hyderabad", "test", 1L);
        assertTrue(store.open() instanceof VolatileMemoryStore);
        assertEquals("Hyderabad", store.activeFact("city").value);
    }

    @Test
    public void nullOpenFallsBackToRamOnly() {
        DeferredMemoryStore store = new DeferredMemoryStore(() -> null);
        assertNotNull(store.open());
        assertTrue(store.activeFacts().isEmpty());
    }

    @Test
    public void concurrentFirstUseOpensOnce() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch inOpener = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DeferredMemoryStore store = new DeferredMemoryStore(() -> {
            opens.incrementAndGet();
            inOpener.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new VolatileMemoryStore();
        });
        Thread first = new Thread(store::open);
        first.start();
        inOpener.await();
        Thread second = new Thread(() -> store.activeFacts());
        second.start();
        release.countDown();
        first.join(5000);
        second.join(5000);
        assertEquals(1, opens.get());
    }
}
