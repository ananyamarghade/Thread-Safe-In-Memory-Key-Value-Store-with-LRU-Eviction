package com.cache.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free metrics collector for the LRU cache.
 *
 * <p>Uses {@link AtomicLong} counters so that metrics recording never contends
 * with the cache's own read/write lock. This is important: we don't want
 * metrics to become a bottleneck in high-throughput scenarios.
 *
 * <p>All methods are thread-safe.
 */
public class CacheMetrics {

    private final AtomicLong hits        = new AtomicLong();
    private final AtomicLong misses      = new AtomicLong();
    private final AtomicLong evictions   = new AtomicLong();
    private final AtomicLong puts        = new AtomicLong();
    private final AtomicLong ttlExpiries = new AtomicLong();

    public void recordHit()        { hits.incrementAndGet(); }
    public void recordMiss()       { misses.incrementAndGet(); }
    public void recordEviction()   { evictions.incrementAndGet(); }
    public void recordPut()        { puts.incrementAndGet(); }
    public void recordTtlExpiry()  { ttlExpiries.incrementAndGet(); }

    public long getHits()        { return hits.get(); }
    public long getMisses()      { return misses.get(); }
    public long getEvictions()   { return evictions.get(); }
    public long getPuts()        { return puts.get(); }
    public long getTtlExpiries() { return ttlExpiries.get(); }

    /** Hit rate as a value in [0.0, 1.0]. Returns 0 if no operations yet. */
    public double hitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    /** Total get() calls (hits + misses). */
    public long totalGets() {
        return hits.get() + misses.get();
    }

    /** Resets all counters. Useful between benchmark runs. */
    public void reset() {
        hits.set(0);
        misses.set(0);
        evictions.set(0);
        puts.set(0);
        ttlExpiries.set(0);
    }

    @Override
    public String toString() {
        return String.format(
            "CacheMetrics{hits=%d, misses=%d, hitRate=%.2f%%, puts=%d, evictions=%d, ttlExpiries=%d}",
            hits.get(), misses.get(), hitRate() * 100,
            puts.get(), evictions.get(), ttlExpiries.get()
        );
    }
}
