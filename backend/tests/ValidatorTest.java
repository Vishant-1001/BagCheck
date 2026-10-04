package tests;

import dsa.Graph;
import model.Baggage;
import service.ConfigLoader;
import model.JourneyResult;
import service.BaggageValidator;

import java.util.Arrays;
import java.util.List;

/** Unit tests for the baggage validation engine. */
public final class ValidatorTest {

    private static final ConfigLoader CONFIG = new ConfigLoader("data");

    /** A validator on the standard profile. */
    static BaggageValidator standardValidator() {
        return new BaggageValidator(CONFIG.buildGraph("standard"), CONFIG.referenceProcess(), CONFIG.finalCheckpoint());
    }

    /** A validator on the dead-end profile used by DEMO 5. */
    static BaggageValidator deadEndValidator() {
        return new BaggageValidator(CONFIG.buildGraph("deadend"), CONFIG.referenceProcess(), CONFIG.finalCheckpoint());
    }

    /** Feed a checkpoint sequence for one bag and return the result of the last scan. */
    static JourneyResult feed(BaggageValidator validator, String bagId, String... checkpoints) {
        JourneyResult last = null;
        int sequence = 0;
        for (String checkpoint : checkpoints) {
            last = validator.validate(bagId, checkpoint, ++sequence);
        }
        return last;
    }

