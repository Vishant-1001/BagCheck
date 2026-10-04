# Viva preparation

Short, defensible answers. Every claim below is backed by code that can be opened
during the viva; file paths are given so the examiner can jump straight there.

---

## A. The required questions

### 1. Why a graph?

Because the domain is a set of *relationships between checkpoints*, not a list of
independent rules. Real baggage processes say "from `LOADING` you may go to
`TRANSFER` or to `ARRIVAL`" - that is an edge. Once the rules are a graph, every
question we need to ask is a graph question: is this an edge (`hasEdge`), and can I
still get to `ARRIVAL` (`BFS`). It also means the rules can live in configuration
(`data/transitions.json`) instead of in `if/else` chains.

### 2. Why a *directed* graph?

Baggage movement is not symmetric. `TRANSFER → ARRIVAL` is legal; `ARRIVAL → TRANSFER`
is not - a bag that has been delivered cannot go back to the transfer belt.
An undirected graph would silently accept that reversal. Proof in the code:
`Graph.hasEdge(a,b)` does not imply `hasEdge(b,a)`, and `GraphTest` asserts it.

### 3. Why an adjacency list and not a matrix?

* The graph is **sparse**: 6 nodes but only 5 legal transitions. A matrix would be
  36 cells to store 5 truths (and O(V²) space for any process graph).
* Checkpoints are **configurable at runtime**. Adding a checkpoint to a matrix means
  reallocating and rebuilding it; with lists it is one bucket insert.
* Both operations we need are cheap: `hasEdge` is O(out-degree) - two comparisons for
  `LOADING` - and BFS/DFS want exactly the neighbour list, which is O(V + E) instead
  of O(V²) to inspect.

### 4. Why a hash table?

Every scan event starts with the same question: *which bag record is this?* Under a
stream where events for many bags are interleaved, that lookup happens once per
event. A hash table answers it in O(1) average time by the bag ID, instead of
scanning a list of bags (O(k)) per event. It is also reused inside the graph for
the adjacency list and inside BFS/DFS as the visited-set, so it is not a token
structure. Implementation: `backend/src/dsa/CustomHashTable.java`, separate
chaining, rehash at load factor 0.75.

### 5. Why a queue?

Scan events form a **stream that must be processed in arrival order**. Consider:

```
BAG001 → CHECK_IN
BAG002 → CHECK_IN
BAG001 → SECURITY
```

If `BAG001 → SECURITY` were validated before `BAG001 → CHECK_IN`, the engine would
report an anomaly that never happened. FIFO order is what preserves the causality of
observations. The queue also decouples intake from validation, so events can arrive
faster than they are validated, exactly like a scanner gateway buffering messages.
Implementation: `backend/src/dsa/CustomQueue.java` (linked list, head + tail, all
operations O(1) worst case).

### 6. Why BFS/DFS?

For **reachability**: "from the bag's current checkpoint, can `ARRIVAL` still be
reached through some sequence of legal transitions?" That is a path-existence
problem, and path existence is exactly what traversal solves. BFS additionally
returns the **fewest-hop remaining route**, which the dashboard draws as the
remaining journey. DFS answers the same reachability question and is used as a
cross-check (a test asserts both agree).

### 7. Why is BFS/DFS *not* required for every scan validation?

Because a transition check is a **single-edge membership test**, not a path search.
Asking "does `SECURITY → ARRIVAL` exist?" is `Graph.hasEdge`, which is O(out-degree).
Running BFS to answer it would be wrong and wasteful. We state this openly: the
traversal is used only for the reachability question in step 7, never for ordinary
validation. (`docs/dsa.md` §1 and `docs/complexity.md` §6.)

### 8. Why not Dijkstra?

Dijkstra is for weighted shortest paths, and a priority queue would be needed to
select the minimum-distance node. Here every legal transition costs exactly one hop,
so BFS already yields the minimum-hop path - it is the special case of Dijkstra for
unweighted graphs. Adding Dijkstra would add code, a heap and no new information.

### 9. Why not a database?

The problem is *sequence validity*, not storage. All the state is a few hundred
records that are rebuilt deterministically from JSON at startup, and the project is
meant to run offline with one command and no installers. A database would add a
dependency, a schema and setup steps without answering any question the graph does
not already answer. We do note the limitation: state is in memory and is lost on
restart, which is why a database would be the first addition for a persistent
version.

### 10. Why not machine learning?

Because the task has an **exact** answer that is fully derivable from the configured
rules. A classifier would replace a provable "this edge does not exist" with a
probabilistic guess, would need labelled data we do not have, and would hide the
data structures this project is meant to demonstrate. Also, the system's job is to
report *inconsistency*, not to predict mishandling.

### 11. What constitutes an invalid transition?

