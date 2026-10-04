# Baggage Journey Integrity Checker

A DSA-based system for validating baggage scan sequences against a predefined checkpoint-transition graph and identifying journey inconsistencies.

**This is a simulation.** It uses synthetic scan events, it is not connected to any airline or airport system, and it makes no claim to model a complete real-world baggage-handling process.

---

## 1. Problem

An airport bag produces a sequence of scan events during its journey:

```
BAG001 → CHECK_IN
BAG001 → SECURITY
BAG001 → LOADING
BAG001 → TRANSFER
BAG001 → ARRIVAL
```

Real baggage systems already emit these events. The question this project answers is:

> **Given a stream of scan events, is the observed journey consistent with the allowed process, and if not, exactly what is wrong with it?**

The system must distinguish between five outcomes:

| Outcome | Meaning |
| --- | --- |
| **Valid journey** | The bag reached the terminal checkpoint `ARRIVAL` along legal transitions |
| **Invalid checkpoint transition** | The observed move is not an edge of the process graph |
| **Duplicate checkpoint scan** | The same checkpoint was scanned twice in a row |
| **Incomplete journey** | All observed transitions are legal, but `ARRIVAL` was never observed |
| **ARRIVAL unreachable** | The graph structure no longer allows the bag to reach `ARRIVAL` |

The important part is that these are **different** findings with different operational meanings, and the system reports them with precise terminology instead of calling everything "missing bag".

## 2. Real-world motivation

Baggage tracking is a regulated industry practice, not a hypothetical.

