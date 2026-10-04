package dsa;

import java.util.ArrayList;
import java.util.List;

/**
 * DSA CONCEPT 3 of 3: a FIFO queue, written from scratch.
 *
 * Design choice: singly linked list with a head pointer and a tail pointer.
 * This is the simplest reliable queue implementation - every operation touches
 * only the two ends, so there is no array resizing or wraparound arithmetic to
 * get wrong, and no java.util.Queue is involved.
 *
 * Incoming scan events are enqueued here and validated in the exact order in
 * which they arrive (FIFO), which mirrors how a scanner gateway would deliver
 * events to the checker.
 *
 * Complexity: enqueue O(1), dequeue O(1), peek O(1), isEmpty O(1), size O(1).
 * Space: O(n).
 */
public class CustomQueue<T> {

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private Node<T> head;   // next to be dequeued
    private Node<T> tail;   // most recently enqueued
    private int size;

    /** Add to the back of the queue. O(1). */
    public void enqueue(T value) {
        Node<T> node = new Node<T>(value);
        if (tail == null) {
            head = node;
            tail = node;
        } else {
            tail.next = node;
            tail = node;
        }
        size++;
    }

    /** Remove and return the front element. Returns null when the queue is empty. O(1). */
    public T dequeue() {
        if (head == null) {
            return null;
        }
        T value = head.value;
        head = head.next;
        if (head == null) {
            tail = null;
        }
        size--;
        return value;
    }

    /** Look at the front element without removing it. O(1). */
    public T peek() {
        return head == null ? null : head.value;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void clear() {
        head = null;
        tail = null;
        size = 0;
    }

    /** Snapshot of the queue contents, front first. O(n); used only for display. */
    public List<T> toList() {
        List<T> out = new ArrayList<T>(size);
        for (Node<T> node = head; node != null; node = node.next) {
            out.add(node.value);
        }
        return out;
    }
}
