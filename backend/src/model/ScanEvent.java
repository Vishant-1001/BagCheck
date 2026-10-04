package model;

/**
 * One scan observation pushed by the (simulated) scanner gateway, e.g. BAG1001 -> SECURITY.
 * Immutable: events are never edited, only enqueued, dequeued and recorded.
 */
public class ScanEvent {

    private final String bagId;
    private final String checkpoint;
    private final int sequence;   // 1-based position in the enqueued stream

    public ScanEvent(String bagId, String checkpoint, int sequence) {
        this.bagId = bagId;
        this.checkpoint = checkpoint;
        this.sequence = sequence;
    }

    public String getBagId() {
        return bagId;
    }

    public String getCheckpoint() {
        return checkpoint;
    }

    public int getSequence() {
        return sequence;
    }

    @Override
    public String toString() {
        return "#" + sequence + " " + bagId + " -> " + checkpoint;
    }
}
