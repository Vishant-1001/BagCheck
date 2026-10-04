package model;

import java.util.ArrayList;
import java.util.List;

/**
 * The baggage record stored in the custom hash table under its bag ID.
 *
 * Fields required by the specification:
 *   bag id, current checkpoint, previous checkpoint, scan history,
 *   journey status and anomaly information.
 *
 * It also keeps the accepted checkpoint path (for the graph visualisation) and
 * whether a "cannot reach ARRIVAL" finding was already reported (to avoid
 * repeating the same finding on every later scan).
 */
public class Baggage {

    /** A defect or finding recorded against a bag. */
    public static class Anomaly {
        private final String type;
        private final String checkpoint;
        private final int sequence;
        private final String detail;

        public Anomaly(String type, String checkpoint, int sequence, String detail) {
            this.type = type;
            this.checkpoint = checkpoint;
            this.sequence = sequence;
            this.detail = detail;
        }

        public String getType() {
            return type;
        }

        public String getCheckpoint() {
            return checkpoint;
        }

        public int getSequence() {
            return sequence;
        }

        public String getDetail() {
            return detail;
        }
    }

    private final String bagId;
    private String currentCheckpoint;    // null before the first accepted scan
    private String previousCheckpoint;   // null for the first accepted scan
    private String journeyStatus = "IN PROGRESS";
    private final List<ScanEvent> scanHistory = new ArrayList<ScanEvent>();
    private final List<Anomaly> anomalies = new ArrayList<Anomaly>();
    private final List<String> acceptedPath = new ArrayList<String>();
    private final List<String> advisories = new ArrayList<String>();
    /** validation outcome of every scan this bag has received, in order */
    private final List<JourneyResult> results = new ArrayList<JourneyResult>();
    private boolean arrivalUnreachableReported;
    private boolean arrived;

    public Baggage(String bagId) {
        this.bagId = bagId;
    }

    public String getBagId() {
        return bagId;
    }

    public String getCurrentCheckpoint() {
        return currentCheckpoint;
    }

    public void setCurrentCheckpoint(String currentCheckpoint) {
        this.currentCheckpoint = currentCheckpoint;
    }

    public String getPreviousCheckpoint() {
        return previousCheckpoint;
    }

    public void setPreviousCheckpoint(String previousCheckpoint) {
        this.previousCheckpoint = previousCheckpoint;
    }

    public String getJourneyStatus() {
        return journeyStatus;
    }

    public void setJourneyStatus(String journeyStatus) {
        this.journeyStatus = journeyStatus;
    }

    public List<ScanEvent> getScanHistory() {
        return scanHistory;
    }

    public void recordScan(ScanEvent event) {
        scanHistory.add(event);
    }

    public List<Anomaly> getAnomalies() {
        return anomalies;
    }

    public void addAnomaly(Anomaly anomaly) {
        anomalies.add(anomaly);
    }

    /** Accepted checkpoints in order - this is the path drawn on the graph. */
    public List<String> getAcceptedPath() {
        return acceptedPath;
    }

    public void extendAcceptedPath(String checkpoint) {
        acceptedPath.add(checkpoint);
    }

    /** Legal observations that deviate from the reference process (advisory, not an anomaly). */
    public List<String> getAdvisories() {
        return advisories;
    }

    public void addAdvisory(String advisory) {
        advisories.add(advisory);
    }

    public List<JourneyResult> getResults() {
        return results;
    }

    public void recordResult(JourneyResult result) {
        results.add(result);
    }

    public boolean isArrivalUnreachableReported() {
        return arrivalUnreachableReported;
    }

    public void setArrivalUnreachableReported(boolean value) {
        this.arrivalUnreachableReported = value;
    }

    public boolean hasArrived() {
        return arrived;
    }

    public void setArrived(boolean arrived) {
        this.arrived = arrived;
    }
}
