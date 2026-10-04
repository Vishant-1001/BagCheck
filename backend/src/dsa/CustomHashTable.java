package dsa;

import java.util.ArrayList;
import java.util.List;

/**
 * DSA CONCEPT 2 of 3: a hash table, written from scratch.
 *
 * Design choices (kept deliberately simple and inspectable):
 *   - Keys are Strings (bag IDs, checkpoint names).
 *   - Collision resolution: SEPARATE CHAINING (each bucket holds a short linked
 *     list of entries). Chaining is the simplest reliable technique: it never
 *     fails to insert, and it degrades gracefully as the load factor grows.
 *   - Hash function: polynomial rolling hash, h = h * 31 + char, then modulo the
 *     bucket count. This is the classic textbook string hash.
 *   - Rehashing: when size / capacity exceeds 0.75 the bucket array is doubled
 *     and the number of buckets is kept even so the modulo stays cheap.
 *
 * Used in two places inside this project:
 *   1. BaggageValidator stores baggage records:        BAG_ID  -> Baggage
 *   2. Graph uses it for the adjacency list and as the visited-set in BFS/DFS.
 *
 * Complexity:
 *   put / get / update / contains / remove: O(1) AVERAGE, O(n) WORST CASE.
 *   Worst case only happens when every key collides into one bucket; that is
 *   why the average case is qualified and never quoted as plain "O(1)".
 */
public class CustomHashTable<V> {

    /** Resize once the table is 75% full. */
    private static final double LOAD_FACTOR_LIMIT = 0.75;

    private static final class Entry<V> {
        final String key;
        V value;
        Entry<V> next;

        Entry(String key, V value, Entry<V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private Entry<V>[] buckets;
    private int size;
    private int collisions;
    private int rehashes;

    public CustomHashTable() {
        this(16);
    }

    @SuppressWarnings("unchecked")
    public CustomHashTable(int initialCapacity) {
        int capacity = Math.max(2, initialCapacity);
        this.buckets = new Entry[capacity];
    }

    /**
     * Polynomial rolling hash reduced to a valid bucket index.
     * Kept as a named method so it can be shown in the viva.
     */
    int indexFor(String key) {
        int h = 0;
        for (int i = 0; i < key.length(); i++) {
            h = 31 * h + key.charAt(i);
        }
        return (h & 0x7fffffff) % buckets.length;
    }

    /** Insert or replace. Returns the previous value, or null when the key was new. */
    public V put(String key, V value) {
        if (key == null) {
            throw new IllegalArgumentException("CustomHashTable does not accept null keys");
        }
        int index = indexFor(key);
        for (Entry<V> e = buckets[index]; e != null; e = e.next) {
            if (e.key.equals(key)) {
                V previous = e.value;
                e.value = value;
                return previous;
            }
        }
        if (buckets[index] != null) {
            collisions++;
        }
        buckets[index] = new Entry<V>(key, value, buckets[index]);
        size++;
        if (size > buckets.length * LOAD_FACTOR_LIMIT) {
            rehash();
        }
        return null;
    }

    /** Explicit alias of put(), kept because "update" is the operation the validator calls. */
    public void update(String key, V value) {
        put(key, value);
    }

    public V get(String key) {
        if (key == null) {
            return null;
        }
        for (Entry<V> e = buckets[indexFor(key)]; e != null; e = e.next) {
            if (e.key.equals(key)) {
                return e.value;
            }
        }
        return null;
    }

    public boolean contains(String key) {
        if (key == null) {
            return false;
        }
        for (Entry<V> e = buckets[indexFor(key)]; e != null; e = e.next) {
            if (e.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    public V remove(String key) {
        if (key == null) {
            return null;
        }
        int index = indexFor(key);
        Entry<V> previous = null;
        for (Entry<V> e = buckets[index]; e != null; previous = e, e = e.next) {
            if (e.key.equals(key)) {
                if (previous == null) {
                    buckets[index] = e.next;
                } else {
                    previous.next = e.next;
                }
                size--;
                return e.value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void rehash() {
        Entry<V>[] old = buckets;
        buckets = new Entry[old.length * 2];
        for (Entry<V> head : old) {
            for (Entry<V> e = head; e != null; ) {
                Entry<V> next = e.next;
                int index = indexFor(e.key);
                e.next = buckets[index];
                buckets[index] = e;
                e = next;
            }
        }
        rehashes++;
    }

    /** All keys currently stored (used by the API and the tests). */
    public List<String> keys() {
        List<String> out = new ArrayList<String>(size);
        for (Entry<V> head : buckets) {
            for (Entry<V> e = head; e != null; e = e.next) {
                out.add(e.key);
            }
        }
        return out;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return buckets.length;
    }

    public double loadFactor() {
        return (double) size / buckets.length;
    }

    /** Number of insertions that landed on an already-used bucket. Used by the collision test. */
    public int collisions() {
        return collisions;
    }

    public int rehashes() {
        return rehashes;
    }

    /** Longest bucket chain. O(capacity); only used for tests and reporting. */
    public int longestChain() {
        int longest = 0;
        for (Entry<V> head : buckets) {
            int length = 0;
            for (Entry<V> e = head; e != null; e = e.next) {
                length++;
            }
            if (length > longest) {
                longest = length;
            }
        }
        return longest;
    }
}
