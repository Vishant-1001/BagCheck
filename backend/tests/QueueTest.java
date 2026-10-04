package tests;

import dsa.CustomQueue;

/** Unit tests for the custom FIFO queue. */
public final class QueueTest {

    public static void register() {
        TestHarness.test("queue: enqueue and size", new TestHarness.Body() {
            public void run() {
                CustomQueue<String> queue = new CustomQueue<String>();
                TestHarness.assertTrue(queue.isEmpty(), "a new queue is empty");
                TestHarness.assertEquals(0, queue.size(), "new queue size");

                queue.enqueue("BAG001:CHECK_IN");
                queue.enqueue("BAG002:CHECK_IN");
                queue.enqueue("BAG001:SECURITY");
                TestHarness.assertEquals(3, queue.size(), "size after three enqueues");
                TestHarness.assertFalse(queue.isEmpty(), "queue is no longer empty");
            }
        });

        TestHarness.test("queue: dequeue removes the front element", new TestHarness.Body() {
            public void run() {
                CustomQueue<String> queue = new CustomQueue<String>();
                queue.enqueue("first");
                queue.enqueue("second");

                TestHarness.assertEquals("first", queue.dequeue(), "dequeue returns the front");
                TestHarness.assertEquals(1, queue.size(), "size after one dequeue");
                TestHarness.assertEquals("second", queue.dequeue(), "second dequeue returns the next element");
                TestHarness.assertTrue(queue.isEmpty(), "queue is empty after draining");
                TestHarness.assertNull(queue.dequeue(), "dequeue on an empty queue returns null");
            }
        });

        TestHarness.test("queue: FIFO ordering is preserved", new TestHarness.Body() {
            public void run() {
                // Exactly the interleaving problem the project faces: two bags, one shared queue.
                CustomQueue<String> queue = new CustomQueue<String>();
                String[] events = {
                    "BAG001:CHECK_IN", "BAG002:CHECK_IN", "BAG001:SECURITY",
                    "BAG003:CHECK_IN", "BAG002:SECURITY", "BAG001:LOADING"
                };
                for (String event : events) {
                    queue.enqueue(event);
                }
                for (String expected : events) {
                    TestHarness.assertEquals(expected, queue.dequeue(), "FIFO order must be preserved");
                }
                TestHarness.assertTrue(queue.isEmpty(), "all events were consumed in order");
            }
        });

        TestHarness.test("queue: peek does not remove the element", new TestHarness.Body() {
            public void run() {
                CustomQueue<String> queue = new CustomQueue<String>();
                TestHarness.assertNull(queue.peek(), "peek on an empty queue returns null");

                queue.enqueue("only");
                TestHarness.assertEquals("only", queue.peek(), "peek returns the front element");
                TestHarness.assertEquals("only", queue.peek(), "peek is repeatable");
                TestHarness.assertEquals(1, queue.size(), "peek must not change the size");
                TestHarness.assertEquals("only", queue.dequeue(), "the element is still available");
            }
        });

        TestHarness.test("queue: handles the empty queue and clear()", new TestHarness.Body() {
            public void run() {
                CustomQueue<Integer> queue = new CustomQueue<Integer>();
                TestHarness.assertNull(queue.peek(), "empty queue peek");
                TestHarness.assertNull(queue.dequeue(), "empty queue dequeue");
                TestHarness.assertEquals(0, queue.toList().size(), "empty queue has no contents");

                for (int i = 0; i < 1000; i++) {
                    queue.enqueue(i);
                }
                TestHarness.assertEquals(1000, queue.size(), "big queue size");
                TestHarness.assertEquals(0, queue.toList().get(0), "snapshot is front-first");
                TestHarness.assertEquals(999, queue.toList().get(999), "snapshot ends at the last element");

                queue.clear();
                TestHarness.assertTrue(queue.isEmpty(), "clear empties the queue");
                TestHarness.assertNull(queue.dequeue(), "dequeue after clear returns null");
            }
        });
    }
}
