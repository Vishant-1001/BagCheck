package service;

import dsa.Graph;
import util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the SIMULATED configuration from the data/ directory.
 *
 * Everything that decides "what is legal" lives in data files, not in Java code:
 *   data/checkpoints.json      checkpoint vocabulary + node positions for the UI
 *   data/transitions.json      directed graph profiles (adjacency lists)
 *   data/reference_process.json explicit ordered reference sequence
 *   data/demos.json            the five built-in demo scenarios
 *   data/sample_scans.json     deterministic sample scan stream
 *
 * java.util collections are used here only for configuration plumbing; the
 * assessed structures (graph, queue, hash table) are the project's own classes.
 */
public class ConfigLoader {

    private final String dataDir;
    private final Map<String, Object> checkpointFile;
    private final Map<String, Object> transitionsFile;
    private final Map<String, Object> referenceFile;
    private final Map<String, Object> demoFile;
    private final Map<String, Object> sampleFile;

    public ConfigLoader(String dataDir) {
        this.dataDir = dataDir;
        this.checkpointFile = Json.asObject(Json.parse(Json.readFile(dataDir + "/checkpoints.json")));
        this.transitionsFile = Json.asObject(Json.parse(Json.readFile(dataDir + "/transitions.json")));
        this.referenceFile = Json.asObject(Json.parse(Json.readFile(dataDir + "/reference_process.json")));
        this.demoFile = Json.asObject(Json.parse(Json.readFile(dataDir + "/demos.json")));
        this.sampleFile = Json.asObject(Json.parse(Json.readFile(dataDir + "/sample_scans.json")));
    }

    public String getDataDir() {
        return dataDir;
    }

    public String finalCheckpoint() {
        return Json.string(checkpointFile, "finalCheckpoint", "ARRIVAL");
    }

    /** Raw checkpoint definitions (id, label, description, x, y) for display. */
    public List<Map<String, Object>> checkpointDefinitions() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : Json.asArray(checkpointFile.get("checkpoints"))) {
            out.add(Json.asObject(item));
        }
        return out;
    }

    public String defaultProfile() {
        return Json.string(transitionsFile, "defaultProfile", "standard");
    }

    /** Profile names in file order. */
    public List<String> profileNames() {
        return new ArrayList<String>(profiles().keySet());
    }

    public Map<String, Map<String, Object>> profiles() {
        Map<String, Object> raw = Json.asObject(transitionsFile.get("profiles"));
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            out.put(entry.getKey(), Json.asObject(entry.getValue()));
        }
        return out;
    }

    public Map<String, Object> profile(String name) {
        Map<String, Object> profile = profiles().get(name);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown graph profile: " + name);
        }
        return profile;
    }

    /** Legal edges of a profile as [from, to] pairs, in file order. */
    public List<String[]> edges(String profileName) {
        List<String[]> out = new ArrayList<String[]>();
        for (Object item : Json.asArray(profile(profileName).get("edges"))) {
            Map<String, Object> edge = Json.asObject(item);
            out.add(new String[] { Json.string(edge, "from", ""), Json.string(edge, "to", "") });
        }
        return out;
    }

    public List<String> referenceProcess() {
        return Json.stringList(referenceFile, "checkpoints");
    }

    public String referenceProcessName() {
        return Json.string(referenceFile, "name", "Reference process");
    }

    public List<Map<String, Object>> demos() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : Json.asArray(demoFile.get("demos"))) {
            out.add(Json.asObject(item));
        }
        return out;
    }

    /** Deterministic sample scan stream as [bagId, checkpoint] pairs. */
    public List<String[]> sampleScans() {
        List<String[]> out = new ArrayList<String[]>();
        for (Object item : Json.asArray(sampleFile.get("scans"))) {
            Map<String, Object> scan = Json.asObject(item);
            out.add(new String[] { Json.string(scan, "bagId", ""), Json.string(scan, "checkpoint", "") });
        }
        return out;
    }

    /**
     * Builds the directed graph for one profile.
     *
     * ALL defined checkpoints become nodes for every profile (the vocabulary is
     * global), while the PROFILE decides which transitions are legal. So a scan to
     * a node that exists but has no legal edge into it is an INVALID TRANSITION,
     * not an UNKNOWN CHECKPOINT - an important distinction in the viva.
     */
    public Graph buildGraph(String profileName) {
        Graph graph = new Graph();
        for (Map<String, Object> definition : checkpointDefinitions()) {
            graph.addCheckpoint(Json.string(definition, "id", ""));
        }
        for (String[] edge : edges(profileName)) {
            graph.addEdge(edge[0], edge[1]);
        }
        return graph;
    }
}
