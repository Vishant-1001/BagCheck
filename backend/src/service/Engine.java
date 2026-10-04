package service;

import model.Baggage;
import model.JourneyResult;
import model.ScanEvent;
import util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application facade between the HTTP layer and the DSA engine.
 *
 * The API layer knows nothing about graphs, queues or hash tables - it calls
 * Engine and serialises the maps produced here. The DSA classes know nothing
 * about HTTP. That is the whole separation of concerns in this project.
 *
 * NOTE: switching the graph profile REPLACES the validator, so any journeys
 * validated under the previous profile are discarded. That is intentional:
 * they were validated against different rules and are not comparable.
 *
 * This class is synchronized because the HTTP server may serve the browser and
 * the benchmark at the same time; the DSA structures themselves stay simple.
 */
public class Engine {

    private final ConfigLoader config;
    private BaggageValidator validator;
    private String profileName;

    public Engine(String dataDir) {
        this.config = new ConfigLoader(dataDir);
        loadProfile(config.defaultProfile());
    }

    public ConfigLoader getConfig() {
        return config;
    }

    public synchronized BaggageValidator validator() {
        return validator;
    }

    public synchronized String getProfileName() {
        return profileName;
    }

    public synchronized void loadProfile(String name) {
        // buildGraph throws for an unknown profile, so a bad request cannot corrupt state
        this.validator = new BaggageValidator(config.buildGraph(name), config.referenceProcess(),
                config.finalCheckpoint());
        this.profileName = name;
    }

    public synchronized void reset() {
        validator.reset();
    }

    // ------------------------------------------------------------------
    // Mutations driven by the UI
    // ------------------------------------------------------------------

    public synchronized ScanEvent scan(String bagId, String checkpoint) {
        return validator.submitScan(bagId, checkpoint);
    }

    public synchronized List<JourneyResult> process(boolean all) {
        if (!all) {
            List<JourneyResult> one = new ArrayList<JourneyResult>();
            JourneyResult result = validator.processNext();
            if (result != null) {
                one.add(result);
            }
            return one;
        }
        return validator.processAll();
    }

