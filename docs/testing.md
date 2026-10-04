# Testing

## 1. How to run the tests

```bash
./run.sh test          # Linux / macOS
run.bat test           # Windows
```

The script compiles `backend/src` into `build/`, `backend/tests` into
`build-tests/` and runs `tests.TestRunner` from the project root (the tests read
`data/*.json` with relative paths, so they must be run from the project root - the
scripts do this for you).

Equivalent manual commands:

```bash
javac -d build $(find backend/src -name '*.java')
javac -cp build -d build-tests $(find backend/tests -name '*.java')
java -cp build:build-tests tests.TestRunner        # Windows: build;build-tests
```

Exit code is `0` when every test passes, `1` otherwise, so the run is CI-friendly.
Current status: **45 tests, 45 passing**.

## 2. Why there is no JUnit

The project must build and run with nothing but a JDK - no internet, no dependency
manager, no jar downloads. `backend/tests/TestHarness.java` is a ~70-line harness
that offers `assertEquals`, `assertTrue`, `assertFalse`, `assertNull`,
`assertNotNull` and `assertContains`, prints a per-test PASS/FAIL line, and returns
a non-zero exit code on failure. Numbers are compared numerically so counters
(`long`) and literals (`int`) mix freely.

## 3. Test inventory

### `GraphTest` - the graph (8 tests)

| Test | Asserts |
| --- | --- |
| node insertion | nodes can be added, duplicate insertion is ignored, `nodeCount` is correct, unknown nodes are absent |
| edge insertion | edges exist, direction matters, endpoints are auto-created, duplicate edges are not double-counted |
| valid transition lookup | all standard-profile edges exist; `LOADING` has exactly the two expected successors |
| invalid transition lookup | `SECURITY → ARRIVAL` is absent, `ARRIVAL` is terminal, no self-loop, unknown source has no edges |
| BFS traversal and shortest path | BFS visit order, reachability from several nodes, shortest path is `LOADING → ARRIVAL` (2 nodes) and `CHECK_IN → … → ARRIVAL` (4 nodes, skipping `TRANSFER`), self-path length 1 |
| BFS detects an unreachable target | dead-end profile: `LOADING → ARRIVAL` unreachable with an empty path, while `CHECK_IN`/`SECURITY` can still reach `ARRIVAL`; `nodesThatCannotReach` lists `LOADING`, `QUARANTINE` and `TRANSFER` but not `CHECK_IN` |
| DFS agrees with BFS | DFS order starts at the source and visits all reachable nodes; DFS reachability matches BFS on both profiles and respects direction |
| configuration is data, not code | the same pair `SECURITY → ARRIVAL` is illegal in the standard profile and legal in a hand-built graph |

### `QueueTest` - the queue (5 tests)

| Test | Asserts |
| --- | --- |
| enqueue and size | a new queue is empty; size grows with enqueues |
| dequeue removes the front element | FIFO removal, size decreases, empty queue returns `null` |
| FIFO ordering is preserved | six events for three interleaved bags come back in submission order |
| peek does not remove | `peek` is repeatable, does not change size, front is still dequeueable |
| empty queue and clear() | `peek`/`dequeue`/`toList` on empty, 1,000-element queue, `toList` is front-first, `clear` empties it |

### `HashTableTest` - the hash table (6 tests)

| Test | Asserts |
| --- | --- |
| insert and lookup | values are retrievable, `size` and `keys` are correct |
| update replaces an existing value | `put` returns the replaced value; `update` changes values, keeps size stable, inserts missing keys |
| missing key behaves safely | `get`/`remove` return `null`, `contains` is false, `null` key lookup is safe |
| collision handling via separate chaining | 60 keys in 4 buckets: everything is stored and retrievable, `collisions() > 0`, the table grew |
| rehashing preserves every entry | 500 keys: size, rehashes, load factor ≤ 0.75, longest chain > 1, all 500 readable, `remove` works |
| same hash bucket, different keys | the classic `"Aa"`/`"BB"` collision pair keeps two separate values |

### `ValidatorTest` - the engine (13 tests)

