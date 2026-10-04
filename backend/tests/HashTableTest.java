package tests;

import dsa.CustomHashTable;

/** Unit tests for the custom hash table (separate chaining). */
public final class HashTableTest {

    public static void register() {
        TestHarness.test("hash table: insert and lookup", new TestHarness.Body() {
            public void run() {
                CustomHashTable<String> table = new CustomHashTable<String>();
                table.put("BAG001", "CHECK_IN");
                table.put("BAG002", "SECURITY");

                TestHarness.assertEquals("CHECK_IN", table.get("BAG001"), "lookup BAG001");
                TestHarness.assertEquals("SECURITY", table.get("BAG002"), "lookup BAG002");
                TestHarness.assertEquals(2, table.size(), "size after two inserts");
                TestHarness.assertTrue(table.contains("BAG001"), "contains BAG001");
                TestHarness.assertEquals(2, table.keys().size(), "keys() lists every stored key");
            }
        });

        TestHarness.test("hash table: update replaces an existing value", new TestHarness.Body() {
            public void run() {
                CustomHashTable<String> table = new CustomHashTable<String>();
                table.put("BAG001", "CHECK_IN");

                String previous = table.put("BAG001", "SECURITY");
                TestHarness.assertEquals("CHECK_IN", previous, "put returns the replaced value");
                TestHarness.assertEquals("SECURITY", table.get("BAG001"), "value was replaced");
                TestHarness.assertEquals(1, table.size(), "replacing must not increase the size");

                table.update("BAG001", "LOADING");
                TestHarness.assertEquals("LOADING", table.get("BAG001"), "update() changes the value");
                TestHarness.assertEquals(1, table.size(), "update() keeps the size stable");

                table.update("BAG999", "ARRIVAL");
                TestHarness.assertEquals("ARRIVAL", table.get("BAG999"), "update() inserts missing keys");
                TestHarness.assertEquals(2, table.size(), "update() inserted a new key");
            }
        });

        TestHarness.test("hash table: missing key behaves safely", new TestHarness.Body() {
            public void run() {
                CustomHashTable<String> table = new CustomHashTable<String>();
                TestHarness.assertNull(table.get("NOPE"), "get on a missing key returns null");
                TestHarness.assertFalse(table.contains("NOPE"), "contains on a missing key is false");
                TestHarness.assertNull(table.remove("NOPE"), "remove on a missing key returns null");
                TestHarness.assertEquals(0, table.size(), "missing operations do not change the size");
                TestHarness.assertNull(table.get(null), "null key lookup is safe");
            }
        });

        TestHarness.test("hash table: collision handling via separate chaining", new TestHarness.Body() {
            public void run() {
                // A deliberately tiny table (4 buckets) forces many collisions.
                CustomHashTable<Integer> table = new CustomHashTable<Integer>(4);
                for (int i = 0; i < 60; i++) {
                    table.put("BAG" + i, i);
                }
                TestHarness.assertEquals(60, table.size(), "every entry is stored");
                TestHarness.assertTrue(table.collisions() > 0, "collisions were actually exercised");
                TestHarness.assertTrue(table.capacity() >= 8, "the table grew instead of silently degrading");

                for (int i = 0; i < 60; i++) {
                    TestHarness.assertEquals(i, table.get("BAG" + i), "colliding key BAG" + i + " is still retrievable");
                }
                TestHarness.assertNull(table.get("BAG60"), "a key that was never inserted is still missing");
            }
        });

        TestHarness.test("hash table: rehashing preserves every entry", new TestHarness.Body() {
            public void run() {
                CustomHashTable<Integer> table = new CustomHashTable<Integer>(8);
                for (int i = 0; i < 500; i++) {
                    table.put("KEY-" + i, i);
                }
                TestHarness.assertEquals(500, table.size(), "size after 500 inserts");
                TestHarness.assertTrue(table.rehashes() > 0, "the table rehashed while growing");
                TestHarness.assertTrue(table.loadFactor() <= 0.75, "load factor stays under the resize threshold");
                TestHarness.assertTrue(table.longestChain() > 1, "chaining is used, so chains exist");

                boolean allPresent = true;
                for (int i = 0; i < 500; i++) {
                    Integer value = table.get("KEY-" + i);
                    if (value == null || value != i) {
                        allPresent = false;
                    }
                }
                TestHarness.assertTrue(allPresent, "all 500 entries survived the rehash");

                TestHarness.assertEquals(499, table.remove("KEY-499"), "remove returns the value");
                TestHarness.assertFalse(table.contains("KEY-499"), "removed key is gone");
                TestHarness.assertEquals(499, table.size(), "size after removal");
            }
        });

        TestHarness.test("hash table: same hash bucket, different keys", new TestHarness.Body() {
            public void run() {
                // "Aa" and "BB" are the classic Java String.hashCode() collision pair.
                CustomHashTable<String> table = new CustomHashTable<String>(4);
                table.put("Aa", "first");
                table.put("BB", "second");
                TestHarness.assertEquals("first", table.get("Aa"), "Aa keeps its own value");
                TestHarness.assertEquals("second", table.get("BB"), "BB keeps its own value");
                TestHarness.assertEquals(2, table.size(), "both colliding keys are stored separately");
            }
        });
    }
}
