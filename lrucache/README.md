# Thread-Safe In-Memory LRU Cache — Production Design

## System Design Overview

### Core Data Structures

```
HashMap<key, Node>   ←──────────────────────────────────────────────────────┐
                                                                              │
Doubly Linked List:                                                           │
                                                                              │
  HEAD(dummy) ↔ [node A, key=3] ↔ [node B, key=1] ↔ [node C, key=7] ↔ TAIL │
  (MRU end)                                                     (LRU end)    │
                      │                   │                  │               │
                      └──────────────────-┴──────────────────┘               │
                               keys stored in nodes ───────────────────────--┘
```

The HashMap gives us O(1) key lookup. The Doubly Linked List maintains
access order. The critical insight: each `Node` stores its own key, so when
we evict the tail (LRU node), we can do `map.remove(node.key)` — also O(1).

### Why NOT LinkedHashMap?
`LinkedHashMap` with `accessOrder=true` gives the same theoretical complexity
but is not thread-safe and wrapping it in `Collections.synchronizedMap` coarsens
all locking — defeating the purpose. Our custom DLL gives us full control over
the locking granularity and eviction semantics.

---

## Locking Strategy

### The Choice: ReentrantReadWriteLock (RRWL)

```
Operation         Lock Type   Reason
──────────────────────────────────────────────────────
get(key)          WRITE       Must update LRU order (move node to head)
put(key, value)   WRITE       Mutates map + DLL
remove(key)       WRITE       Mutates map + DLL
size()            READ        Non-mutating, safe to parallelise
```

### Why RRWL instead of `synchronized`?

1. `synchronized` methods hold the intrinsic lock for the entire method, with no
   distinction between readers and writers — every operation serialises.
2. RRWL allows concurrent readers (useful if you add a `peek()` method or size checks).
3. RRWL gives a clearer intent in code, making the design reviewable.

### Why a single lock instead of striped/segment locking?

The LRU doubly linked list is a **single global structure**. Every `get()` must
promote a node to the head — touching the same sentinel `head` node regardless of
which key was accessed. This makes segment-level locking extremely difficult:
you'd need to lock both the bucket segment AND the DLL, introducing the risk of
deadlocks.

A sharded LRU can work (see improvement #1 below), but requires segmenting the
entire cache including the DLL, effectively running N independent caches.

### Fair vs Non-Fair Lock

We use `new ReentrantReadWriteLock(false)` (non-fair) for maximum throughput.
Fair mode prevents starvation but adds ~10-20% overhead because the lock must
maintain a FIFO queue. For a cache used in a latency-sensitive path,
non-fair is the right default.

---

## TTL Design

TTL expiry is implemented **lazily**: the expiry timestamp is stored on the Node
and checked on every `get()`. This avoids background threads and is safe
under our write lock.

Trade-off: expired keys occupy space until accessed. For workloads where many
keys expire but are never re-read, add an eager janitor:

```java
ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();
janitor.scheduleAtFixedRate(this::evictExpiredEntries, 1, 1, TimeUnit.SECONDS);
```

---

## Performance Characteristics

| Metric             | Complexity |
|--------------------|------------|
| get()              | O(1)       |
| put()              | O(1) amortised |
| remove()           | O(1)       |
| Memory             | O(capacity) |

---

## Improvement Suggestions

### 1. Sharded LRU (Reduce Contention)
Split the cache into N shards, each with its own HashMap + DLL + lock.
Route requests via `key % N`. This is how Caffeine (the industry-standard
Java cache library) achieves near-linear scalability.

```java
int shard = key % NUM_SHARDS;
shards[shard].put(key, value);
```

### 2. Use Caffeine in Production
Caffeine uses a lock-free ring buffer to batch LRU updates — reads don't
acquire any lock, instead they append to a per-thread buffer that is drained
asynchronously by a maintenance thread. This achieves near-optimal read
throughput. Our implementation is a clean educational version; in production,
use Caffeine unless you need custom eviction semantics.

### 3. W-TinyLFU Eviction Policy
Pure LRU has known weaknesses with scan-resistance (a sequential scan of
cold data evicts hot data). W-TinyLFU (used by Caffeine) adds a frequency
sketch (Count-Min) to prefer evicting low-frequency keys, giving 10-40% better
hit rates in practice.

### 4. JMH Benchmarks
Replace the hand-rolled benchmark with JMH for statistically rigorous results:
JVM warmup, JIT effects, and GC interference are all controlled for.

### 5. Generics
Replace `int key / int value` with `<K, V>` generics. The current design mirrors
the LeetCode API but production caches should accept any serializable key/value.

### 6. Metrics Export
Add a Prometheus/Micrometer meter registry to export hit rate, eviction rate,
and cache size as time-series metrics.

---

## Comparison with Redis LRU

| Aspect             | This Implementation           | Redis LRU                              |
|--------------------|-------------------------------|----------------------------------------|
| Location           | In-process JVM heap           | Out-of-process, network I/O required  |
| Latency            | ~50–200 ns                    | ~100 µs–1 ms (network round trip)     |
| Capacity           | Limited by JVM heap           | Limited by server RAM (can be GBs)    |
| Eviction policy    | True LRU (exact)              | Approximate LRU (random sample of 5)  |
| Persistence        | None (memory only)            | RDB snapshots + AOF logging            |
| Distribution       | Single JVM                    | Redis Cluster (16384 hash slots)       |
| Thread model       | Multi-threaded with locks     | Single-threaded event loop             |
| Serialization      | No-op (native Java objects)   | Must serialise/deserialise (msgpack etc.) |

Redis uses **approximate LRU** not exact LRU: it samples a random set of keys
and evicts the one with the oldest access time. This avoids maintaining an
ordered data structure globally, which would be a bottleneck in Redis's
single-threaded model.

---

## Scaling to Distributed Systems

To scale this design across multiple nodes:

```
Client
   │
   ▼
Consistent Hash Router  ──→ determines which shard owns a key
   │
   ├──→ Node 1 (LRUCache, keys 0-3fff)
   ├──→ Node 2 (LRUCache, keys 4000-7fff)
   └──→ Node 3 (LRUCache, keys 8000-ffff)
```

1. **Consistent Hashing**: Route keys to shards via consistent hash ring
   (virtual nodes for even distribution). Minimises reshuffling on node add/remove.

2. **Replication**: Each shard can have read replicas. Writes go to primary,
   reads can be served from replicas (eventual consistency trade-off).

3. **Invalidation Protocol**: On eviction, optionally publish an invalidation
   message to peer nodes via a pub/sub bus (Kafka, Redis Pub/Sub) to keep
   distributed replicas coherent.

4. **Coordination**: Use etcd or ZooKeeper for shard membership, leader
   election, and failure detection.

5. **Network Layer**: gRPC (with Protocol Buffers) for low-overhead RPC between
   clients and cache nodes.

This is essentially how systems like Memcached and Hazelcast Near Cache work.
