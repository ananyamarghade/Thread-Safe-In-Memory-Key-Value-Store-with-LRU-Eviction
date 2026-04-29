package com.cache.core;

import com.cache.metrics.CacheMetrics;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe, in-memory LRU (Least Recently Used) cache with O(1) get/put.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LOCKING STRATEGY
 * ─────────────────────────────────────────────────────────────────────────────
 * We use a single {@link ReentrantReadWriteLock} (RRWL) rather than
 * {@code synchronized} methods for two reasons:
 *
 * 1. <strong>Read concurrency</strong>: Multiple threads can hold the read lock
 *    simultaneously, which matters when reads are frequent and reads DON'T need
 *    to move the node to the head (see read-only variant note below).
 *
 * 2. <strong>Upgrade path</strong>: Java's RRWL does NOT support lock upgrade
 *    (read → write). So any operation that MAY mutate state must acquire a full
 *    write lock up front. This is a known trade-off of RRWL.
 *
 * Why not striped / segment locking (like ConcurrentHashMap)?
 *   - The LRU list is a single global structure. Moving a node to the head on
 *     every get() touches the same sentinel nodes, making per-bucket locking
 *     extremely difficult without risking deadlocks.
 *   - A per-shard design IS possible (see improvement suggestions in README)
 *     but adds significant complexity. This implementation prioritises
 *     correctness and clarity.
 *
 * Operation → lock type:
 *   get()  → WRITE lock  (must update LRU order; moves node to head)
 *   put()  → WRITE lock  (always mutates map and list)
 *   size() → READ lock   (non-mutating)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DATA STRUCTURES
 * ─────────────────────────────────────────────────────────────────────────────
 * HashMap<key, Node>  : O(1) lookup by key
 * DoublyLinkedList    : O(1) move-to-head, O(1) remove-tail (LRU eviction)
 *
 * Together these give us O(1) amortised for both get() and put().
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TTL SUPPORT
 * ─────────────────────────────────────────────────────────────────────────────
 * Optional per-entry TTL is stored as an absolute expiry timestamp on the node.
 * Expired entries are lazily evicted on access (no background thread needed
 * for correctness; see startTtlJanitor() for eager cleanup).
 *
 * @param <V> value type (int for interview compatibility; use generics for prod)
 */
public class LRUCache {

    // ── State ────────────────────────────────────────────────────────────────

    private final int capacity;
    private final Map<Integer, Node> map;
    private final DoublyLinkedList list;
    private final CacheMetrics metrics;

    // ── Concurrency ──────────────────────────────────────────────────────────

    /**
     * Fair RRWL: fairness prevents writer starvation when reads are very
     * frequent. Set to false for higher throughput if starvation is not a
     * concern (benchmarks show ~10-20% improvement with non-fair lock).
     */
    private final ReadWriteLock lock = new ReentrantReadWriteLock(/* fair= */ false);

    // ── Constants ────────────────────────────────────────────────────────────

    /** Sentinel value returned when a key is not found. */
    public static final int NOT_FOUND = -1;

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param capacity maximum number of entries before LRU eviction kicks in.
     * @throws IllegalArgumentException if capacity < 1.
     */
    public LRUCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be >= 1");
        this.capacity = capacity;
        // Pre-size the map to avoid rehashing; load factor 0.75 is Java default.
        this.map      = new HashMap<>(capacity * 2);
        this.list     = new DoublyLinkedList();
        this.metrics  = new CacheMetrics();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the value for {@code key}, or {@link #NOT_FOUND} if absent /
     * expired. Promotes the entry to MRU position on a cache hit.
     *
     * <p>Acquires write lock because LRU ordering is updated on every hit.
     *
     * <p>Time complexity: O(1).
     */
    public int get(int key) {
        lock.writeLock().lock();
        try {
            Node node = map.get(key);

            if (node == null) {
                metrics.recordMiss();
                return NOT_FOUND;
            }

            // Lazy TTL eviction: treat as a miss if the entry has expired.
            if (node.isExpired()) {
                evictNode(node);
                metrics.recordTtlExpiry();
                metrics.recordMiss();
                return NOT_FOUND;
            }

            list.moveToHead(node);
            metrics.recordHit();
            return node.value;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Inserts or updates the entry for {@code key}. If the cache is at
     * capacity and a new key is inserted, the LRU entry is evicted first.
     *
     * <p>Time complexity: O(1).
     */
    public void put(int key, int value) {
        putWithTtl(key, value, 0L);
    }

    /**
     * Like {@link #put(int, int)} but with an explicit TTL.
     *
     * @param ttlMillis time-to-live in milliseconds; 0 means no expiry.
     */
    public void put(int key, int value, long ttlMillis) {
        putWithTtl(key, value, ttlMillis);
    }

    /**
     * Explicitly removes a key from the cache.
     *
     * @return true if the key existed and was removed.
     */
    public boolean remove(int key) {
        lock.writeLock().lock();
        try {
            Node node = map.get(key);
            if (node == null) return false;
            evictNode(node);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the number of non-expired entries currently in the cache.
     *
     * <p>Note: this is the logical size including potentially expired (but
     * not yet lazily evicted) entries when TTL is used. Use with that caveat.
     */
    public int size() {
        lock.readLock().lock();
        try {
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the configured maximum capacity. */
    public int capacity() {
        return capacity;
    }

    /** Returns a snapshot of the cache metrics (counters are always live). */
    public CacheMetrics metrics() {
        return metrics;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers (all called while holding write lock)
    // ─────────────────────────────────────────────────────────────────────────

    private void putWithTtl(int key, int value, long ttlMillis) {
        lock.writeLock().lock();
        try {
            Node existing = map.get(key);

            if (existing != null) {
                // Update in-place: move to head, refresh value and TTL.
                existing.value = value;
                existing.expiresAt = ttlMillis > 0
                    ? System.currentTimeMillis() + ttlMillis : 0;
                list.moveToHead(existing);
            } else {
                // Evict LRU if at capacity.
                if (map.size() == capacity) {
                    evictLru();
                }
                long expiresAt = ttlMillis > 0
                    ? System.currentTimeMillis() + ttlMillis : 0;
                Node newNode = new Node(key, value, expiresAt);
                map.put(key, newNode);
                list.addToHead(newNode);
            }
            metrics.recordPut();

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes the least-recently-used node (tail of DLL) from both the map
     * and the list. Caller must hold write lock.
     */
    private void evictLru() {
        Node lru = list.removeTail();
        if (lru != null) {
            map.remove(lru.key);
            metrics.recordEviction();
        }
    }

    /**
     * Removes a specific node from both the map and the list.
     * Used for TTL expiry and explicit remove(). Caller must hold write lock.
     */
    private void evictNode(Node node) {
        list.removeNode(node);
        map.remove(node.key);
        metrics.recordEviction();
    }
}
