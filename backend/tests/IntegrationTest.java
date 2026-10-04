package tests;

import model.JourneyResult;
import service.BaggageValidator;
import service.Engine;
import util.Json;

import java.util.List;
import java.util.Map;

/**
 * Integration tests for the layer the dashboard actually talks to: the Engine
 * facade and the JSON payloads that become API responses.
 */
public final class IntegrationTest {

    public static void register() {
        TestHarness.test("engine: boots on the default profile from data files", new TestHarness.Body() {
            public void run() {
                Engine engine = new Engine("data");
                TestHarness.assertEquals("standard", engine.getProfileName(), "default profile");
                TestHarness.assertEquals(6, engine.validator().getGraph().nodeCount(), "checkpoints from data");
                TestHarness.assertEquals(5, engine.validator().getGraph().edgeCount(), "legal transitions from data");
                TestHarness.assertEquals("ARRIVAL", engine.validator().getFinalCheckpoint(), "final state");
                TestHarness.assertEquals(5, engine.validator().getReferenceProcess().size(), "reference process");
            }
        });

        TestHarness.test("engine: state payload carries everything the dashboard needs", new TestHarness.Body() {
            public void run() {
                Engine engine = new Engine("data");
                Map<String, Object> state = engine.statePayload();

                for (String key : new String[] { "graph", "queue", "stats", "bags", "demos", "referenceProcess" }) {
                    TestHarness.assertNotNull(state.get(key), "state key '" + key + "' is present");
                }
                Map<String, Object> graph = Json.asObject(state.get("graph"));
                TestHarness.assertEquals(6, Json.asArray(graph.get("nodes")).size(), "graph nodes");
                TestHarness.assertEquals(5, Json.asArray(graph.get("edges")).size(), "graph edges");
                TestHarness.assertEquals("ARRIVAL", Json.string(graph, "finalCheckpoint", ""), "final checkpoint");

                Map<String, Object> firstNode = Json.asObject(Json.asArray(graph.get("nodes")).get(0));
                TestHarness.assertEquals("CHECK_IN", Json.string(firstNode, "id", ""), "node order follows the data file");
                TestHarness.assertTrue(Json.integer(firstNode, "x", -1) > 0, "layout coordinates come from the data file");

                Map<String, Object> stats = Json.asObject(state.get("stats"));
                TestHarness.assertEquals(0, Json.integer(stats, "bags", -1), "no bags yet");
                TestHarness.assertEquals(0, Json.integer(stats, "scansProcessed", -1), "nothing processed yet");
                TestHarness.assertNotNull(stats.get("hashCapacity"), "hash table internals are reported");

                // the payload must be serialisable, since that is what the API returns
                String json = Json.write(state);
                TestHarness.assertTrue(json.startsWith("{"), "payload serialises to a JSON object");
                TestHarness.assertContains(json, "\"finalCheckpoint\":\"ARRIVAL\"", "values are emitted");
            }
        });

        TestHarness.test("engine: demo 1 runs end to end through the queue", new TestHarness.Body() {
            public void run() {
                Engine engine = new Engine("data");
                Map<String, Object> start = engine.startDemo("demo1");
                TestHarness.assertEquals(5, Json.integer(start, "queued", 0), "five events were queued");
                TestHarness.assertEquals(5, engine.validator().getQueue().size(), "queue holds the demo events");

                List<JourneyResult> results = engine.process(false);
                TestHarness.assertEquals(1, results.size(), "step mode processes exactly one event");
                TestHarness.assertEquals(4, engine.validator().getQueue().size(), "four events remain");

                results = engine.process(true);
                TestHarness.assertEquals(4, results.size(), "the rest is processed in one batch");
                TestHarness.assertEquals(BaggageValidator.STATUS_VALID, results.get(3).journeyStatus, "demo 1 is valid");

                Map<String, Object> stats = engine.statsPayload();
                TestHarness.assertEquals(1, Json.integer(stats, "validJourneys", 0), "one valid journey");
                TestHarness.assertEquals(5, Json.integer(stats, "scansProcessed", 0), "five scans processed");
                TestHarness.assertEquals(4, Json.integer(stats, "validTransitions", 0), "four real transitions");
                TestHarness.assertTrue(Json.asArray(engine.bagsPayload()).size() == 1, "one bag record");
            }
        });

        TestHarness.test("engine: switching to the dead-end profile rebuilds the graph", new TestHarness.Body() {
            public void run() {
                Engine engine = new Engine("data");
                engine.startDemo("demo5");   // demo 5 declares the dead-end profile
                TestHarness.assertEquals("deadend", engine.getProfileName(), "profile switched by the demo");
                TestHarness.assertEquals(4, engine.validator().getGraph().edgeCount(), "dead-end graph edges");
                TestHarness.assertFalse(engine.validator().getGraph().hasEdge("LOADING", "TRANSFER"),
                        "the standard edges are gone");

                List<JourneyResult> results = engine.process(true);
                TestHarness.assertEquals(BaggageValidator.STATUS_UNREACHABLE, results.get(2).journeyStatus,
                        "the demo ends in ARRIVAL UNREACHABLE");

                // switching back restores the standard rules
                engine.loadProfile("standard");
                TestHarness.assertEquals(5, engine.validator().getGraph().edgeCount(), "standard graph restored");
                TestHarness.assertEquals(0, engine.validator().getBagCount(), "state was reset with the rules");
            }
        });

        TestHarness.test("engine: sample stream produces a realistic mix of outcomes", new TestHarness.Body() {
            public void run() {
                Engine engine = new Engine("data");
                int queued = engine.startSampleStream();
                TestHarness.assertEquals(14, queued, "sample stream size matches data/sample_scans.json");

                List<JourneyResult> results = engine.process(true);
                TestHarness.assertEquals(14, results.size(), "every queued event was processed");

                Map<String, Object> stats = engine.statsPayload();
                TestHarness.assertEquals(3, Json.integer(stats, "bags", 0), "three bags in the sample stream");
                TestHarness.assertTrue(Json.integer(stats, "validJourneys", 0) >= 1, "at least one bag completes");
                TestHarness.assertTrue(Json.integer(stats, "duplicateScans", 0) >= 1, "the stream contains a duplicate");
                TestHarness.assertTrue(Json.integer(stats, "invalidTransitions", 0) >= 1, "the stream contains an invalid transition");
                TestHarness.assertEquals(14, Json.integer(stats, "scansProcessed", 0), "processed counter");
                TestHarness.assertEquals(0, Json.integer(stats, "queued", 0), "queue drained");
            }
        });

        TestHarness.test("engine: invalid input is rejected without corrupting state", new TestHarness.Body() {
            public void run() {
                Engine engine = new Engine("data");
                engine.startDemo("demo1");

                boolean unknownProfileRejected = false;
                try {
                    engine.loadProfile("does-not-exist");
                } catch (IllegalArgumentException expected) {
                    unknownProfileRejected = true;
                }
                TestHarness.assertTrue(unknownProfileRejected, "an unknown profile is rejected");
                TestHarness.assertEquals("standard", engine.getProfileName(), "profile unchanged after a bad request");
                TestHarness.assertEquals(5, engine.validator().getQueue().size(), "queued work untouched after a bad request");

                boolean unknownDemoRejected = false;
                try {
                    engine.startDemo("demo99");
                } catch (IllegalArgumentException expected) {
                    unknownDemoRejected = true;
                }
                TestHarness.assertTrue(unknownDemoRejected, "an unknown demo is rejected");
            }
        });

        TestHarness.test("engine: reset clears everything the dashboard shows", new TestHarness.Body() {
            public void run() {
                Engine engine = new Engine("data");
                engine.startDemo("demo1");
                engine.process(true);
                engine.reset();

                Map<String, Object> stats = engine.statsPayload();
                TestHarness.assertEquals(0, Json.integer(stats, "bags", -1), "bags cleared");
                TestHarness.assertEquals(0, Json.integer(stats, "scansProcessed", -1), "counters cleared");
                TestHarness.assertEquals(0, Json.integer(stats, "queued", -1), "queue cleared");
                TestHarness.assertEquals(0, engine.validator().getQueue().size(), "queue size zero");
                TestHarness.assertEquals("standard", engine.getProfileName(), "the profile itself is kept");
            }
        });
    }
}
