package service;

import dsa.CustomHashTable;
import dsa.CustomQueue;
import dsa.Graph;
import model.Baggage;
import model.JourneyResult;
import model.ScanEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The validation engine: where all three assessed data structures meet.
 *
 *   CustomQueue       incoming scan events wait here in FIFO order
 *   CustomHashTable   baggage records, keyed by bag ID (BAG1001 -> Baggage)
 *   Graph             the directed checkpoint graph that defines legal transitions
 *   BFS / DFS         answers "can this bag still reach ARRIVAL?"
 *
 * One scan event is validated in seven steps (the algorithm differs from a dumb
 * if/else chain in that step 4 asks the GRAPH, not the code):
 *   1. look the bag up in the hash table
 *   2. if it is the first scan, initialise the record
 *   3. otherwise read its current checkpoint
 *   4. ask the graph whether current -> new is a legal edge
 *   5. classify the event (valid / duplicate / invalid / unknown)
 *   6. update the baggage state (rejected scans never advance the state)
 *   7. run graph traversal to check whether ARRIVAL is still reachable
 *
 * Complexity: one scan event is O(1) average for the hash lookup + O(out-degree)
 * for the edge check + O(V + E) for the reachability BFS. With the demo graph
 * (V=6, E=5) the traversal is a handful of operations, so validating a whole
 * stream of n events is O(n * (V + E)) in the worst case.
 */
public class BaggageValidator {

    // ---- per-case results -------------------------------------------------
    public static final String STATUS_IN_PROGRESS = "IN PROGRESS";
    public static final String STATUS_VALID = "VALID JOURNEY";
    public static final String STATUS_ANOMALY = "ANOMALY DETECTED";
    public static final String STATUS_INCOMPLETE = "INCOMPLETE JOURNEY";
    public static final String STATUS_UNREACHABLE = "ARRIVAL UNREACHABLE";

    // ---- anomaly taxonomy (deliberately precise terminology) --------------
    public static final String ANOMALY_DUPLICATE = "DUPLICATE CHECKPOINT";
    public static final String ANOMALY_INVALID = "INVALID TRANSITION";
    public static final String ANOMALY_UNKNOWN = "UNKNOWN CHECKPOINT";
    public static final String ANOMALY_UNREACHABLE = "ARRIVAL UNREACHABLE";
    public static final String ADVISORY_SKIPPED = "EXPECTED CHECKPOINT SKIPPED";

    private final Graph graph;
    private final List<String> referenceProcess;
    private final String finalCheckpoint;

    private final CustomHashTable<Baggage> bags = new CustomHashTable<Baggage>();
    private final CustomQueue<ScanEvent> scanQueue = new CustomQueue<ScanEvent>();

    // ---- counters used by the statistics panel and the tests --------------
    private int sequenceCounter;
    private long scansQueued;
    private long scansProcessed;
    private long validTransitions;
    private long duplicateScans;
    private long invalidTransitions;
    private long unknownCheckpoints;
    private long lastBatchMillis;
    private int lastBatchSize;

    public BaggageValidator(Graph graph, List<String> referenceProcess, String finalCheckpoint) {
        this.graph = graph;
        this.referenceProcess = new ArrayList<String>(referenceProcess);
        this.finalCheckpoint = finalCheckpoint;
    }

    public Graph getGraph() {
        return graph;
    }

    public CustomHashTable<Baggage> getBags() {
        return bags;
    }

    public CustomQueue<ScanEvent> getQueue() {
        return scanQueue;
    }

    public List<String> getReferenceProcess() {
        return new ArrayList<String>(referenceProcess);
    }

    public String getFinalCheckpoint() {
        return finalCheckpoint;
    }

    // ------------------------------------------------------------------
    // Event intake (FIFO queue)
    // ------------------------------------------------------------------

    /** Enqueue a scan event. Sequence numbers are assigned in arrival order. */
    public ScanEvent submitScan(String bagId, String checkpoint) {
        ScanEvent event = new ScanEvent(bagId, checkpoint, ++sequenceCounter);
        scanQueue.enqueue(event);
        scansQueued++;
        return event;
    }

    /** Dequeue and validate exactly one event. Returns null when the queue is empty. */
    public JourneyResult processNext() {
        if (scanQueue.isEmpty()) {
            return null;
        }
        ScanEvent event = scanQueue.dequeue();
        return validate(event.getBagId(), event.getCheckpoint(), event.getSequence());
    }

    /** Drain the queue in FIFO order, measuring the real processing time. */
    public List<JourneyResult> processAll() {
        List<JourneyResult> results = new ArrayList<JourneyResult>();
        long start = System.nanoTime();
        JourneyResult result;
        while ((result = processNext()) != null) {
            results.add(result);
        }
        lastBatchMillis = (System.nanoTime() - start) / 1_000_000L;
        lastBatchSize = results.size();
        return results;
    }

    // ------------------------------------------------------------------
    // The validation algorithm
    // ------------------------------------------------------------------