| Test | Asserts |
| --- | --- |
| valid journey is accepted | five scans → four valid transitions, `VALID JOURNEY`, arrived, no anomaly, counters correct |
| first scan initialises the journey | unknown bag is not in the table before, record created after, no `fromCheckpoint`, status `INCOMPLETE JOURNEY`, `ARRIVAL` reachable |
| duplicate scan in a row | `DUPLICATE SCAN` + `DUPLICATE CHECKPOINT`, not accepted, still at `SECURITY`, anomaly counter, history keeps all scans |
| invalid transition is rejected | `INVALID TRANSITION`, not accepted, position unchanged, rejected edge named in the detail, `ARRIVAL` still reachable |
| incomplete journey is not an anomaly | status `INCOMPLETE JOURNEY`, no anomaly type, remaining BFS route `LOADING → ARRIVAL`, and finishing makes the same bag valid |
| `ARRIVAL UNREACHABLE` via traversal | identical scans are merely incomplete on the standard graph but `ARRIVAL UNREACHABLE` on the dead-end graph, empty remaining path, one anomaly recorded |
| unknown checkpoint | `UNKNOWN CHECKPOINT`, not accepted, last valid position kept, counter incremented, bag can recover with the next legal scan |
| unknown bag created once | the record is created on first sight and not duplicated on the second scan |
| reference process advisory | legal `LOADING → ARRIVAL` yields `VALID JOURNEY`, no anomaly, `TRANSFER` reported as skipped, advisory text attached |
| rejected scans never advance the state | after an invalid, a duplicate and an unknown scan: position and accepted path unchanged, three anomalies, bag still finishes later |
| queue FIFO across bags | six queued events for three bags are processed in order and each bag's state is updated independently; empty queue returns `null` |
| the graph defines the rules | the same scan sequence becomes `VALID JOURNEY` once the graph contains `SECURITY → ARRIVAL` |
| reset clears state | queue, bags and counters cleared, engine still usable afterwards |

### `DemoScenarioTest` - the five demo scenarios (6 tests)

Reads `data/demos.json` (the same file the dashboard uses) and validates each demo
exactly as the UI does: switch profile → validate its scans → assert the expected
outcome. This prevents the documentation, the demo buttons and the engine from
drifting apart.

| Demo | Required outcome |
| --- | --- |
| demo 1 | `VALID JOURNEY`, arrived at `ARRIVAL`, no anomaly |
| demo 2 | `DUPLICATE SCAN` / `DUPLICATE CHECKPOINT`, state did not move |
| demo 3 | `INVALID TRANSITION` / `SECURITY → ARRIVAL` named in the detail |
| demo 4 | `INCOMPLETE JOURNEY`, no anomaly, `ARRIVAL` reachable |
| demo 5 | event 1 and 2 are `INCOMPLETE JOURNEY`; event 3 is `ARRIVAL UNREACHABLE` with `arrivalReachable == false`, stuck at `LOADING` |

### `IntegrationTest` - engine and API payloads (7 tests)

| Test | Asserts |
| --- | --- |
| boots from data files | default profile, 6 nodes, 5 edges, `ARRIVAL`, 5-step reference process |
| state payload shape | all keys present, node/edge counts, node order and coordinates come from the data file, hash internals reported, payload serialises to JSON |
| demo 1 end to end through the queue | start queues 5 events, step mode processes exactly 1 leaving 4, batch mode processes the rest, final status `VALID JOURNEY`, stats agree |
| profile switching rebuilds the graph | demo 5 switches to `deadend`, 3 edges, standard edges gone, result `ARRIVAL UNREACHABLE`, switching back restores 5 edges and clears state |
| sample stream | 14 events, all processed, 3 bags, at least one valid journey, at least one duplicate, at least one invalid transition, queue drained |
| invalid input rejected safely | unknown profile and unknown demo both throw; the current profile and queued work are untouched |
| reset | bags, counters and queue cleared; the profile is kept |

## 4. What is deliberately not covered

* **HTTP-level tests.** `ApiServer` is a thin framing layer over the `Engine`
  payloads that `IntegrationTest` already validates. Spinning up a server inside the
  test suite would add flakiness (ports, timing) without testing new logic. The
  endpoints were exercised manually end to end in the browser.
* **Browser/JS tests.** The frontend holds no validation logic, so there is nothing
  algorithmic to test there. The five demos, the sample stream and the profile
  switch were verified manually in the dashboard.
* **Large-scale stress tests in the suite.** Scaling is measured by
  `benchmark.Benchmark` (documented in `docs/complexity.md`), keeping the test suite
  fast and deterministic.
* **Concurrency.** The API is single-threaded by design, so the structures are not
  exercised under concurrent mutation.

## 5. Adding a test

```java
// backend/tests/MyTest.java
package tests;

public final class MyTest {
    public static void register() {
        TestHarness.test("my structure: does the thing", new TestHarness.Body() {
            public void run() {
                TestHarness.assertEquals(4, 2 + 2, "arithmetic still works");
            }
        });
    }
}
```

Then add `MyTest.register();` to `TestRunner.main`. Tests that need the engine can
build one directly:

```java
ConfigLoader config = new ConfigLoader("data");
BaggageValidator validator = new BaggageValidator(
        config.buildGraph("standard"), config.referenceProcess(), config.finalCheckpoint());
```