    public static void register() {
        TestHarness.test("validator: valid journey is accepted", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                JourneyResult result = feed(validator, "BAG001",
                        "CHECK_IN", "SECURITY", "LOADING", "TRANSFER", "ARRIVAL");

                TestHarness.assertEquals(JourneyResult.VALID_TRANSITION, result.eventResult, "last event is a valid transition");
                TestHarness.assertEquals(BaggageValidator.STATUS_VALID, result.journeyStatus, "journey is valid");
                TestHarness.assertTrue(result.accepted, "the scan advanced the state");
                TestHarness.assertTrue(result.arrived, "the bag reached ARRIVAL");
                TestHarness.assertNull(result.anomalyType, "no anomaly was recorded");
                TestHarness.assertEquals(4, validator.getValidTransitions(),
                        "five scans make four transitions (the first scan only initialises the journey)");
                TestHarness.assertEquals(0, validator.getInvalidTransitions(), "no invalid transitions");
                TestHarness.assertEquals(0, validator.getDuplicateScans(), "no duplicates");
            }
        });

        TestHarness.test("validator: first scan initialises the journey", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                TestHarness.assertFalse(validator.getBags().contains("BAG777"), "unknown bag is not in the hash table yet");

                JourneyResult result = feed(validator, "BAG777", "CHECK_IN");
                TestHarness.assertTrue(validator.getBags().contains("BAG777"), "the bag record was created");
                TestHarness.assertEquals(JourneyResult.VALID_TRANSITION, result.eventResult, "first scan is accepted");
                TestHarness.assertTrue(result.accepted, "first scan is accepted");
                TestHarness.assertNull(result.fromCheckpoint, "there is no previous checkpoint yet");
                TestHarness.assertEquals("CHECK_IN", result.currentCheckpoint, "current checkpoint is set");
                TestHarness.assertEquals(BaggageValidator.STATUS_INCOMPLETE, result.journeyStatus,
                        "a journey that is merely unfinished is INCOMPLETE, not an anomaly");
                TestHarness.assertTrue(result.arrivalReachable, "ARRIVAL is still reachable from CHECK_IN");
            }
        });

        TestHarness.test("validator: duplicate scan in a row", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                JourneyResult result = feed(validator, "BAG001", "CHECK_IN", "SECURITY", "SECURITY");

                TestHarness.assertEquals(JourneyResult.DUPLICATE_SCAN, result.eventResult, "third event is a duplicate");
                TestHarness.assertEquals(BaggageValidator.ANOMALY_DUPLICATE, result.anomalyType, "anomaly type");
                TestHarness.assertFalse(result.accepted, "a duplicate must not advance the state");
                TestHarness.assertEquals("SECURITY", result.currentCheckpoint, "the bag is still at SECURITY");
                TestHarness.assertEquals(BaggageValidator.STATUS_ANOMALY, result.journeyStatus, "journey is flagged");
                TestHarness.assertEquals(1, validator.getDuplicateScans(), "duplicate counter");
                TestHarness.assertEquals(1, validator.getValidTransitions(),
                        "only CHECK_IN -> SECURITY was a real transition");
                TestHarness.assertEquals(3, validator.getBags().get("BAG001").getScanHistory().size(),
                        "every scan is still recorded in the history");
            }
        });

        TestHarness.test("validator: invalid transition is rejected", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                JourneyResult result = feed(validator, "BAG001", "CHECK_IN", "SECURITY", "ARRIVAL");

                TestHarness.assertEquals(JourneyResult.INVALID_TRANSITION, result.eventResult, "third event is invalid");
                TestHarness.assertEquals(BaggageValidator.ANOMALY_INVALID, result.anomalyType, "anomaly type");
                TestHarness.assertFalse(result.accepted, "an illegal scan must not advance the state");
                TestHarness.assertEquals("SECURITY", result.currentCheckpoint, "the bag remains at SECURITY");
                TestHarness.assertEquals("SECURITY", result.fromCheckpoint, "the rejected edge is reported");
                TestHarness.assertEquals("ARRIVAL", result.checkpoint, "the rejected target is reported");
                TestHarness.assertEquals(BaggageValidator.STATUS_ANOMALY, result.journeyStatus, "journey is flagged");
                TestHarness.assertTrue(result.arrivalReachable, "ARRIVAL is still legally reachable from SECURITY");
                TestHarness.assertEquals(1, validator.getInvalidTransitions(), "invalid counter");
                TestHarness.assertContains(result.detail, "SECURITY -> ARRIVAL", "the detail explains the rejection");
            }
        });

        TestHarness.test("validator: incomplete journey is not an anomaly", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                JourneyResult result = feed(validator, "BAG001", "CHECK_IN", "SECURITY", "LOADING");

                TestHarness.assertEquals(JourneyResult.VALID_TRANSITION, result.eventResult, "the last transition is legal");
                TestHarness.assertEquals(BaggageValidator.STATUS_INCOMPLETE, result.journeyStatus, "journey is incomplete");
                TestHarness.assertNull(result.anomalyType, "being unfinished is not an anomaly");
                TestHarness.assertTrue(result.arrivalReachable, "ARRIVAL is still reachable");
                TestHarness.assertEquals(0, validator.getInvalidTransitions(), "no invalid transitions");
                TestHarness.assertEquals(0, validator.getInvalidTransitions() + validator.getDuplicateScans(),
                        "no anomalies at all");

                // BFS reports the remaining route: the graph shortcut LOADING -> ARRIVAL is shortest
                TestHarness.assertEquals(2, result.remainingPath.size(), "remaining path length");
                TestHarness.assertEquals("LOADING", result.remainingPath.get(0), "remaining path starts here");
                TestHarness.assertEquals("ARRIVAL", result.remainingPath.get(1), "remaining path ends here");

                TestHarness.assertEquals(BaggageValidator.STATUS_VALID,
                        feed(validator, "BAG001", "TRANSFER", "ARRIVAL").journeyStatus,
                        "finishing the journey makes the same bag valid");
            }
        });

        TestHarness.test("validator: ARRIVAL UNREACHABLE is detected with graph traversal", new TestHarness.Body() {
            public void run() {
                // The default profile accepts this sequence; the dead-end profile breaks reachability.
                TestHarness.assertEquals(BaggageValidator.STATUS_INCOMPLETE,
                        feed(standardValidator(), "BAG001", "CHECK_IN", "SECURITY", "LOADING").journeyStatus,
                        "on the standard graph these scans are merely incomplete");

                BaggageValidator validator = deadEndValidator();
                JourneyResult result = feed(validator, "BAG001", "CHECK_IN", "SECURITY", "LOADING");

                TestHarness.assertEquals(BaggageValidator.STATUS_UNREACHABLE, result.journeyStatus, "journey is stuck");
                TestHarness.assertEquals(BaggageValidator.ANOMALY_UNREACHABLE, result.anomalyType, "headline defect");
                TestHarness.assertFalse(result.arrivalReachable, "BFS proves ARRIVAL is unreachable");
                TestHarness.assertEquals(0, result.remainingPath.size(), "no remaining path exists");
                TestHarness.assertEquals("LOADING", result.currentCheckpoint, "the bag is still at LOADING");
                TestHarness.assertEquals(2, validator.getValidTransitions(),
                        "three scans make two transitions, and both were legal");
                TestHarness.assertEquals(1, validator.getBags().get("BAG001").getAnomalies().size(),
                        "the unreachable finding is recorded once, not on every later scan");
            }
        });

        TestHarness.test("validator: unknown checkpoint in the scan stream", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                JourneyResult result = feed(validator, "BAG001", "CHECK_IN", "MOON_BASE");

                TestHarness.assertEquals(JourneyResult.UNKNOWN_CHECKPOINT, result.eventResult, "unknown checkpoint");
                TestHarness.assertEquals(BaggageValidator.ANOMALY_UNKNOWN, result.anomalyType, "anomaly type");
                TestHarness.assertFalse(result.accepted, "an unknown checkpoint cannot advance the state");
                TestHarness.assertEquals("CHECK_IN", result.currentCheckpoint, "the bag keeps its last valid checkpoint");
                TestHarness.assertEquals(1, validator.getUnknownCheckpoints(), "unknown counter");

                // the bag can recover: an unknown scan must not destroy the journey
                JourneyResult recovery = feed(validator, "BAG001", "SECURITY");
                TestHarness.assertEquals(JourneyResult.VALID_TRANSITION, recovery.eventResult, "recovery scan is valid");
                TestHarness.assertEquals("SECURITY", recovery.currentCheckpoint, "state advanced correctly");
            }
        });

        TestHarness.test("validator: unknown bag is created on first sight, never twice", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                TestHarness.assertNull(validator.getBags().get("BAG404"), "lookup of an unseen bag is null");

                feed(validator, "BAG404", "CHECK_IN");
                Baggage bag = validator.getBags().get("BAG404");
                TestHarness.assertNotNull(bag, "the record now exists in the custom hash table");
                TestHarness.assertEquals("BAG404", bag.getBagId(), "key and record agree");

                feed(validator, "BAG404", "SECURITY");
                TestHarness.assertEquals(1, validator.getBags().size(), "the same bag is not stored twice");
                TestHarness.assertEquals(2, bag.getScanHistory().size(), "both scans are in the history");
                TestHarness.assertEquals(2, bag.getAcceptedPath().size(), "two accepted checkpoints so far");
            }
        });

        TestHarness.test("validator: reference process reports an explicitly skipped checkpoint", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                // LOADING -> ARRIVAL is a legal graph edge but bypasses TRANSFER in the reference process.
                JourneyResult result = feed(validator, "BAG001", "CHECK_IN", "SECURITY", "LOADING", "ARRIVAL");

                TestHarness.assertEquals(JourneyResult.VALID_TRANSITION, result.eventResult, "the shortcut is legal");
                TestHarness.assertEquals(BaggageValidator.STATUS_VALID, result.journeyStatus, "the journey still finishes");
                TestHarness.assertNull(result.anomalyType, "a legal shortcut is a deviation, not an anomaly");
                TestHarness.assertEquals(1, result.skippedCheckpoints.size(), "one checkpoint was bypassed");
                TestHarness.assertEquals("TRANSFER", result.skippedCheckpoints.get(0), "TRANSFER was bypassed");
                TestHarness.assertNotNull(result.advisory, "an advisory is attached");
                TestHarness.assertContains(result.advisory, "EXPECTED CHECKPOINT SKIPPED", "advisory wording");
                TestHarness.assertContains(result.advisory, "TRANSFER", "advisory names the checkpoint");
            }
        });

        TestHarness.test("validator: rejected scans never advance the state", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                feed(validator, "BAG001", "CHECK_IN", "SECURITY");
                Baggage bag = validator.getBags().get("BAG001");

                feed(validator, "BAG001", "ARRIVAL");     // invalid
                feed(validator, "BAG001", "SECURITY");    // duplicate
                feed(validator, "BAG001", "MARS");        // unknown

                TestHarness.assertEquals("SECURITY", bag.getCurrentCheckpoint(), "state is unchanged by bad scans");
                TestHarness.assertEquals(2, bag.getAcceptedPath().size(), "only accepted scans extend the path");
                TestHarness.assertEquals(3, bag.getAnomalies().size(), "all three defects are recorded");
                TestHarness.assertEquals(1, validator.getBags().size(), "still one bag");

                // and the bag can still finish afterwards: the journey completes, but the
                // defects stay on the record, so the status is ANOMALY DETECTED (not VALID)
                JourneyResult recovery = feed(validator, "BAG001", "LOADING", "TRANSFER", "ARRIVAL");
                TestHarness.assertEquals(JourneyResult.VALID_TRANSITION, recovery.eventResult, "the scans are legal again");
                TestHarness.assertTrue(recovery.arrived, "the bag reaches ARRIVAL");
                TestHarness.assertEquals(BaggageValidator.STATUS_ANOMALY, recovery.journeyStatus,
                        "earlier defects keep the journey flagged as an anomaly");
            }
        });

        TestHarness.test("validator: queue processes events in FIFO order across bags", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                validator.submitScan("BAG001", "CHECK_IN");
                validator.submitScan("BAG002", "CHECK_IN");
                validator.submitScan("BAG001", "SECURITY");
                validator.submitScan("BAG003", "CHECK_IN");
                validator.submitScan("BAG001", "LOADING");
                validator.submitScan("BAG002", "SECURITY");

                TestHarness.assertEquals(6, validator.getQueue().size(), "six events wait in the queue");

                List<JourneyResult> results = validator.processAll();
                TestHarness.assertEquals(6, results.size(), "six events were processed");
                TestHarness.assertTrue(validator.getQueue().isEmpty(), "the queue is drained");

                // FIFO: the order of results matches the order of submission
                TestHarness.assertEquals("BAG001", results.get(0).bagId, "first event belongs to BAG001");
                TestHarness.assertEquals("BAG002", results.get(1).bagId, "second event belongs to BAG002");
                TestHarness.assertEquals("SECURITY", results.get(2).checkpoint, "third event is BAG001 at SECURITY");
                TestHarness.assertEquals("BAG003", results.get(3).bagId, "fourth event belongs to BAG003");

                // each bag's state was updated independently
                TestHarness.assertEquals("LOADING", validator.getBags().get("BAG001").getCurrentCheckpoint(), "BAG001");
                TestHarness.assertEquals("SECURITY", validator.getBags().get("BAG002").getCurrentCheckpoint(), "BAG002");
                TestHarness.assertEquals("CHECK_IN", validator.getBags().get("BAG003").getCurrentCheckpoint(), "BAG003");
                TestHarness.assertEquals(3, validator.getBags().size(), "three bags in the hash table");
                TestHarness.assertNull(validator.processNext(), "processing an empty queue returns null");
            }
        });

        TestHarness.test("validator: the graph defines the rules, not the validator", new TestHarness.Body() {
            public void run() {
                // Identical scan sequence, different graph: SECURITY -> ARRIVAL becomes legal.
                Graph custom = new Graph();
                for (String id : Arrays.asList("CHECK_IN", "SECURITY", "LOADING", "TRANSFER", "ARRIVAL")) {
                    custom.addCheckpoint(id);
                }
                custom.addEdge("CHECK_IN", "SECURITY");
                custom.addEdge("SECURITY", "ARRIVAL");      // the rule the standard profile does not have

                BaggageValidator validator = new BaggageValidator(custom, CONFIG.referenceProcess(), "ARRIVAL");
                JourneyResult result = feed(validator, "BAG001", "CHECK_IN", "SECURITY", "ARRIVAL");

                TestHarness.assertEquals(JourneyResult.VALID_TRANSITION, result.eventResult,
                        "the same scan is valid once the graph contains the edge");
                TestHarness.assertEquals(BaggageValidator.STATUS_VALID, result.journeyStatus, "journey completes");
                TestHarness.assertEquals(2, result.skippedCheckpoints.size(),
                        "reference process still flags the LOADING and TRANSFER bypass");
            }
        });

        TestHarness.test("validator: reset clears queue, bags and counters", new TestHarness.Body() {
            public void run() {
                BaggageValidator validator = standardValidator();
                validator.submitScan("BAG001", "CHECK_IN");
                feed(validator, "BAG002", "CHECK_IN", "SECURITY", "SECURITY");
                validator.reset();

                TestHarness.assertTrue(validator.getQueue().isEmpty(), "queue cleared");
                TestHarness.assertEquals(0, validator.getBags().size(), "bags cleared");
                TestHarness.assertEquals(0, validator.getDuplicateScans(), "counters cleared");
                TestHarness.assertEquals(BaggageValidator.STATUS_INCOMPLETE,
                        feed(validator, "BAG003", "CHECK_IN").journeyStatus, "engine still works after reset");
            }
        });
    }
}
