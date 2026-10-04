package model;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of validating ONE scan event.
 *
 * Immutable data carrier: fields are final and read directly. The class is kept
 * small on purpose - it is a result object, not a service.
 *
 * Two levels of information are deliberately separated because they answer
 * different questions:
 *   - eventResult    : what happened to THIS scan (accepted / duplicate / invalid / unknown)
 *   - journeyStatus  : what is the state of the WHOLE journey now
 *                      (valid / anomaly / incomplete / arrival unreachable)
 *
 * anomalyType carries a real defect (too late / illegal / unknown / unreachable).
 * advisory carries the weaker, explicitly-inferred note "EXPECTED CHECKPOINT
 * SKIPPED", which comes from the reference process, never from the graph alone.
 */
public class JourneyResult {

    public static final String VALID_TRANSITION = "VALID TRANSITION";
    public static final String DUPLICATE_SCAN = "DUPLICATE SCAN";
    public static final String INVALID_TRANSITION = "INVALID TRANSITION";
    public static final String UNKNOWN_CHECKPOINT = "UNKNOWN CHECKPOINT";

    public final String bagId;
    public final String checkpoint;
    public final int sequence;
    /** null for the first accepted scan of a bag (there is no previous checkpoint yet) */
    public final String fromCheckpoint;
    public final String eventResult;
    /** true when the scan advanced the bag state */
    public final boolean accepted;
    /** most specific defect, or null when nothing is wrong */
    public final String anomalyType;
    /** advisory note such as "EXPECTED CHECKPOINT SKIPPED: TRANSFER", or null */
    public final String advisory;
    public final String detail;
    public final String journeyStatus;
    public final String currentCheckpoint;
    /** did graph traversal find ARRIVAL from the bag's current checkpoint? */
    public final boolean arrivalReachable;
    /** the remaining legal route to ARRIVAL found by BFS (empty when unreachable) */
    public final List<String> remainingPath;
    /** reference-process checkpoints that the observed (legal) journey bypassed */
    public final List<String> skippedCheckpoints;
    public final boolean arrived;

    public JourneyResult(String bagId, String checkpoint, int sequence, String fromCheckpoint,
                         String eventResult, boolean accepted, String anomalyType, String advisory,
                         String detail, String journeyStatus, String currentCheckpoint,
                         boolean arrivalReachable, List<String> remainingPath,
                         List<String> skippedCheckpoints, boolean arrived) {
        this.bagId = bagId;
        this.checkpoint = checkpoint;
        this.sequence = sequence;
        this.fromCheckpoint = fromCheckpoint;
        this.eventResult = eventResult;
        this.accepted = accepted;
        this.anomalyType = anomalyType;
        this.advisory = advisory;
        this.detail = detail;
        this.journeyStatus = journeyStatus;
        this.currentCheckpoint = currentCheckpoint;
        this.arrivalReachable = arrivalReachable;
        this.remainingPath = remainingPath == null ? new ArrayList<String>() : remainingPath;
        this.skippedCheckpoints = skippedCheckpoints == null ? new ArrayList<String>() : skippedCheckpoints;
        this.arrived = arrived;
    }
}