An observed move `current → next` for which `Graph.hasEdge(current, next)` is false:
the destination is a defined checkpoint, but the edge does not exist in the active
profile. Example: `SECURITY → ARRIVAL` in the standard profile. Outcome: the scan is
**rejected**, the anomaly `INVALID TRANSITION` is recorded, and the baggage state is
**not** advanced - the bag stays where it was
(`BaggageValidator.validate`, the `hasEdge` branch).

### 12. How is a duplicate detected?

The bag's own recorded `currentCheckpoint` equals the scanned checkpoint **and** the
graph has no self-transition. The second scan does not represent a move, so it is
classified `DUPLICATE SCAN` / `DUPLICATE CHECKPOINT`, recorded as an anomaly, and the
state is left unchanged. Note the precision: it is a *repeated observation*, not an
invalid transition, because no transition was attempted. The dashboard draws a red
ring on the node instead of a red edge.

### 13. How is an *incomplete* journey different from an *invalid* transition?

* **Invalid transition**: something observed was *illegal* → recorded as an anomaly,
  status `ANOMALY DETECTED`.
* **Incomplete journey**: everything observed was *legal*, but the bag was never
  seen at `ARRIVAL` → no anomaly, status `INCOMPLETE JOURNEY`.

An incomplete journey is "we have not seen the end yet"; it makes no claim that
anything went wrong. This is exactly why the system does not report a missing bag on
the basis of an unfinished sequence.

### 14. How is "unreachable ARRIVAL" detected?

`BaggageValidator.evaluateJourney` runs `graph.reachable(current, ARRIVAL)`, a BFS
from the bag's current checkpoint. If BFS cannot reach `ARRIVAL`, no sequence of
legal transitions can, so the status becomes `ARRIVAL UNREACHABLE` and the finding is
recorded once per bag (`arrivalUnreachableReported`).

The demo that shows it is DEMO 5: the **same scans** as DEMO 4, but on the dead-end
profile where `LOADING → QUARANTINE` and `QUARANTINE` has no outgoing edge. In that
profile `ARRIVAL` is still reachable from `CHECK_IN` and `SECURITY`, so the first two
events are merely `INCOMPLETE JOURNEY`; only after the bag reaches `LOADING` does BFS
prove that `ARRIVAL` can no longer be reached. The scans are legal throughout - only
traversal can tell you that the journey has become impossible.

### 15. What are the limitations of the simulation?

* Synthetic scan events only; no connection to any airline or airport system.
* The process graph is a teaching-scale abstraction of the four IATA Resolution 753
  tracking points plus a security step, not a complete baggage-handling model.
* Reaching `ARRIVAL` in the graph means an `ARRIVAL` scan was observed with legal
  transitions - it is **not** evidence about where the bag physically is.
* Anomalies are observations of inconsistency, not diagnoses: a duplicate scan may be
  a real double scan or a duplicated message.
* No timestamps, so dwell times cannot be analysed; events are ordered by arrival.
* In-memory state, lost on restart; no persistence, no authentication, single user.
* Skipped checkpoints are inferred only from the explicit reference process, and only
  as an advisory - a general graph cannot prove what is missing.

---

## B. Anticipated follow-up questions

**Walk me through what happens when I press *Process next*.** The browser posts to
`/api/process`; `Engine.process(false)` → `BaggageValidator.processNext()` dequeues
one `ScanEvent` from the `CustomQueue` and calls `validate()`, which does
hash-lookup → duplicate check → `graph.hasEdge` → state update → reference-process
check → BFS reachability, then returns a `JourneyResult`. The API returns that result
plus the new state, and the frontend animates the transition and re-renders.

**Show me that the rules come from the graph and not from the code.** Open
`backend/src/service/BaggageValidator.java`: there is no comparison of checkpoint
names anywhere. The single decision is `graph.hasEdge(current, checkpoint)`. Then
change `data/transitions.json` (or use the profile dropdown) and watch the outcome
change for the same scans - that is DEMO 4 vs DEMO 5, and the test
*"the graph defines the rules, not the validator"*.

**Why is the first scan not checked against the graph?** Because there is no
previous checkpoint to move from; the first observation initialises the state
(`fromCheckpoint == null` in the result). Validating a "transition" from nothing
would be meaningless. An unknown *checkpoint* on the first scan is still rejected
and recorded.

**What happens to a rejected scan?** It is recorded in the scan history and as an
anomaly, but the current checkpoint, the previous checkpoint and the accepted path
are untouched. A test (`rejected scans never advance the state`) asserts this, and it
means a bag can recover: after a bad scan, a legal scan still advances the journey -
although the journey stays flagged `ANOMALY DETECTED` because the defects are on its
record.

**Why is a bag that had an anomaly and then reached ARRIVAL not "VALID JOURNEY"?**
Because the journey completed *with recorded defects*. Reporting `VALID JOURNEY`
would hide them. The status is `ANOMALY DETECTED` and the anomalies are listed.

