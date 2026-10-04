package tests;

import model.JourneyResult;
import service.BaggageValidator;
import service.ConfigLoader;
import util.Json;

import java.util.List;
import java.util.Map;

/**
 * End-to-end tests for the five built-in demo scenarios.
 *
 * The demos are read from data/demos.json (the same file the dashboard uses), so
 * a demo cannot silently drift away from what the documentation promises.
 */
public final class DemoScenarioTest {

    public static void register() {
        TestHarness.test("demo: exactly the five required scenarios exist", new TestHarness.Body() {
            public void run() {
                List<Map<String, Object>> demos = new ConfigLoader("data").demos();
                TestHarness.assertEquals(5, demos.size(), "five built-in demo scenarios");
                TestHarness.assertEquals("demo1", Json.string(demos.get(0), "id", ""), "first demo id");
                TestHarness.assertEquals("demo5", Json.string(demos.get(4), "id", ""), "last demo id");
            }
        });

        TestHarness.test("demo 1: valid journey is reported as VALID JOURNEY", new TestHarness.Body() {
            public void run() {
                JourneyResult result = runDemo("demo1");
                TestHarness.assertEquals(BaggageValidator.STATUS_VALID, result.journeyStatus, "journey status");
                TestHarness.assertTrue(result.arrived, "the bag reached ARRIVAL");
                TestHarness.assertNull(result.anomalyType, "no anomaly");
                TestHarness.assertEquals("ARRIVAL", result.currentCheckpoint, "final checkpoint");
            }
        });

        TestHarness.test("demo 2: duplicate scan is reported as DUPLICATE SCAN", new TestHarness.Body() {
            public void run() {
                JourneyResult result = runDemo("demo2");
                TestHarness.assertEquals(JourneyResult.DUPLICATE_SCAN, result.eventResult, "event result");
                TestHarness.assertEquals(BaggageValidator.ANOMALY_DUPLICATE, result.anomalyType, "anomaly type");
                TestHarness.assertEquals(BaggageValidator.STATUS_ANOMALY, result.journeyStatus, "journey status");
                TestHarness.assertEquals("SECURITY", result.currentCheckpoint, "state did not move");
            }
        });

        TestHarness.test("demo 3: illegal edge is reported as INVALID TRANSITION", new TestHarness.Body() {
            public void run() {
                JourneyResult result = runDemo("demo3");
                TestHarness.assertEquals(JourneyResult.INVALID_TRANSITION, result.eventResult, "event result");
                TestHarness.assertEquals(BaggageValidator.ANOMALY_INVALID, result.anomalyType, "anomaly type");
                TestHarness.assertEquals(BaggageValidator.STATUS_ANOMALY, result.journeyStatus, "journey status");
                TestHarness.assertContains(result.detail, "SECURITY -> ARRIVAL", "rejected edge is named in the detail");
            }
        });

        TestHarness.test("demo 4: unfinished journey is reported as INCOMPLETE JOURNEY", new TestHarness.Body() {
            public void run() {
                JourneyResult result = runDemo("demo4");
                TestHarness.assertEquals(BaggageValidator.STATUS_INCOMPLETE, result.journeyStatus, "journey status");
                TestHarness.assertNull(result.anomalyType, "incomplete is not an anomaly");
                TestHarness.assertTrue(result.arrivalReachable, "ARRIVAL is still reachable");
            }
        });

        TestHarness.test("demo 5: only the LOADING scan makes ARRIVAL unreachable", new TestHarness.Body() {
            public void run() {
                // The whole point of demo 5: the scans are legal and the first two are only
                // unfinished, because ARRIVAL is still reachable from CHECK_IN and SECURITY.
                List<JourneyResult> results = runDemoAll("demo5");
                TestHarness.assertEquals(3, results.size(), "three scans in the demo");
                TestHarness.assertEquals(BaggageValidator.STATUS_INCOMPLETE, results.get(0).journeyStatus,
                        "event 1 is merely incomplete");
                TestHarness.assertEquals(BaggageValidator.STATUS_INCOMPLETE, results.get(1).journeyStatus,
                        "event 2 is merely incomplete");
                TestHarness.assertEquals(BaggageValidator.STATUS_UNREACHABLE, results.get(2).journeyStatus,
                        "event 3 discovers that ARRIVAL became unreachable");

                JourneyResult result = results.get(2);
                TestHarness.assertEquals(BaggageValidator.STATUS_UNREACHABLE, result.journeyStatus, "journey status");
                TestHarness.assertEquals(BaggageValidator.ANOMALY_UNREACHABLE, result.anomalyType, "anomaly type");
                TestHarness.assertFalse(result.arrivalReachable, "BFS proves unreachability");
                TestHarness.assertEquals("LOADING", result.currentCheckpoint, "the bag is stuck at LOADING");
            }
        });
    }

    /** Runs one demo exactly as the dashboard does: switch profile, then validate its scans. */
    private static JourneyResult runDemo(String demoId) {
        List<JourneyResult> results = runDemoAll(demoId);
        return results.get(results.size() - 1);
    }

    /** The full event-by-event outcome of a demo. */
    private static List<JourneyResult> runDemoAll(String demoId) {
        ConfigLoader config = new ConfigLoader("data");
        Map<String, Object> demo = null;
        for (Map<String, Object> candidate : config.demos()) {
            if (Json.string(candidate, "id", "").equals(demoId)) {
                demo = candidate;
            }
        }
        TestHarness.assertNotNull(demo, "demo " + demoId + " exists in data/demos.json");

        String profile = Json.string(demo, "profile", "standard");
        BaggageValidator validator = new BaggageValidator(config.buildGraph(profile),
                config.referenceProcess(), config.finalCheckpoint());

        String bagId = Json.string(demo, "bagId", "DEMO");
        List<JourneyResult> results = new java.util.ArrayList<JourneyResult>();
        int sequence = 0;
        for (String checkpoint : Json.stringList(demo, "scans")) {
            results.add(validator.validate(bagId, checkpoint, ++sequence));
        }
        TestHarness.assertTrue(results.size() > 0, "the demo produced at least one result");
        return results;
    }
}
