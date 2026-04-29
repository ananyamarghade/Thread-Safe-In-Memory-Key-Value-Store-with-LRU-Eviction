package com.cache;

import com.cache.core.LRUCache;
import com.cache.metrics.CacheMetrics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Microbenchmark comparing single-threaded vs multi-threaded throughput of
 * {@link LRUCache}.
 *
 * <p>This is a hand-rolled benchmark rather than JMH to keep the project
 * dependency-free. For production benchmarking use JMH (see improvement notes).
 *
 * <p>Run: {@code java -jar target/lru-cache-1.0.0-jar-with-dependencies.jar}
 */
public class BenchmarkRunner {

    // ── Config ───────────────────────────────────────────────────────────────

    private static final int CACHE_CAPACITY = 1_000;
    private static final int KEY_SPACE      = 1_200;  // slightly above capacity → ~16% miss rate
    private static final int WARMUP_OPS     = 200_000;
    private static final int MEASURE_OPS    = 1_000_000;

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println("  LRU Cache Benchmark");
        System.out.println("  Capacity=" + CACHE_CAPACITY + "  KeySpace=" + KEY_SPACE);
        System.out.println("  Warmup=" + WARMUP_OPS + "  Measurement=" + MEASURE_OPS + " ops");
        System.out.println("════════════════════════════════════════════════════════");

        benchmarkSingleThread();
        System.out.println();
        for (int threads : new int[]{2, 4, 8, 16, 32, 64}) {
            benchmarkMultiThread(threads);
        }
        System.out.println("════════════════════════════════════════════════════════");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single-thread benchmark
    // ─────────────────────────────────────────────────────────────────────────

    private static void benchmarkSingleThread() {
        LRUCache cache = new LRUCache(CACHE_CAPACITY);
        Random rng = new Random(42);

        // Warmup
        for (int i = 0; i < WARMUP_OPS; i++) runOp(cache, rng);

        cache.metrics().reset();
        long start = System.nanoTime();

        for (int i = 0; i < MEASURE_OPS; i++) runOp(cache, rng);

        long elapsedNs = System.nanoTime() - start;
        printResult("Single-thread", 1, MEASURE_OPS, elapsedNs, cache.metrics());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multi-thread benchmark
    // ─────────────────────────────────────────────────────────────────────────

    private static void benchmarkMultiThread(int numThreads) throws Exception {
        LRUCache cache    = new LRUCache(CACHE_CAPACITY);
        int opsPerThread  = MEASURE_OPS / numThreads;
        Random rng        = new Random(42);

        // Warmup (single-threaded to seed the cache)
        for (int i = 0; i < WARMUP_OPS; i++) runOp(cache, rng);
        cache.metrics().reset();

        ExecutorService pool   = Executors.newFixedThreadPool(numThreads);
        CountDownLatch ready   = new CountDownLatch(numThreads);
        CountDownLatch start   = new CountDownLatch(1);
        AtomicLong totalOps    = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < numThreads; t++) {
            futures.add(pool.submit(() -> {
                Random local = new Random(); // thread-local RNG avoids contention
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < opsPerThread; i++) {
                    runOp(cache, local);
                }
                totalOps.addAndGet(opsPerThread);
            }));
        }

        ready.await();                // all threads are staged
        long t0 = System.nanoTime();
        start.countDown();            // release!

        for (Future<?> f : futures) f.get();
        long elapsedNs = System.nanoTime() - t0;

        pool.shutdown();
        printResult(numThreads + "-threads", numThreads, totalOps.get(), elapsedNs, cache.metrics());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** 70% reads, 30% writes – realistic OLTP-like workload. */
    private static void runOp(LRUCache cache, Random rng) {
        int key = rng.nextInt(KEY_SPACE);
        if (rng.nextInt(10) < 7) {
            cache.get(key);
        } else {
            cache.put(key, key * 3);
        }
    }

    private static void printResult(String label, int threads, long ops,
                                    long elapsedNs, CacheMetrics metrics) {
        double elapsedMs   = elapsedNs / 1_000_000.0;
        double opsPerSec   = ops / (elapsedNs / 1_000_000_000.0);
        double nsPerOp     = (double) elapsedNs / ops;

        System.out.printf(
            "  %-16s │ threads=%-3d │ ops=%,10d │ time=%8.1f ms │ " +
            "throughput=%,12.0f ops/s │ latency=%.1f ns/op │ hitRate=%.1f%%%n",
            label, threads, ops, elapsedMs, opsPerSec, nsPerOp,
            metrics.hitRate() * 100
        );
    }
}