    /**
     * Validate one scan observation for one bag. Exposed without the queue so the
     * automated tests can drive it directly.
     */
    public JourneyResult validate(String bagId, String checkpoint, int sequence) {
        scansProcessed++;
        Baggage bag = bags.get(bagId);

        // --- unknown checkpoint: it is not in the configured vocabulary at all
        if (!graph.hasCheckpoint(checkpoint)) {
            unknownCheckpoints++;
            if (bag == null) {
                bag = new Baggage(bagId);
                bags.put(bagId, bag);
            }
            bag.recordScan(new ScanEvent(bagId, checkpoint, sequence));
            String detail = "Checkpoint '" + checkpoint + "' is not part of the configured process vocabulary, "
                    + "so no transition rule applies to it. The scan is ignored.";
            bag.addAnomaly(new Baggage.Anomaly(ANOMALY_UNKNOWN, checkpoint, sequence, detail));
            bag.setJourneyStatus(STATUS_ANOMALY);
            return result(bagId, checkpoint, sequence, bag.getCurrentCheckpoint(),
                    JourneyResult.UNKNOWN_CHECKPOINT, false, ANOMALY_UNKNOWN, null, detail, bag);
        }

        // --- STEP 2: first scan initialises the journey
        if (bag == null || bag.getCurrentCheckpoint() == null) {
            if (bag == null) {
                bag = new Baggage(bagId);
                bags.put(bagId, bag);
            }
            bag.recordScan(new ScanEvent(bagId, checkpoint, sequence));
            bag.setCurrentCheckpoint(checkpoint);
            bag.extendAcceptedPath(checkpoint);
            String detail = "First scan for " + bagId + " initialises the journey at " + checkpoint + ".";
            String status = evaluateJourney(bag, sequence);
            bag.setJourneyStatus(status);
            return result(bagId, checkpoint, sequence, null,
                    JourneyResult.VALID_TRANSITION, true, null, null, detail, bag);
        }

        bag.recordScan(new ScanEvent(bagId, checkpoint, sequence));
        String current = bag.getCurrentCheckpoint();

        // --- duplicate scan: the bag is already at this checkpoint
        if (checkpoint.equals(current)) {
            duplicateScans++;
            String detail = bagId + " is already at " + current + ". The graph has no self-transition, "
                    + "so the repeated scan is a duplicate; the baggage state is left unchanged.";
            bag.addAnomaly(new Baggage.Anomaly(ANOMALY_DUPLICATE, checkpoint, sequence, detail));
            String status = evaluateJourney(bag, sequence);
            bag.setJourneyStatus(status);
            return result(bagId, checkpoint, sequence, current,
                    JourneyResult.DUPLICATE_SCAN, false, ANOMALY_DUPLICATE, null, detail, bag);
        }

        // --- STEP 4: ask the graph, not a hard-coded rule
        if (!graph.hasEdge(current, checkpoint)) {
            invalidTransitions++;
            String detail = "Edge " + current + " -> " + checkpoint + " does not exist in the "
                    + "configured process graph. The scan is rejected and " + bagId + " remains at " + current + ".";
            bag.addAnomaly(new Baggage.Anomaly(ANOMALY_INVALID, checkpoint, sequence, detail));
            String status = evaluateJourney(bag, sequence);
            bag.setJourneyStatus(status);
            return result(bagId, checkpoint, sequence, current,
                    JourneyResult.INVALID_TRANSITION, false, ANOMALY_INVALID, null, detail, bag);
        }

        // --- legal transition: advance the state
        validTransitions++;
        bag.setPreviousCheckpoint(current);
        bag.setCurrentCheckpoint(checkpoint);
        bag.extendAcceptedPath(checkpoint);
        if (finalCheckpoint.equals(checkpoint)) {
            bag.setArrived(true);
        }

        // Reference-process deviation is an ADVISORY, inferred from an explicit
        // ordered reference sequence - the graph alone cannot say what was skipped.
        List<String> skipped = referenceSkipped(current, checkpoint);
        String advisory = null;
        if (!skipped.isEmpty()) {
            advisory = ADVISORY_SKIPPED + ": " + join(skipped)
                    + " (legal edge " + current + " -> " + checkpoint
                    + " bypasses those checkpoints of the reference process)";
            bag.addAdvisory(advisory);
        }

        String status = evaluateJourney(bag, sequence);
        bag.setJourneyStatus(status);
        String detail = "Legal transition " + current + " -> " + checkpoint + "."
                + (skipped.isEmpty() ? "" : " Reference process expects " + join(skipped) + " in between.");
        return result(bagId, checkpoint, sequence, current,
                JourneyResult.VALID_TRANSITION, true, null, advisory, detail, bag);
    }