    /** Load a built-in demo: switch to its graph profile, clear state and queue its scans. */
    public synchronized Map<String, Object> startDemo(String demoId) {
        Map<String, Object> demo = findDemo(demoId);
        if (demo == null) {
            throw new IllegalArgumentException("Unknown demo id: " + demoId);
        }
        loadProfile(Json.string(demo, "profile", config.defaultProfile()));
        String bagId = Json.string(demo, "bagId", "DEMO-BAG");
        for (String checkpoint : Json.stringList(demo, "scans")) {
            validator.submitScan(bagId, checkpoint);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("demo", demo);
        out.put("queued", Json.stringList(demo, "scans").size());
        return out;
    }

    /** Queue the deterministic sample scan stream on the standard profile. */
    public synchronized int startSampleStream() {
        loadProfile(config.defaultProfile());
        int count = 0;
        for (String[] scan : config.sampleScans()) {
            validator.submitScan(scan[0], scan[1]);
            count++;
        }
        return count;
    }

    private Map<String, Object> findDemo(String demoId) {
        for (Map<String, Object> demo : config.demos()) {
            if (Json.string(demo, "id", "").equals(demoId)) {
                return demo;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Serialisation
    // ------------------------------------------------------------------

    /** Everything the dashboard needs to render itself. */
    public synchronized Map<String, Object> statePayload() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("graph", graphPayload());
        out.put("queue", queuePayload());
        out.put("stats", statsPayload());
        out.put("bags", bagsPayload());
        out.put("demos", config.demos());
        out.put("referenceProcess", validator.getReferenceProcess());
        out.put("referenceProcessName", config.referenceProcessName());
        return out;
    }

    public synchronized Map<String, Object> graphPayload() {
        Map<String, Object> profile = config.profile(profileName);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("profile", profileName);
        out.put("profileName", Json.string(profile, "name", profileName));
        out.put("profileDescription", Json.string(profile, "description", ""));
        out.put("finalCheckpoint", validator.getFinalCheckpoint());

        // profiles offered in the UI dropdown
        List<Map<String, Object>> profiles = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : config.profiles().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", entry.getKey());
            item.put("name", Json.string(entry.getValue(), "name", entry.getKey()));
            item.put("edgeCount", Json.asArray(entry.getValue().get("edges")).size());
            profiles.add(item);
        }
        out.put("profiles", profiles);

        List<String> stuckNodes = validator.getGraph().nodesThatCannotReach(validator.getFinalCheckpoint());
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> definition : config.checkpointDefinitions()) {
            String id = Json.string(definition, "id", "");
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("id", id);
            node.put("label", Json.string(definition, "label", id));
            node.put("description", Json.string(definition, "description", ""));
            node.put("x", Json.integer(definition, "x", 0));
            node.put("y", Json.integer(definition, "y", 0));
            node.put("isFinal", id.equals(validator.getFinalCheckpoint()));
            node.put("outDegree", validator.getGraph().outDegree(id));
            node.put("reachesFinal", !stuckNodes.contains(id));
            nodes.add(node);
        }
        out.put("nodes", nodes);

        List<String> reference = validator.getReferenceProcess();
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        for (String[] edge : config.edges(profileName)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("from", edge[0]);
            item.put("to", edge[1]);
            item.put("onReferenceProcess",
                    reference.indexOf(edge[0]) >= 0 && reference.indexOf(edge[1]) == reference.indexOf(edge[0]) + 1);
            edges.add(item);
        }
        out.put("edges", edges);
        return out;
    }

    public synchronized Map<String, Object> queuePayload() {
        List<Map<String, Object>> pending = new ArrayList<Map<String, Object>>();
        for (ScanEvent event : validator.getQueue().toList()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("bagId", event.getBagId());
            item.put("checkpoint", event.getCheckpoint());
            item.put("sequence", event.getSequence());
            pending.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", validator.getQueue().size());
        out.put("events", pending);
        return out;
    }

    public synchronized Map<String, Object> statsPayload() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("queued", validator.getQueue().size());
        out.put("scansQueued", validator.getScansQueued());
        out.put("scansProcessed", validator.getScansProcessed());
        out.put("validTransitions", validator.getValidTransitions());
        out.put("duplicateScans", validator.getDuplicateScans());
        out.put("invalidTransitions", validator.getInvalidTransitions());
        out.put("unknownCheckpoints", validator.getUnknownCheckpoints());
        out.put("bags", validator.getBagCount());
        out.put("validJourneys", validator.countBagsWithStatus(BaggageValidator.STATUS_VALID));
        out.put("anomalyJourneys", validator.countBagsWithStatus(BaggageValidator.STATUS_ANOMALY));
        out.put("incompleteJourneys", validator.countBagsWithStatus(BaggageValidator.STATUS_INCOMPLETE));
        out.put("unreachableJourneys", validator.countBagsWithStatus(BaggageValidator.STATUS_UNREACHABLE));
        out.put("inProgressJourneys", validator.countBagsWithStatus(BaggageValidator.STATUS_IN_PROGRESS));
        out.put("graphNodes", validator.getGraph().nodeCount());
        out.put("graphEdges", validator.getGraph().edgeCount());
        out.put("lastBatchMillis", validator.getLastBatchMillis());
        out.put("lastBatchSize", validator.getLastBatchSize());
        // internals of the CUSTOM hash table, so the implementation is visible in the UI
        out.put("hashCapacity", validator.getBags().capacity());
        out.put("hashSize", validator.getBags().size());
        out.put("hashCollisions", validator.getBags().collisions());
        out.put("hashLongestChain", validator.getBags().longestChain());
        out.put("hashRehashes", validator.getBags().rehashes());
        out.put("hashLoadFactor", Math.round(validator.getBags().loadFactor() * 1000.0) / 1000.0);
        return out;
    }

    public synchronized List<Map<String, Object>> bagsPayload() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String bagId : validator.getBags().keys()) {
            Baggage bag = validator.getBags().get(bagId);
            if (bag != null) {
                out.add(bagPayload(bag));
            }
        }
        return out;
    }

    public synchronized Map<String, Object> bagPayload(Baggage bag) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("bagId", bag.getBagId());
        out.put("currentCheckpoint", bag.getCurrentCheckpoint());
        out.put("previousCheckpoint", bag.getPreviousCheckpoint());
        out.put("journeyStatus", bag.getJourneyStatus());
        out.put("scanCount", bag.getScanHistory().size());
        out.put("anomalyCount", bag.getAnomalies().size());
        out.put("arrived", bag.hasArrived());
        out.put("acceptedPath", bag.getAcceptedPath());
        out.put("advisories", bag.getAdvisories());

        List<Map<String, Object>> anomalies = new ArrayList<Map<String, Object>>();
        for (Baggage.Anomaly anomaly : bag.getAnomalies()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("type", anomaly.getType());
            item.put("checkpoint", anomaly.getCheckpoint());
            item.put("sequence", anomaly.getSequence());
            item.put("detail", anomaly.getDetail());
            anomalies.add(item);
        }
        out.put("anomalies", anomalies);

        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (JourneyResult result : bag.getResults()) {
            results.add(resultPayload(result));
        }
        out.put("results", results);
        return out;
    }

    public Map<String, Object> resultPayload(JourneyResult result) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("bagId", result.bagId);
        out.put("sequence", result.sequence);
        out.put("checkpoint", result.checkpoint);
        out.put("fromCheckpoint", result.fromCheckpoint);
        out.put("eventResult", result.eventResult);
        out.put("accepted", result.accepted);
        out.put("anomalyType", result.anomalyType);
        out.put("advisory", result.advisory);
        out.put("detail", result.detail);
        out.put("journeyStatus", result.journeyStatus);
        out.put("currentCheckpoint", result.currentCheckpoint);
        out.put("arrivalReachable", result.arrivalReachable);
        out.put("remainingPath", result.remainingPath);
        out.put("skippedCheckpoints", result.skippedCheckpoints);
        out.put("arrived", result.arrived);
        return out;
    }

    public List<Map<String, Object>> resultsPayload(List<JourneyResult> results) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (JourneyResult result : results) {
            out.add(resultPayload(result));
        }
        return out;
    }
}