* **IATA Resolution 753** requires member airlines to track baggage at a minimum of four core tracking points: **acceptance (check-in), loading, transfer and arrival/delivery** ([IATA](https://www.iata.org/en/programs/ops-infra/baggage/baggage-tracking/)).
* IATA's 2024 global progress report, based on a survey of 155 airlines and 94 airports, found **44% of airlines fully implemented** Resolution 753 with a further 41% in progress, and **75% of airports capable** of Res. 753 tracking. Optical barcode scanning is the dominant technology (73% of airports), with RFID at 27% ([IATA press release, 9 May 2024](https://www.iata.org/en/pressroom/2024-releases/2024-05-09-01/)).
* The same report states that **between 2007 and 2022 baggage mishandling fell by nearly 60%**, and that in 2022 the global rate of mishandled bags was **7.6 per 1,000 passengers** (SITA data), with the majority returned within 48 hours.

Two takeaways from that material shape this project:

1. The four mandatory tracking points are *exactly* the checkpoints our graph models (plus a security checkpoint used as an extra process step in our simulation).
2. Because tracking messages are produced by many different systems and exchanged between airlines, **the consistency of the event stream itself is a real operational concern**. A sequence that jumps, repeats, or stalls carries information about the physical handling of the bag.

**What we do not claim:** we do not claim integration with any airline, we do not claim to reproduce the full IATA message standard, we do not claim real-world accuracy from synthetic data, and we do not claim that graph traversal predicts physical baggage movement. The defensible claim is the one in the title: *validating scan sequences against a predefined checkpoint-transition graph to identify journey inconsistencies*.

## 3. Scope

**In scope**

* A configurable directed graph of legal checkpoint transitions (data, not code).
* A custom FIFO queue for incoming scan events.
* A custom hash table storing baggage records by bag ID.
* Scan-by-scan validation with precise anomaly classification.
* BFS/DFS reachability: "can this bag still reach `ARRIVAL`?"
* A single polished dashboard that visualises the journey and the graph.
* Automated tests, complexity analysis and a measured performance demonstration.

**Explicitly out of scope** (deliberately not built)

AI/ML, computer vision, RFID or IoT hardware, passenger tracking, airline reservation systems, routing or shortest-path optimisation, airport logistics optimisation, prediction models, cloud infrastructure, authentication, payment, microservices, unnecessary databases, and any form of generic "airport dashboard".

## 4. Solution

A single Java process contains everything:

```
scan events ──► CustomQueue (FIFO) ──► BaggageValidator ──► JourneyResult
                                            │
                              ┌─────────────┼──────────────┐
                              ▼             ▼              ▼
                     CustomHashTable      Graph        BFS / DFS
                     (BAG001 → Baggage)  (legal       (can ARRIVAL still
                                          transitions)  be reached?)
                                            ▲
                                            │
                                     data/*.json
                                  (the rules, not code)
```

The rulebook is **configuration**: `data/transitions.json` defines the graph profiles, `data/checkpoints.json` the vocabulary, `data/reference_process.json` the explicit ordered reference sequence, `data/demos.json` the five demo scenarios and `data/sample_scans.json` a deterministic sample stream. Change the JSON, and the validation rules change - there is no `if (previous == SECURITY && next == ARRIVAL) reject();` anywhere in the codebase.

## 5. DSA concepts

Exactly three core structures, plus traversal where it does real work:

| # | Structure | Implementation | Where it lives |
| --- | --- | --- | --- |
| 1 | **Directed graph** (adjacency list) | `backend/src/dsa/Graph.java` | Defines which transitions are legal |
| 2 | **Hash table** (separate chaining) | `backend/src/dsa/CustomHashTable.java` | Baggage records `BAG001 → Baggage`, plus the graph's adjacency storage and the BFS/DFS visited-set |
| 3 | **Queue** (FIFO) | `backend/src/dsa/CustomQueue.java` | Incoming scan events, validated in arrival order |
| - | **BFS / DFS** | `Graph.bfsPath`, `Graph.bfsOrder`, `Graph.dfsOrder`, `Graph.dfsReachable` | Reachability: *is `ARRIVAL` still reachable from the current checkpoint?* |

None of the assessed structures is a `java.util` class. `java.util.List/Map` appear only for configuration plumbing and display.

## 6. Why each DSA is used

**Why a graph?** The process is a set of *relationships* between checkpoints, not a list of rules. A graph expresses "from `LOADING` you may go to `TRANSFER` or `ARRIVAL`" once, and every validation question then becomes a graph query. Rules stay in data, and the same graph can be visualised and traversed.

**Why directed?** Baggage movement is not symmetric: `TRANSFER → ARRIVAL` is legal, `ARRIVAL → TRANSFER` is not. Direction is part of the domain.

**Why an adjacency list and not a matrix?** The checkpoint graph is tiny and sparse (6 nodes, 5 edges). A matrix would waste O(V²) space and would have to be rebuilt whenever the configurable graph changes; list storage costs O(V + E) and adding a checkpoint is one bucket insert.

**Why a hash table?** Validation is dominated by "which bag is this scan for?" over a stream where events for different bags are interleaved. A keyed lookup gives O(1) average access to the baggage record from the bag ID, with no scanning of a list.

**Why a queue?** Scan events arrive as a stream and must be processed in the order they were received (FIFO): events for `BAG001` and `BAG002` are interleaved, and reordering them would silently change the result of a journey. A queue also decouples event intake from validation, which is exactly how a scanner gateway behaves.

**Why BFS/DFS at all?** Because one question cannot be answered by any single edge check: *after this scan, can this bag still reach `ARRIVAL` through some sequence of legal transitions?* That is reachability, and reachability is a traversal problem. BFS additionally returns the **shortest remaining route**, which the dashboard draws as the remaining journey. Ordinary transition validation (one scan at a time) genuinely needs only `hasEdge` - we do not pretend otherwise.

**Why not Dijkstra?** Every legal transition costs one hop, so all edges have equal weight. Dijkstra would add a priority queue and produce exactly the same answer as BFS. Using BFS is the correct, simpler choice.

**Why not a database?** The system must be a self-contained demo that runs offline in one command with no installers. All state is in memory and rebuilt deterministically from JSON; persistence is not part of the problem being solved.

**Why not machine learning?** The task is a rule-consistency problem with an exact answer. A classifier would replace a provable answer with a probabilistic one, and would hide the DSA the project is meant to demonstrate.

## 7. Algorithm

For every scan event (this is steps 1-7 of the specification):

```
dequeue event (bagId, checkpoint)

1. bag ← hashTable.get(bagId)                     // custom hash table
2. if bag is null or bag.current is null:
       initialise the journey at 'checkpoint'     // accepted, no transition checked
       result ← VALID, then go to step 7
3. current ← bag.current
4. if checkpoint == current:
       result ← DUPLICATE SCAN                    // state unchanged
   else if graph.hasEdge(current, checkpoint):    // the GRAPH decides, not code
       bag.previous ← current ; bag.current ← checkpoint
       skipped ← referenceProcess check           // ADVISORY only, see §9
       result ← VALID TRANSITION
   else:
       result ← INVALID TRANSITION                // state unchanged
5. record the outcome and, for anomalies, the anomaly type
6. update counters and the baggage record
7. reachable ← graph.bfsPath(bag.current, ARRIVAL) // BFS/DFS reachability
   journeyStatus ← evaluate(bag, reachable)

repeat until the queue is empty
```

`evaluate` applies this documented precedence:

1. bag is at `ARRIVAL` → `VALID JOURNEY` (or `ANOMALY DETECTED` if defects were recorded earlier)
2. `ARRIVAL` is no longer reachable → `ARRIVAL UNREACHABLE`
3. a defect was recorded → `ANOMALY DETECTED`
4. otherwise → `INCOMPLETE JOURNEY`

Note the deliberate asymmetry in step 4: **a rejected scan never advances the baggage state**. Duplicates, invalid transitions and unknown checkpoints are recorded on the bag, but the bag stays where it was, so a later legal scan can still recover the journey.

## 8. Architecture

```
project/
├── backend/
│   ├── src/
│   │   ├── dsa/          Graph.java, CustomQueue.java, CustomHashTable.java
│   │   ├── model/        Baggage.java, ScanEvent.java, JourneyResult.java
│   │   ├── service/      BaggageValidator.java, ConfigLoader.java, Engine.java
│   │   ├── api/          ApiServer.java
│   │   ├── util/         Json.java
│   │   └── benchmark/    Benchmark.java
│   └── tests/            TestHarness, GraphTest, QueueTest, HashTableTest,
│                         ValidatorTest, DemoScenarioTest, IntegrationTest
├── frontend/             index.html, styles.css, app.js
├── data/                 checkpoints.json, transitions.json, reference_process.json,
│                         demos.json, sample_scans.json
├── docs/                 architecture.md, dsa.md, complexity.md, testing.md,
│                         viva.md, references.md
├── run.sh / run.bat      build | test | serve | benchmark
└── README.md
```

**No frameworks, no build tool, no dependency manager.** `javac` and `java` are the entire toolchain, which keeps the project runnable on any machine with a JDK.

**Frontend ↔ backend.** The DSA engine is Java, so the simplest reliable architecture is a small HTTP layer built into the JDK (`com.sun.net.httpserver.HttpServer`) that (a) serves the static frontend and (b) exposes the engine as JSON. One process, one command, no CORS problems, no Python sidecar, no microservices. The API handler is deliberately single-threaded so the in-memory engine needs no locking around a request.

**Separation of concerns.**

* `dsa/` knows nothing about baggage - it is a generic graph, queue and hash table.
* `model/` is data only.
* `service/BaggageValidator` is the algorithm; `service/Engine` owns profile switching and JSON payloads.
* `api/ApiServer` is transport only (routing, static files, JSON framing).
* `frontend/` contains no validation logic at all - the browser sends events and renders what the engine returns.

**One graph, not two.** The dashboard draws the nodes, coordinates and edges reported by `/api/state`, all of which come from the same Java `Graph` instance used for validation. There is no second, decorative graph in JavaScript.

## 9. Anomalies: precise terminology

| Finding | Example | Type recorded | Why it is *this* and not something else |
| --- | --- | --- | --- |
| **Duplicate scan** | `CHECK_IN → SECURITY → SECURITY` | `DUPLICATE CHECKPOINT` | The bag is already at `SECURITY`; the graph has no self-transition, so this is a repeated observation, not a move |
| **Invalid transition** | `CHECK_IN → SECURITY → ARRIVAL` | `INVALID TRANSITION` | `SECURITY → ARRIVAL` is simply not an edge of the configured graph |
| **Incomplete journey** | `CHECK_IN → SECURITY → LOADING` (no `ARRIVAL` yet) | status `INCOMPLETE JOURNEY` | Nothing is wrong with what was observed - the journey is unfinished |
| **ARRIVAL unreachable** | dead-end profile after `LOADING` | `ARRIVAL UNREACHABLE` | BFS proves that no sequence of legal transitions from the current checkpoint reaches `ARRIVAL` |
| **Unknown checkpoint** | a scan at a checkpoint that is not in the vocabulary | `UNKNOWN CHECKPOINT` | No transition rule applies to a checkpoint the process does not define |
| **Expected checkpoint skipped** | legal `LOADING → ARRIVAL` in the standard profile | `EXPECTED CHECKPOINT SKIPPED: TRANSFER` (advisory) | Only the *explicit reference process* makes this inference valid - see below |

**On "missing checkpoint".** A general graph cannot tell you which checkpoint is missing: a legal shortcut is legal. So the system only reports a skipped checkpoint when an **explicit ordered reference process** (`data/reference_process.json`) states what the expected order is. That report is an **advisory**, not an anomaly: the journey is still `VALID JOURNEY`, with the note that `TRANSFER` was bypassed. This distinction is deliberately visible in the UI (green status, amber advisory).

## 10. Installation

Requirements: **JDK 17 or newer** (`javac` and `java` must be on the `PATH`). Nothing else. No Maven, no Gradle, no npm, no Python, no internet access at runtime.

```bash
git clone <this repository>
cd baggage-journey-integrity-checker
java -version      # must be 17+
```

## 11. Running

Everything goes through one script:

```bash
./run.sh build       # compile the engine + tests into build/ and build-tests/
./run.sh test        # compile and run all 45 automated tests
./run.sh serve       # start the dashboard on http://localhost:8080
./run.sh benchmark   # run the performance demonstration
```

Windows:

```bat
run.bat serve
run.bat test
run.bat benchmark
```

Optional arguments: `./run.sh serve 9000` (port) and `./run.sh benchmark 20000` (bag count).

The dashboard then lives at **http://localhost:8080/** and the raw engine state at **http://localhost:8080/api/state**.

If a local JDK is present in `.tools/jdk*/`, the scripts use it automatically (that is how this checkout was built and tested); otherwise they use `javac`/`java` from the `PATH`.

## 12. Demo

Five built-in scenarios, each one click in the dashboard (panel **2 · Demo scenarios**). Each demo switches the graph profile it needs, clears state and then streams its scans through the queue one event at a time so the animation is visible.

| Demo | Scans | Observed result |
| --- | --- | --- |
| 1 - Valid journey | `CHECK_IN → SECURITY → LOADING → TRANSFER → ARRIVAL` | `VALID JOURNEY` |
| 2 - Duplicate scan | `CHECK_IN → SECURITY → SECURITY` | `ANOMALY DETECTED` / `DUPLICATE CHECKPOINT` |
| 3 - Invalid transition | `CHECK_IN → SECURITY → ARRIVAL` | `ANOMALY DETECTED` / `INVALID TRANSITION`, bag stays at `SECURITY` |
| 4 - Incomplete journey | `CHECK_IN → SECURITY → LOADING` | `INCOMPLETE JOURNEY` (no anomaly, `ARRIVAL` still reachable) |
| 5 - Unreachable arrival | same scans as demo 4, **on the dead-end graph profile** | `INCOMPLETE JOURNEY`, `INCOMPLETE JOURNEY`, then `ARRIVAL UNREACHABLE` |

Demo 5 is the important one for the viva. The scans are **identical to demo 4**; only the graph differs. In that profile `ARRIVAL` is still reachable from `CHECK_IN` and `SECURITY` (via `SECURITY → ARRIVAL`), so the first two events are merely `INCOMPLETE JOURNEY`. Only when the bag reaches `LOADING`, which now leads to `QUARANTINE` - a checkpoint with no outgoing transition - does BFS prove that `ARRIVAL` can no longer be reached. No single edge check could produce that finding: it is a property of the graph around the current node, not of the last transition.

Additional ways to drive it:

* **Load sample stream** enqueues 14 deterministic events for 3 bags (a valid journey, a legal shortcut, a duplicate, an invalid transition) and validates them with animation.
* **Scan event simulator** - type a bag ID, click a checkpoint chip, then `Enqueue scan`, `Process next` or `Process all` to drive it by hand.
* **Graph profile** dropdown switches between the standard and the dead-end graph. Switching profiles rebuilds the graph and clears journeys, because previously validated journeys were validated under different rules.

The right-hand panel shows live statistics, the internals of the custom hash table (capacity, load factor, collisions, longest chain, rehashes) and the reference process.

## 13. Testing

```bash
./run.sh test
```

45 tests, all passing, covering exactly the areas required:

| Suite | Tests | Covers |
| --- | --- | --- |
| `GraphTest` | 8 | node insertion, edge insertion, valid/invalid transition lookup, BFS order and shortest path, unreachable targets, DFS agreement with BFS, "the graph is data" |
| `QueueTest` | 5 | enqueue, dequeue, FIFO ordering across interleaved bags, peek, empty queue, clear |
| `HashTableTest` | 6 | insert, lookup, update, collision handling (60 keys in 4 buckets, including the classic `"Aa"`/`"BB"` collision pair), missing keys, rehashing |
| `ValidatorTest` | 13 | valid journey, first scan, duplicate, invalid transition, incomplete, unreachable `ARRIVAL`, unknown checkpoint, unknown bag, skipped-checkpoint advisory, rejected scans never advance state, FIFO batch processing, graph-driven rules, reset |
| `DemoScenarioTest` | 6 | all five demo scenarios end to end, driven from `data/demos.json` |
| `IntegrationTest` | 7 | boot from data files, API payload shape, demo 1 through the queue, profile switching, sample stream, invalid input handling, reset |

The tests are plain Java - a 60-line harness (`backend/tests/TestHarness.java`) replaces JUnit so that the project has **zero dependencies** and one command runs everything.

## 14. Complexity

Let **V** = checkpoints, **E** = legal transitions, **n** = checkpoints scanned so far for one bag, **k** = keys stored.

| Operation | Average | Worst case | Note |
| --- | --- | --- | --- |
| `Graph.addCheckpoint` | O(1) | O(V) | hash insert; worst case only under pathological collisions |
| `Graph.addEdge` | O(out-degree) | O(out-degree) | duplicate-edge scan over a short list |
| `Graph.hasEdge` | O(out-degree) | O(out-degree) | a transition check, not a path search |
| `Graph.neighbors` | O(1) copy | O(out-degree) | |
| `Graph.bfsOrder` / `bfsPath` | O(V + E) | O(V + E) | BFS explores each node and edge once |
| `Graph.dfsOrder` / `dfsReachable` | O(V + E) | O(V + E) | recursion depth up to O(V) |
| `CustomQueue.enqueue/dequeue/peek` | O(1) | O(1) | linked list with head + tail |
| `CustomHashTable.put/get/update/contains/remove` | O(1) | O(k) | worst case: every key in one chain |
| `CustomHashTable` rehash | O(k) | O(k) | amortised O(1) per insertion, triggers at load factor > 0.75 |
| Validate one scan event | O(1) + O(V + E) | O(k) + O(V + E) | hash lookup + edge check + reachability BFS |
| Validate a stream of *m* events | O(m · (V + E)) | O(m · (k + V + E)) | the dominant cost is one BFS per accepted scan |
| Memory | O(V + E + k + total scans) | | scan histories are stored per bag |

Hash-table operations are **not** claimed to be constant-time without qualification: they are O(1) *on average*, O(k) in the worst case when all keys collide into one bucket. In this project the graph is tiny (V = 6, E = 5), so the per-scan traversal is a handful of steps; the asymptotic result matters mainly because it shows the design scales to bigger process graphs.

**Measured performance** (not estimated - reproduce with `./run.sh benchmark`; JDK 17.0.20.1 Temurin, Linux, median of 3 runs, synthetic data):

| Bags | Events | Median time | Events / second | µs per event |
| --- | --- | --- | --- | --- |
| 100 | 467 | 3 ms | 155,667 | 6.42 |
| 500 | 2,363 | 11 ms | 214,818 | 4.66 |
| 1,000 | 4,727 | 17 ms | 278,059 | 3.60 |
| 5,000 | 23,596 | 49 ms | 481,551 | 2.08 |

Three honest observations: per-event cost *falls* as the batch grows because JVM JIT compilation is amortised over more work (the first runs are slower); repeat runs on the same machine varied by a few milliseconds at the small sizes (for example 9-11 ms at 500 bags and 15-17 ms at 1,000 bags), which is normal warm-up noise rather than a different algorithm; and these numbers measure validation only - not file I/O or HTTP. The dataset mixes valid journeys, a duplicate every 10th bag and an invalid transition every 17th bag, so the BFS reachability check is genuinely exercised.

## 15. Limitations

* **Simulation only.** Scan events are synthetic and generated by us; the demo is differentiable from reality.
* **Simplified process.** Real baggage handling has many more custody points, exceptions, offloads and interline handovers. Our graph is a teaching-scale abstraction of the four IATA Res. 753 tracking points plus a security step.
* **No physical inference.** Reaching `ARRIVAL` in the graph means an `ARRIVAL` scan was observed and the transitions were legal. It says nothing about where the bag physically is. The system checks *event consistency*, not reality.
* **Detected anomalies are not diagnoses.** A duplicate scan may be a real double scan or a duplicated message; an invalid transition may be a mis-scanned bag or a data error. The system reports *what is inconsistent*, and does not speculate about causes.
* **In-memory only.** No persistence: restarting the server clears all journeys.
* **No timestamps.** Events are ordered by arrival, not by time, so dwell times and delays cannot be analysed.
* **Bounded inputs.** No authentication, no rate limiting, no input size limits beyond the in-memory dataset; it is a single-user demo, not a deployed service.
* **Skipped checkpoints are inferred, not proven.** They are reported only against the explicit reference process, and only as advisories.

## 16. References

Primary real-world sources:

1. IATA - *Baggage Tracking (Resolution 753)*: <https://www.iata.org/en/programs/ops-infra/baggage/baggage-tracking/>
2. IATA - *Industry Makes Progress to Reduce Baggage Mishandling, New Survey Reveals* (9 May 2024): <https://www.iata.org/en/pressroom/2024-releases/2024-05-09-01/>
3. IATA - *Resolution 753 Implementation Guide* (PDF): <https://www.iata.org/contentassets/d22bcfe86aa54f22a2919b0e4224f68f/reso753-implementation-guide---2023_issue-4.0.pdf>
4. SITA - *Baggage IT Insights* (industry mishandled-bag statistics): <https://www.sita.aero/resources/surveys-reports/sita-baggage-it-insights/>

Textbook material for the data structures:

5. Cormen, Leiserson, Rivest, Stein - *Introduction to Algorithms*, 4th ed. (graphs, BFS/DFS §20, hash tables §11).
6. Sedgewick, Wayne - *Algorithms*, 4th ed. (adjacency-list graphs, symbol tables).

Full details and how each source is used: [`docs/references.md`](docs/references.md).

---

## Further documentation

| Document | Contents |
| --- | --- |
| [`docs/architecture.md`](docs/architecture.md) | Component map, request flows, design decisions and rejected alternatives |
| [`docs/dsa.md`](docs/dsa.md) | The three structures in detail, with the invariants and the exact operations used |
| [`docs/complexity.md`](docs/complexity.md) | Full operation-by-operation complexity analysis and the measured benchmark |
| [`docs/testing.md`](docs/testing.md) | Test inventory, how to run them, and the demo-scenario acceptance criteria |
| [`docs/viva.md`](docs/viva.md) | Concise answers to the questions a DSA examiner will ask |
| [`docs/references.md`](docs/references.md) | Sources, what each one supports, and the academic-honesty statement |

**Academic honesty statement.** This project is a simulation. It is not AI-powered, predictive, intelligent or autonomous; it is not integrated with any airline; it does not guarantee prevention of lost baggage; its synthetic data proves nothing about real-world accuracy; and graph traversal does not predict physical baggage movement. What it does do is implement a graph, a queue, a hash table and BFS/DFS from scratch, and use them to validate baggage scan sequences against a predefined checkpoint-transition graph.