**What is the time complexity of one scan event?** O(V + E) - a hash lookup O(1)
average, an edge check O(out-degree), and one BFS O(V + E). With this graph that is
a handful of operations; `docs/complexity.md` has the full table and measured
numbers (≈2–6 µs per event, 5,000 bags / 23,596 events in 49 ms).

**Why is the hash table not simply O(1)?** It is O(1) on *average*; the worst case is
O(k) when all keys collide into one bucket. Chaining bounds the damage but cannot
remove it. We state the average/worst-case distinction explicitly instead of
claiming constant time.

**Why separate chaining and not open addressing?** Chaining never fails to insert and
needs no tombstones, which matters because we support `remove` (used by `reset`).
Open addressing would need a probe sequence and a deletion policy - more code, more
edge cases, no benefit at this size.

**You can't use `HashMap`, so how is the graph stored?** The adjacency list is a
`CustomHashTable<List<String>>`, and BFS/DFS use the same custom table as the
visited set and the parent map. `java.util` appears only for configuration loading,
payloads, display and synthetic data generation.

**Why is the API single-threaded?** The engine is in-memory and mutable; handling
requests sequentially means the DSA code needs no locks, and demo output stays
deterministic. `Engine`'s public methods are still `synchronized` so a future
threaded server cannot corrupt state.

**Is the frontend graph the same graph as the engine's?** Yes. The dashboard draws
whatever `/api/graph` reports - nodes, coordinates and edges are all produced by the
Java `Graph` and the loaded profile. There is no second graph in JavaScript, and the
page contains no validation logic at all.

**What would you do with another month?** In priority order: (1) cache reachability
with one reverse BFS from `ARRIVAL` (O(V + E) once, then O(1) per scan) instead of a
BFS per scan; (2) persistence so journeys survive a restart; (3) event timestamps and
dwell-time rules; (4) an endpoint that ingests real IATA-style messages so the
simulation can be replayed against captured data. None of these changes the core DSA.

**What is the real-world relevance?** IATA Resolution 753 requires member airlines to
track baggage at acceptance, loading, transfer and arrival, and to exchange that data
with interline partners. In a stream produced by many systems, sequence consistency
is a real concern, and the four mandatory tracking points are exactly the checkpoints
this graph models. Our simulation is a simplified, offline version of that
consistency check - no more.

**What did you deliberately *not* build, and why?** AI/ML, computer vision, RFID/IoT,
routing optimisation, prediction, authentication, databases, microservices and
generic dashboards. The brief forbids them and none of them is needed to answer the
question the project asks. Keeping it small is what makes it correct and explainable.

---

## C. Five-minute demo script

1. **Framing (30 s).** "IATA Res. 753 requires tracking bags at acceptance, loading,
   transfer and arrival. In a real stream, event sequences can be inconsistent. We
   validate the sequence against a checkpoint-transition graph and name the exact
   inconsistency."
2. **Facts (30 s).** Show the graph card and the profile dropdown: 6 checkpoints,
   5 legal transitions, loaded from `data/transitions.json` - "the rules are data,
   not code".
3. **DEMO 1 (40 s).** Valid journey. Point at the green traversed path, the journey
   chain and `VALID JOURNEY`.
4. **DEMO 2 (30 s).** Duplicate scan: red ring on the node, `DUPLICATE CHECKPOINT`,
   state unchanged, and the remaining BFS route still drawn.
5. **DEMO 3 (30 s).** Invalid transition: red dashed "✕ rejected" edge, bag stays at
   `SECURITY`, and the reasons line explains which edge does not exist.
6. **DEMO 4 (30 s).** Incomplete journey: no anomaly at all - "unfinished is not
   wrong", remaining route still highlighted.
7. **DEMO 5 (60 s).** The same scans on the dead-end graph: the first two events are
   merely `INCOMPLETE JOURNEY`, then after `LOADING` the status flips to
   `ARRIVAL UNREACHABLE`.
   "Only BFS can tell you this; no single edge check can."
8. **Close (40 s).** Hash table internals in the statistics panel (capacity, load
   factor, collisions), the sample stream for scale, and `./run.sh test` → 45 tests
   passing, `./run.sh benchmark` → 23,596 events in 49 ms.

---

## D. Honesty checklist (say these out loud if asked)

* Simulation only; synthetic data; not connected to any airline.
* Not AI-powered, not predictive, not autonomous.
* The graph check verifies *event consistency*; it does not predict physical
  baggage movement, and it does not guarantee that bags are not lost.
* `ARRIVAL` unreachable means "no legal transition sequence reaches `ARRIVAL` in the
  configured graph", not "the bag is lost".
* A skipped checkpoint is an advisory inferred from an explicit reference process,
  never a proven fact.
* Benchmark numbers are measured on one machine with synthetic data and say nothing
  about real airport volumes.
