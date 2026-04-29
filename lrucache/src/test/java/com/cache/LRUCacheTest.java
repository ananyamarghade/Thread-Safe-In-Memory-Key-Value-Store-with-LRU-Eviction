package com.cache;

import com.cache.core.LRUCache;
import com.cache.metrics.CacheMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness and concurrency tests for {@link LRUCache}.
 *
 * <p>The test suite is divided into three layers:
 * <ol>
 *   <li><strong>Unit tests</strong> – single-threaded correctness.</li>
 *   <li><strong>Stress tests</strong> – multi-threaded (100+ threads) to catch
 *       race conditions, deadlocks, and incorrect eviction under contention.</li>
 *   <li><strong>TTL tests</strong> – verifies time-based expiry.</li>
 * </ol>
 */
class LRUCacheTest {

    private LRUCache cache;

    @BeforeEach
    void setUp() {
        cache = new LRUCache(3);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit tests – correctness
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("get() returns NOT_FOUND for missing key")
    void getReturnsNotFoundForMissingKey() {
        assertEquals(LRUCache.NOT_FOUND, cache.get(42));
    }

    @Test
    @DisplayName("put() and get() basic round-trip")
    void putAndGetRoundTrip() {
        cache.put(1, 100);
        cache.put(2, 200);
        assertEquals(100, cache.get(1));
        assertEquals(200, cache.get(2));
    }

    @Test
    @DisplayName("put() overwrites existing key without increasing size")
    void putOverwritesExistingKey() {
        cache.put(1, 100);
        cache.put(1, 999);
        assertEquals(999, cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("LRU eviction: oldest entry evicted when capacity exceeded")
    void lruEvictionEvictsOldestEntry() {
        // capacity = 3
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        // Access 1 and 2 to make 3 the LRU
        cache.get(1);
        cache.get(2);
        // Insert 4 → 3 should be evicted
        cache.put(4, 4);

        assertEquals(LRUCache.NOT_FOUND, cache.get(3), "Key 3 should have been evicted");
        assertNotEquals(LRUCache.NOT_FOUND, cache.get(1));
        assertNotEquals(LRUCache.NOT_FOUND, cache.get(2));
        assertNotEquals(LRUCache.NOT_FOUND, cache.get(4));
    }

    @Test
    @DisplayName("put() updates LRU position of existing key")
    void putUpdatesLruPositionOfExistingKey() {
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        // Re-put key 1 → it becomes MRU, so next eviction should hit 2
        cache.put(1, 100);
        cache.put(4, 4); // Eviction happens here

        assertNotEquals(LRUCache.NOT_FOUND, cache.get(1), "Key 1 should NOT be evicted");
        assertEquals(LRUCache.NOT_FOUND, cache.get(2), "Key 2 should be evicted (was LRU)");
    }

    @Test
    @DisplayName("Cache size stays within capacity")
    void cacheSizeStaysWithinCapacity() {
        for (int i = 0; i < 100; i++) {
            cache.put(i, i * 10);
        }
        assertTrue(cache.size() <= 3, "Size must not exceed capacity");
        assertEquals(3, cache.size());
    }

    @Test
    @DisplayName("remove() deletes an entry")
    void removeDeletesEntry() {
        cache.put(1, 1);
        assertTrue(cache.remove(1));
        assertEquals(LRUCache.NOT_FOUND, cache.get(1));
        assertFalse(cache.remove(999)); // non-existent key
    }

    @Test
    @DisplayName("Metrics: hits and misses are tracked correctly")
    void metricsTrackedCorrectly() {
        cache.put(1, 1);
        cache.get(1);  // hit
        cache.get(99); // miss
        cache.get(1);  // hit

        CacheMetrics m = cache.metrics();
        assertEquals(2, m.getHits());
        assertEquals(1, m.getMisses());
        assertEquals(1, m.getPuts());
        assertEquals(0.6666, m.hitRate(), 0.001);
    }

    @Test
    @DisplayName("Metrics: eviction count increments on LRU eviction")
    void evictionCountIncrements() {
        // capacity = 3, insert 5 → 2 evictions
        for (int i = 1; i <= 5; i++) cache.put(i, i);
        assertEquals(2, cache.metrics().getEvictions());
    }

    @Test
    @DisplayName("TTL: expired entry returns NOT_FOUND")
    void ttlExpiredEntryReturnsNotFound() throws InterruptedException {
        cache.put(1, 100, 50L); // 50 ms TTL
        assertEquals(100, cache.get(1)); // still valid
        Thread.sleep(100);               // let it expire
        assertEquals(LRUCache.NOT_FOUND, cache.get(1));
        assertEquals(1, cache.metrics().getTtlExpiries());
    }

    @Test
    @DisplayName("TTL: non-expired entry stays accessible")
    void ttlNonExpiredEntryStaysAccessible() throws InterruptedException {
        cache.put(1, 200, 500L); // 500 ms TTL
        Thread.sleep(50);
        assertEquals(200, cache.get(1));
    }

    @Test
    @DisplayName("Capacity-1 edge case: single-item cache evicts on second insert")
    void singleItemCacheEdgeCase() {
        LRUCache single = new LRUCache(1);
        single.put(1, 1);
        single.put(2, 2);
        assertEquals(LRUCache.NOT_FOUND, single.get(1));
        assertEquals(2, single.get(2));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stress tests – concurrency
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 100 threads perform random put/get operations.
     * We verify:
     * <ul>
     *   <li>No exceptions are thrown (no race conditions corrupt state).</li>
     *   <li>Cache size never exceeds capacity.</li>
     *   <li>All futures complete successfully.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress: 100 threads, mixed read/write, no exceptions")
    void stressTest100Threads() throws InterruptedException, ExecutionException {
        final int THREADS         = 100;
        final int OPS_PER_THREAD  = 1_000;
        final int KEY_SPACE       = 20;    // small key space → high eviction rate
        LRUCache stressCache = new LRUCache(10);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        Random rng = new Random();

        for (int t = 0; t < THREADS; t++) {
            futures.add(pool.submit(() -> {
                try {
                    latch.await(); // synchronised start
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int op = 0; op < OPS_PER_THREAD; op++) {
                    int key = rng.nextInt(KEY_SPACE);
                    if (rng.nextBoolean()) {
                        stressCache.put(key, key * 10);
                    } else {
                        int val = stressCache.get(key);
                        // If found, value must be consistent with key
                        if (val != LRUCache.NOT_FOUND) {
                            assertEquals(key * 10, val,
                                "Corrupted value for key " + key);
                        }
                    }
                }
            }));
        }

        latch.countDown(); // release all threads simultaneously
        for (Future<?> f : futures) f.get(); // rethrows any assertion errors

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(stressCache.size() <= 10, "Size exceeded capacity under stress");

        System.out.println("[Stress Test] Completed. " + stressCache.metrics());
    }

    /**
     * Writer-heavy scenario: validates no data loss for keys that are still
     * in the cache (haven't been evicted).
     */
    @Test
    @DisplayName("Stress: writer-heavy – values are consistent when key present")
    void stressWriterHeavy() throws InterruptedException, ExecutionException {
        final int THREADS = 50;
        final int OPS     = 2_000;
        LRUCache wCache   = new LRUCache(50);

        ExecutorService pool  = Executors.newFixedThreadPool(THREADS);
        AtomicInteger errors  = new AtomicInteger();
        CountDownLatch latch  = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                Random r = new Random();
                for (int i = 0; i < OPS; i++) {
                    int key = r.nextInt(200);
                    int expectedValue = key * 7; // deterministic mapping
                    if (r.nextInt(10) < 7) {     // 70% writes
                        wCache.put(key, expectedValue);
                    } else {
                        int v = wCache.get(key);
                        // If value present it must equal expectedValue
                        if (v != LRUCache.NOT_FOUND && v != expectedValue) {
                            errors.incrementAndGet();
                        }
                    }
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(0, errors.get(), "Data corruption detected under concurrent writes");
        System.out.println("[Writer-Heavy Stress] Completed. " + wCache.metrics());
    }

    /**
     * Deadlock detection: if the test completes within the timeout, no deadlock.
     * Uses a tight loop to maximise lock contention.
     */
    @Test
    @DisplayName("Stress: deadlock detection under maximum contention")
    void deadlockDetectionTest() throws InterruptedException {
        final int THREADS = 200;
        LRUCache dCache   = new LRUCache(5);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch done  = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 500; i++) {
                        dCache.put(tid % 10, tid);
                        dCache.get(i % 10);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        boolean finished = done.await(15, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(finished, "DEADLOCK DETECTED: not all threads completed in time");
        System.out.println("[Deadlock Test] All threads completed cleanly.");
    }
}
