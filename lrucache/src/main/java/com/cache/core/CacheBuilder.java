package com.cache.core;

/**
 * Builder for {@link LRUCache} with a fluent API.
 *
 * <p>Example usage:
 * <pre>{@code
 * LRUCache cache = CacheBuilder.newBuilder()
 *     .capacity(1000)
 *     .build();
 * }</pre>
 */
public class CacheBuilder {

    private int capacity = 128; // sensible default

    private CacheBuilder() {}

    public static CacheBuilder newBuilder() {
        return new CacheBuilder();
    }

    public CacheBuilder capacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be >= 1");
        this.capacity = capacity;
        return this;
    }

    public LRUCache build() {
        return new LRUCache(capacity);
    }
}
