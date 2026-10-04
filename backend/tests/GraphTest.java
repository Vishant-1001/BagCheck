package tests;

import dsa.Graph;
import service.ConfigLoader;

import java.util.List;

/** Unit tests for the custom directed graph (adjacency-list + BFS/DFS). */
public final class GraphTest {

    public static void register() {
        TestHarness.test("graph: node insertion", new TestHarness.Body() {
            public void run() {
                Graph graph = new Graph();
                graph.addCheckpoint("CHECK_IN");
                graph.addCheckpoint("SECURITY");
                TestHarness.assertTrue(graph.hasCheckpoint("CHECK_IN"), "CHECK_IN should exist");
                TestHarness.assertTrue(graph.hasCheckpoint("SECURITY"), "SECURITY should exist");
                TestHarness.assertEquals(2, graph.nodeCount(), "node count");
                TestHarness.assertFalse(graph.hasCheckpoint("ARRIVAL"), "ARRIVAL was never added");

                graph.addCheckpoint("CHECK_IN");   // adding twice must not duplicate
                TestHarness.assertEquals(2, graph.nodeCount(), "duplicate node insertion must be ignored");
            }
        });

        TestHarness.test("graph: edge insertion", new TestHarness.Body() {
            public void run() {
                Graph graph = new Graph();
                graph.addEdge("CHECK_IN", "SECURITY");
                TestHarness.assertTrue(graph.hasEdge("CHECK_IN", "SECURITY"), "edge should exist");
                TestHarness.assertFalse(graph.hasEdge("SECURITY", "CHECK_IN"), "graph is directed");
                TestHarness.assertEquals(2, graph.nodeCount(), "addEdge must create missing endpoints");
                TestHarness.assertEquals(1, graph.edgeCount(), "edge count");

                graph.addEdge("CHECK_IN", "SECURITY");
                TestHarness.assertEquals(1, graph.edgeCount(), "duplicate edges must not be counted twice");
            }
        });

        TestHarness.test("graph: valid transition lookup", new TestHarness.Body() {
            public void run() {
                Graph graph = standardGraph();
                TestHarness.assertTrue(graph.hasEdge("CHECK_IN", "SECURITY"), "CHECK_IN -> SECURITY is legal");
                TestHarness.assertTrue(graph.hasEdge("SECURITY", "LOADING"), "SECURITY -> LOADING is legal");
                TestHarness.assertTrue(graph.hasEdge("LOADING", "TRANSFER"), "LOADING -> TRANSFER is legal");
                TestHarness.assertTrue(graph.hasEdge("TRANSFER", "ARRIVAL"), "TRANSFER -> ARRIVAL is legal");

                List<String> neighbours = graph.neighbors("LOADING");
                TestHarness.assertEquals(2, neighbours.size(), "LOADING has two legal successors");
                TestHarness.assertTrue(neighbours.contains("TRANSFER"), "LOADING -> TRANSFER listed");
                TestHarness.assertTrue(neighbours.contains("ARRIVAL"), "LOADING -> ARRIVAL listed");
            }
        });

        TestHarness.test("graph: invalid transition lookup", new TestHarness.Body() {
            public void run() {
                Graph graph = standardGraph();
                TestHarness.assertFalse(graph.hasEdge("SECURITY", "ARRIVAL"), "SECURITY -> ARRIVAL is not an edge");
                TestHarness.assertFalse(graph.hasEdge("ARRIVAL", "LOADING"), "ARRIVAL is terminal");
                TestHarness.assertFalse(graph.hasEdge("CHECK_IN", "CHECK_IN"), "no self-loop exists");
                TestHarness.assertFalse(graph.hasEdge("UNKNOWN", "SECURITY"), "unknown source has no edges");
                TestHarness.assertFalse(graph.hasEdge("CHECK_IN", "QUARANTINE"), "QUARANTINE is not reachable from CHECK_IN");
                TestHarness.assertEquals(0, graph.neighbors("UNKNOWN").size(), "unknown node has no neighbours");
            }
        });

        TestHarness.test("graph: BFS traversal and shortest path", new TestHarness.Body() {
            public void run() {
                Graph graph = standardGraph();

                List<String> order = graph.bfsOrder("CHECK_IN");
                TestHarness.assertEquals(5, order.size(), "BFS visits all reachable checkpoints");
                TestHarness.assertEquals("CHECK_IN", order.get(0), "BFS starts at the source");
                TestHarness.assertFalse(order.contains("QUARANTINE"), "QUARANTINE is not reachable in the standard graph");

                TestHarness.assertTrue(graph.reachable("CHECK_IN", "ARRIVAL"), "CHECK_IN can reach ARRIVAL");
                TestHarness.assertTrue(graph.reachable("SECURITY", "ARRIVAL"), "SECURITY can reach ARRIVAL");

                // BFS returns the path with the fewest hops: LOADING -> ARRIVAL is the shortcut
                List<String> path = graph.bfsPath("LOADING", "ARRIVAL");
                TestHarness.assertEquals(2, path.size(), "shortest path is LOADING -> ARRIVAL");
                TestHarness.assertEquals("LOADING", path.get(0), "path starts at LOADING");
                TestHarness.assertEquals("ARRIVAL", path.get(1), "path ends at ARRIVAL");

                // BFS returns the FEWEST hops, so it picks the LOADING -> ARRIVAL shortcut here
                List<String> longPath = graph.bfsPath("CHECK_IN", "ARRIVAL");
                TestHarness.assertEquals(4, longPath.size(), "CHECK_IN -> SECURITY -> LOADING -> ARRIVAL is shortest");
                TestHarness.assertEquals("CHECK_IN", longPath.get(0), "path starts at CHECK_IN");
                TestHarness.assertEquals("ARRIVAL", longPath.get(3), "path ends at ARRIVAL");
                TestHarness.assertFalse(longPath.contains("TRANSFER"), "TRANSFER is not needed on the shortest route");

                TestHarness.assertTrue(graph.bfsPath("ARRIVAL", "ARRIVAL").size() == 1, "a node reaches itself with a single-element path");
            }
        });

        TestHarness.test("graph: BFS detects an unreachable target", new TestHarness.Body() {
            public void run() {
                ConfigLoader config = new ConfigLoader("data");
                Graph graph = config.buildGraph("deadend");

                // Reachability is a per-checkpoint property, not a global one:
                // ARRIVAL is still reachable from CHECK_IN and SECURITY, but not from LOADING.
                TestHarness.assertFalse(graph.reachable("LOADING", "ARRIVAL"), "LOADING cannot reach ARRIVAL in the dead-end graph");
                TestHarness.assertEquals(0, graph.bfsPath("LOADING", "ARRIVAL").size(), "no path means an empty list");
                TestHarness.assertTrue(graph.reachable("CHECK_IN", "ARRIVAL"), "CHECK_IN can still reach ARRIVAL here");
                TestHarness.assertTrue(graph.reachable("SECURITY", "ARRIVAL"), "SECURITY can still reach ARRIVAL here");

                List<String> stuck = graph.nodesThatCannotReach("ARRIVAL");
                TestHarness.assertTrue(stuck.contains("LOADING"), "LOADING is a stuck node");
                TestHarness.assertTrue(stuck.contains("QUARANTINE"), "QUARANTINE is a dead end");
                TestHarness.assertTrue(stuck.contains("TRANSFER"), "TRANSFER has no outgoing edge in this profile");
                TestHarness.assertFalse(stuck.contains("ARRIVAL"), "ARRIVAL reaches itself");
                TestHarness.assertFalse(stuck.contains("CHECK_IN"), "CHECK_IN is not stuck in this profile");
            }
        });

        TestHarness.test("graph: DFS traversal agrees with BFS on reachability", new TestHarness.Body() {
            public void run() {
                Graph graph = standardGraph();

                List<String> order = graph.dfsOrder("CHECK_IN");
                TestHarness.assertEquals("CHECK_IN", order.get(0), "DFS starts at the source");
                TestHarness.assertEquals(5, order.size(), "DFS visits all reachable checkpoints");

                TestHarness.assertTrue(graph.dfsReachable("CHECK_IN", "ARRIVAL"), "DFS finds ARRIVAL");
                TestHarness.assertTrue(graph.dfsReachable("SECURITY", "ARRIVAL"), "DFS finds ARRIVAL from SECURITY");
                TestHarness.assertFalse(graph.dfsReachable("ARRIVAL", "CHECK_IN"), "DFS respects edge direction");

                ConfigLoader config = new ConfigLoader("data");
                Graph deadEnd = config.buildGraph("deadend");
                TestHarness.assertFalse(deadEnd.dfsReachable("LOADING", "ARRIVAL"), "DFS also reports the dead end");
                TestHarness.assertTrue(deadEnd.dfsReachable("CHECK_IN", "ARRIVAL"), "DFS still finds a route before LOADING");
            }
        });

        TestHarness.test("graph: configuration is data, not code", new TestHarness.Body() {
            public void run() {
                // The same checkpoint pair is illegal in one profile and legal in a hand-built one.
                // This is the central claim of the project: the graph defines the rules.
                TestHarness.assertFalse(standardGraph().hasEdge("SECURITY", "ARRIVAL"),
                        "SECURITY -> ARRIVAL is illegal in the standard profile");

                Graph custom = new Graph();
                custom.addEdge("CHECK_IN", "SECURITY");
                custom.addEdge("SECURITY", "ARRIVAL");
                TestHarness.assertTrue(custom.hasEdge("SECURITY", "ARRIVAL"),
                        "the same transition becomes legal once the graph says so");
            }
        });
    }

    /** Builds the standard profile graph from the data files. */
    static Graph standardGraph() {
        return new ConfigLoader("data").buildGraph("standard");
    }

}
