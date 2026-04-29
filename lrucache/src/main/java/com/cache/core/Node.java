package com.cache.core;

/**
 * Node in the doubly linked list that backs the LRU ordering.
 *
 * <p>Each node holds a key-value pair and bidirectional pointers so we can:
 * <ul>
 *   <li>Remove any node in O(1) (we have direct pointer, prev, and next)</li>
 *   <li>Promote to head (most-recently-used) in O(1)</li>
 * </ul>
 *
 * <p>The key is stored alongside the value so that when the tail node (LRU
 * candidate) is evicted, we can locate and remove it from the HashMap in O(1)
 * without an extra reverse lookup.
 *
 * <p>Fields are package-private intentionally: only {@link DoublyLinkedList}
 * and {@link LRUCache} operate on them; external callers never touch raw nodes.
 */
class Node {

    final int key;
    int value;

    /** Wall-clock expiry time in milliseconds; 0 means "no expiry". */
    long expiresAt;

    Node prev;
    Node next;

    Node(int key, int value) {
        this.key = key;
        this.value = value;
        this.expiresAt = 0;
    }

    Node(int key, int value, long expiresAt) {
        this.key = key;
        this.value = value;
        this.expiresAt = expiresAt;
    }

    /** Returns true if TTL is set and the entry has expired. */
    boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    @Override
    public String toString() {
        return "Node{key=" + key + ", value=" + value +
               (expiresAt > 0 ? ", ttlMs=" + (expiresAt - System.currentTimeMillis()) : "") + "}";
    }
}
