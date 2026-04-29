package com.cache.core;

/**
 * Intrusive doubly linked list that maintains LRU order.
 *
 * <p><strong>Design:</strong> sentinel head and tail nodes eliminate all
 * null-pointer edge cases in add/remove operations. The list looks like:
 * <pre>
 *   HEAD (dummy) ↔ [MRU node] ↔ ... ↔ [LRU node] ↔ TAIL (dummy)
 * </pre>
 *
 * <p><strong>Thread safety:</strong> This class is NOT thread-safe on its own.
 * All callers ({@link LRUCache}) must hold the appropriate lock before calling
 * any method. This separation of concerns keeps the list focused on pointer
 * manipulation and lets the cache own the locking policy.
 *
 * <p>All operations are O(1).
 */
class DoublyLinkedList {

    /** Dummy head — next pointer always points to the MRU node. */
    private final Node head;

    /** Dummy tail — prev pointer always points to the LRU node. */
    private final Node tail;

    private int size;

    DoublyLinkedList() {
        head = new Node(-1, -1);
        tail = new Node(-1, -1);
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    /**
     * Inserts {@code node} immediately after the head (MRU position).
     * Caller must hold the write lock.
     */
    void addToHead(Node node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
        size++;
    }

    /**
     * Unlinks {@code node} from the list without freeing memory.
     * The node can be re-inserted later (used for move-to-head).
     * Caller must hold the write lock.
     */
    void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        // Clear pointers to avoid memory leaks / stale references
        node.prev = null;
        node.next = null;
        size--;
    }

    /**
     * Moves an already-linked node to the head (O(1): remove + addToHead).
     * Caller must hold the write lock.
     */
    void moveToHead(Node node) {
        removeNode(node);
        addToHead(node);
    }

    /**
     * Removes and returns the tail node (LRU victim).
     * Returns {@code null} if the list is empty.
     * Caller must hold the write lock.
     */
    Node removeTail() {
        if (size == 0) return null;
        Node lru = tail.prev;
        removeNode(lru);
        return lru;
    }

    /** Returns the current number of real (non-sentinel) nodes. */
    int size() {
        return size;
    }

    /** Returns true if the list contains no real nodes. */
    boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns the LRU node without removing it (useful for debugging/metrics).
     * Returns {@code null} if empty.
     */
    Node peekTail() {
        if (size == 0) return null;
        return tail.prev;
    }

    /**
     * Returns the MRU node without removing it.
     * Returns {@code null} if empty.
     */
    Node peekHead() {
        if (size == 0) return null;
        return head.next;
    }
}
