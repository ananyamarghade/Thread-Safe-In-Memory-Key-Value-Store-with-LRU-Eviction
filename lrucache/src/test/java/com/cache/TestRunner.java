package com.cache;

import com.cache.core.LRUCache;
import com.cache.metrics.CacheMetrics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-contained test runner (no JUnit dependency needed for quick validation).
 */
public class TestRunner {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        // Unit tests
        test("get() returns NOT_FOUND for missing key", () -> {
            LRUCache c = new LRUCache(3);
            assertEquals(-1, c.get(42), "Should be NOT_FOUND");
        });

        test("put() and get() round-trip", () -> {
            LRUCache c = new LRUCache(3);
            c.put(1, 100); c.put(2, 200);
            assertEquals(100, c.get(1), "Key 1");
            assertEquals(200, c.get(2), "Key 2");
        });

        test("put() overwrites existing key", () -> {
            LRUCache c = new LRUCache(3);
            c.put(1, 100); c.put(1, 999);
            assertEquals(999, c.get(1), "Updated value");
            assertEquals(1, c.size(), "Size should be 1");
        });

        test("LRU eviction: least-recently-used is evicted", () -> {
            LRUCache c = new LRUCache(3);
            c.put(1, 1); c.put(2, 2); c.put(3, 3);
            c.get(1); c.get(2); // promote 1 and 2; 3 becomes LRU
            c.put(4, 4);         // should evict 3
            assertEquals(-1, c.get(3), "Key 3 should be evicted");
            assertNotEquals(-1, c.get(1), "Key 1 should remain");
            assertNotEquals(-1, c.get(4), "Key 4 should be present");
        });

        test("put() updates LRU position of existing key", () -> {
            LRUCache c = new LRUCache(3);
            c.put(1, 1); c.put(2, 2); c.put(3, 3);
            c.put(1, 100); // make 1 MRU → 2 becomes LRU
            c.put(4, 4);   // evicts 2
            assertNotEquals(-1, c.get(1), "Key 1 should survive");
            assertEquals(-1, c.get(2), "Key 2 should be evicted");
        });

        test("Cache size stays within capacity", () -> {
            LRUCache c = new LRUCache(3);
            for (int i = 0; i < 100; i++) c.put(i, i * 10);
            assertTrue(c.size() <= 3, "Size must not exceed 3");
        });

        test("remove() deletes an entry", () -> {
            LRUCache c = new LRUCache(3);
            c.put(1, 1);
            assertTrue(c.remove(1), "remove should return true");
            assertEquals(-1, c.get(1), "Key should be gone");
            assertTrue(!c.remove(999), "remove non-existent returns false");
        });

        test("Metrics: hits and misses tracked correctly", () -> {
            LRUCache c = new LRUCache(3);
            c.put(1, 1);
            c.get(1);  // hit
            c.get(99); // miss
            c.get(1);  // hit
            CacheMetrics m = c.metrics();
            assertEquals(2, (int)m.getHits(), "Hits");
            assertEquals(1, (int)m.getMisses(), "Misses");
            assertEquals(1, (int)m.getPuts(), "Puts");
        });

        test("Metrics: eviction count increments", () -> {
            LRUCache c = new LRUCache(3);
            for (int i = 1; i <= 5; i++) c.put(i, i);
            assertEquals(2, (int)c.metrics().getEvictions(), "Evictions");
        });

        test("TTL: expired entry returns NOT_FOUND", () -> {
            LRUCache c = new LRUCache(3);
            c.put(1, 100, 50L); // 50ms TTL
            assertEquals(100, c.get(1), "TTL not expired yet");
            sleep(100);
            assertEquals(-1, c.get(1), "Should be expired");
            assertEquals(1, (int)c.metrics().getTtlExpiries(), "TTL expiry count");
        });

        test("TTL: non-expired entry stays accessible", () -> {
            LRUCache c = new LRUCache(3);
            c.put(1, 200, 500L); // 500ms TTL
            sleep(50);
            assertEquals(200, c.get(1), "Should still be valid");
        });

        test("Single-capacity edge case", () -> {
            LRUCache c = new LRUCache(1);
            c.put(1, 1); c.put(2, 2);
            assertEquals(-1, c.get(1), "Key 1 evicted");
            assertEquals(2, c.get(2), "Key 2 present");
        });

        // ── Stress test ──────────────────────────────────────────────────────
        test("Stress: 100 threads, mixed read/write, no data corruption", () -> {
            final int THREADS = 100, OPS = 1_000, KEY_SPACE = 20;
            LRUCache sc = new LRUCache(10);
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            List<Future<?>> futures = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            Random rng = new Random();

            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    try { latch.await(); } catch (InterruptedException e) {}
                    Random r = new Random();
                    for (int op = 0; op < OPS; op++) {
                        int key = r.nextInt(KEY_SPACE);
                        if (r.nextBoolean()) {
                            sc.put(key, key * 10);
                        } else {
                            int v = sc.get(key);
                            if (v != -1 && v != key * 10)
                                throw new RuntimeException("Corrupted! key=" + key + " got=" + v);
                        }
                    }
                }));
            }
            latch.countDown();
            for (Future<?> f : futures) f.get();
            pool.shutdown();
            pool.awaitTermination(15, TimeUnit.SECONDS);
            assertTrue(sc.size() <= 10, "Size exceeded capacity");
            System.out.println("    " + sc.metrics());
        });

        test("Stress: deadlock detection (200 threads, tight contention)", () -> {
            LRUCache dc = new LRUCache(5);
            ExecutorService pool = Executors.newFixedThreadPool(200);
            CountDownLatch done = new CountDownLatch(200);
            for (int t = 0; t < 200; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        Random r = new Random();
                        for (int i = 0; i < 500; i++) {
                            dc.put(r.nextInt(10), tid);
                            dc.get(r.nextInt(10));
                        }
                    } finally { done.countDown(); }
                });
            }
            boolean finished = done.await(15, TimeUnit.SECONDS);
            pool.shutdownNow();
            assertTrue(finished, "DEADLOCK DETECTED");
        });

        // ── Results ──────────────────────────────────────────────────────────
        System.out.println("\n══════════════════════════════════════");
        System.out.printf("  Tests: %d passed, %d failed%n", passed, failed);
        System.out.println("══════════════════════════════════════");
        if (failed > 0) System.exit(1);
    }

    // ── Micro-assertion helpers ───────────────────────────────────────────────

    static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual)
            throw new AssertionError(msg + ": expected " + expected + " but got " + actual);
    }
    static void assertNotEquals(int unexpected, int actual, String msg) {
        if (unexpected == actual)
            throw new AssertionError(msg + ": should not be " + unexpected);
    }
    static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @FunctionalInterface interface TestCase { void run() throws Exception; }

    static void test(String name, TestCase tc) {
        System.out.printf("  %-65s", name + "...");
        try {
            tc.run();
            System.out.println(" ✓ PASS");
            passed++;
        } catch (Throwable ex) {
            System.out.println(" ✗ FAIL: " + ex.getMessage());
            failed++;
        }
    }
}