    /**
     * Journey-level evaluation, run after every processed event.
     *
     * Precedence (documented so the outcome is never a surprise):
     *   1. bag reached ARRIVAL       -> VALID JOURNEY (or ANOMALY DETECTED if defects were recorded earlier)
     *   2. ARRIVAL no longer reachable -> ARRIVAL UNREACHABLE (this is the strongest structural finding)
     *   3. a defect was recorded      -> ANOMALY DETECTED
     *   4. otherwise                  -> INCOMPLETE JOURNEY (nothing is wrong, the journey is just unfinished)
     */
    private String evaluateJourney(Baggage bag, int sequence) {
        String current = bag.getCurrentCheckpoint();
        if (current == null) {
            return STATUS_IN_PROGRESS;
        }
        if (finalCheckpoint.equals(current)) {
            return bag.getAnomalies().isEmpty() ? STATUS_VALID : STATUS_ANOMALY;
        }
        // STEP 7: BFS reachability. The graph traversal is the only component that
        // can answer this question - no single edge check can.
        boolean reachable = graph.reachable(current, finalCheckpoint);
        if (!reachable) {
            if (!bag.isArrivalUnreachableReported()) {
                bag.addAnomaly(new Baggage.Anomaly(ANOMALY_UNREACHABLE, current, sequence,
                        "From " + current + " no sequence of legal transitions leads to " + finalCheckpoint + "."));
                bag.setArrivalUnreachableReported(true);
            }
            return STATUS_UNREACHABLE;
        }
        if (!bag.getAnomalies().isEmpty()) {
            return STATUS_ANOMALY;
        }
        return STATUS_INCOMPLETE;
    }

    /**
     * Which reference-process checkpoints lie strictly between 'from' and 'to'?
     * Returns an empty list when the pair is adjacent in the reference process,
     * appears backwards, or is not part of the reference process at all.
     */
    List<String> referenceSkipped(String from, String to) {
        List<String> skipped = new ArrayList<String>();
        int i = referenceProcess.indexOf(from);
        int j = referenceProcess.indexOf(to);
        if (i < 0 || j < 0 || j <= i + 1) {
            return skipped;
        }
        for (int k = i + 1; k < j; k++) {
            skipped.add(referenceProcess.get(k));
        }
        return skipped;
    }

    /** Headline defect for the UI: a real event defect first, then structural unreachability. */
    private String headline(String eventAnomalyType, String status) {
        if (eventAnomalyType != null) {
            return eventAnomalyType;
        }
        if (STATUS_UNREACHABLE.equals(status)) {
            return ANOMALY_UNREACHABLE;
        }
        return null;
    }

    private JourneyResult result(String bagId, String checkpoint, int sequence, String fromCheckpoint,
                                String eventResult, boolean accepted, String anomalyType, String advisory,
                                String detail, Baggage bag) {
        String current = bag.getCurrentCheckpoint();
        boolean reachable = current != null && graph.reachable(current, finalCheckpoint);
        List<String> remaining = reachable ? graph.bfsPath(current, finalCheckpoint) : new ArrayList<String>();
        // A skipped checkpoint may only be reported for an ACCEPTED transition. Reporting it
        // for a rejected scan would wrongly suggest the transition happened.
        List<String> skipped = accepted ? referenceSkipped(fromCheckpoint, checkpoint) : new ArrayList<String>();
        JourneyResult outcome = new JourneyResult(bagId, checkpoint, sequence, fromCheckpoint, eventResult,
                accepted, headline(anomalyType, bag.getJourneyStatus()), advisory, detail,
                bag.getJourneyStatus(), current, reachable, remaining, skipped, bag.hasArrived());
        // Keep the outcome on the record so the UI can rebuild a journey after a reload.
        bag.recordResult(outcome);
        return outcome;
    }

    // ------------------------------------------------------------------
    // Statistics, reset and helpers
    // ------------------------------------------------------------------

    public long getScansQueued() {
        return scansQueued;
    }

    public long getScansProcessed() {
        return scansProcessed;
    }

    public long getValidTransitions() {
        return validTransitions;
    }

    public long getDuplicateScans() {
        return duplicateScans;
    }

    public long getInvalidTransitions() {
        return invalidTransitions;
    }

    public long getUnknownCheckpoints() {
        return unknownCheckpoints;
    }

    public long getLastBatchMillis() {
        return lastBatchMillis;
    }

    public int getLastBatchSize() {
        return lastBatchSize;
    }

    public int getBagCount() {
        return bags.size();
    }

    /** How many bags currently sit in each journey status. O(number of bags). */
    public int countBagsWithStatus(String status) {
        int count = 0;
        for (String key : bags.keys()) {
            Baggage bag = bags.get(key);
            if (bag != null && status.equals(bag.getJourneyStatus())) {
                count++;
            }
        }
        return count;
    }

    /** Clear queued events and journey state. Used by the demo/reset button. */
    public void reset() {
        scanQueue.clear();
        for (String key : new ArrayList<String>(bags.keys())) {
            bags.remove(key);
        }
        scansQueued = 0;
        scansProcessed = 0;
        validTransitions = 0;
        duplicateScans = 0;
        invalidTransitions = 0;
        unknownCheckpoints = 0;
        sequenceCounter = 0;
        lastBatchMillis = 0;
        lastBatchSize = 0;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}
