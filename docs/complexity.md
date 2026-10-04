# Complexity analysis

Symbols: **V** = checkpoints (nodes), **E** = legal transitions (edges),
**k** = keys stored in a hash table, **d** = out-degree of a single checkpoint,
**m** = number of scan events processed, **n** = checkpoints observed for one bag.

Average and worst case are stated separately wherever they differ. Hash-table
operations are **not** quoted as plain "O(1)": they are O(1) *on average* and O(k)
in the worst case (all keys hashing to one bucket). For the shipped graph, V = 6 and
E = 5, so the constant factors are tiny - the asymptotic analysis matters because the
design must still hold for a larger, configurable process graph.

## 1. Graph - adjacency list

| Operation | Time (average) | Time (worst case) | Space |
| --- | --- | --- | --- |
| `addCheckpoint(id)` | O(1) | O(V) | O(1) per node |
| `hasCheckpoint(id)` | O(1) | O(V) | - |
| `addEdge(from, to)` | O(d) | O(V) | O(1) per edge |
| `hasEdge(from, to)` | O(d) | O(V) | - |
| `neighbors(id)` | O(d) | O(d) | O(d) copy |
| `outDegree(id)` | O(d) | O(d) | - |
| `bfsOrder(start)`, `bfsPath(start, target)`, `reachable(start, target)` | O(V + E) | O(V + E) | O(V) |
| `dfsOrder(start)`, `dfsReachable(start, target)` | O(V + E) | O(V + E) | O(V) recursion stack |
| `nodesThatCannotReach(target)` | O(V · (V + E)) | O(V · (V + E)) | O(V) |

Notes.

* BFS/DFS visit every node at most once and inspect each adjacency entry once,
  which is where O(V + E) comes from.
* `hasEdge` is O(d), not O(1): the successor list is scanned. For a process graph
  d is a small constant (2 for `LOADING`, 1 for the others), so the practical cost
  is a couple of comparisons. With `java.util.HashSet` per node it would be O(1)
  average, but the graph must be built from this project's own hash table, and the
  extra structure is not justified at this size.
* An adjacency **matrix** would make `hasEdge` O(1) but cost O(V²) space and O(V)
  per checkpoint insertion, and would waste space on a graph that is 5/36 dense.
* `nodesThatCannotReach` runs one BFS per node and is only used for display.

## 2. Queue - linked list with head and tail

| Operation | Time | Space |
| --- | --- | --- |
| `enqueue(value)` | O(1) worst case | O(1) |
| `dequeue()` | O(1) worst case | O(1) |
| `peek()` | O(1) worst case | - |
| `isEmpty()`, `size()` | O(1) worst case | - |
| `clear()` | O(1) | frees references |
| `toList()` | O(size) | O(size) |

Every queue operation is worst-case constant time because a linked list with a tail
pointer needs no resizing. A circular-array implementation would be O(1) amortised
with occasional O(n) growth, and would need extra wraparound logic - one of the
reasons the linked version was chosen.

## 3. Hash table - separate chaining

| Operation | Time (average) | Time (worst case) | Space |
| --- | --- | --- | --- |
| `put(key, value)` | O(1) | O(k) | O(1) |
| `update(key, value)` | O(1) | O(k) | O(1) |
| `get(key)` | O(1) | O(k) | - |
| `contains(key)` | O(1) | O(k) | - |
| `remove(key)` | O(1) | O(k) | O(1) |
| `rehash()` | - | O(k) | O(k) |
| sequence of k insertions (including growth) | O(k) amortised | O(k²) pathological | O(k) |

Why the worst case is O(k): if every key lands in the same bucket, the chain has k
entries and a lookup walks it. Chaining bounds the damage (insertion still succeeds)
but cannot eliminate it; a better hash function or randomised hashing would only
reduce its probability.

Why the average is O(1): with load factor bounded at 0.75 by rehashing, the expected
chain length is a small constant (measured longest chain = 2 with 3 keys, 3 with 60
keys in the collision test).

Rehashing: the bucket array doubles when `size > 0.75 * capacity`, and every entry is
re-linked. The O(k) cost is amortised over the insertions that triggered it, giving
O(1) amortised insertion.

## 4. Journey validation

Per scan event:

| Step | Operation | Cost |
| --- | --- | --- |
| 1 | hash lookup of the bag | O(1) average / O(k) worst |
| 2 | first-scan initialisation | O(1) |
| 3 | read the current checkpoint | O(1) |
| 4 | `graph.hasEdge(current, next)` | O(d) |
| 5 | classify the event | O(1) |
| 6 | update the record (+ reference-process check O(V)) | O(V) |
| 7 | BFS reachability from the current checkpoint | O(V + E) |

Therefore:

* **one scan event**: O(1) + O(d) + O(V) + O(V + E) = **O(V + E)** time, plus
  O(1) space (the BFS work-sets are bounded by V),
* **a stream of m events**: **O(m · (V + E))** time,
* **space**: O(V + E) for the graph + O(k) for the baggage records + O(depth of
  stored scan history) - we deliberately keep the full scan history per bag, i.e.
  O(total scans) overall, because the UI shows it.

The dominant per-event cost is the single BFS for reachability. It is included
because "can this bag still reach ARRIVAL?" is the one question no local check can
answer.

## 5. Measured performance

Reproduce with:

```bash
./run.sh benchmark          # 100 / 500 / 1000 / 5000 bags, median of 3 runs
./run.sh benchmark 20000    # a single larger size
```

The benchmark builds a deterministic synthetic dataset per size (`Random(42)`),
enqueues every event into the custom queue and then times
`BaggageValidator.processAll()` with `System.nanoTime()`. Every bag follows
`CHECK_IN → SECURITY → LOADING → (TRANSFER) → ARRIVAL`; every 10th bag repeats
`SECURITY` (duplicate) and every 17th bag jumps `SECURITY → ARRIVAL` (invalid, then
recovers), so hash lookups, edge checks and BFS are all exercised.

Measured on this machine (JDK 17.0.20.1 Temurin, Linux x64, laptop-class CPU,
median of 3 runs):

| Bags | Events | Median time | Events / second | µs per event | ms per bag |
| --- | --- | --- | --- | --- | --- |
| 100 | 467 | 3 ms | 155,667 | 6.42 | 0.030 |
| 500 | 2,363 | 11 ms | 214,818 | 4.66 | 0.022 |
| 1,000 | 4,727 | 17 ms | 278,059 | 3.60 | 0.017 |
| 5,000 | 23,596 | 49 ms | 481,551 | 2.08 | 0.010 |

Interpretation (honest, no extrapolation):

* Throughput rises with batch size instead of falling. The main reason is JVM
  JIT compilation and warm-up: a 100-bag run spends a larger share of its time in
  interpreted execution, so short batches report a worse per-event cost.
* Per-event cost stays in the single-digit microsecond range, consistent with
  "hash lookup + a few comparisons + a BFS over 6 nodes and 5 edges".
* Run-to-run variance at these sizes is a few milliseconds (observed ranges: 9-11 ms at
  500 bags, 15-17 ms at 1,000 bags). Each published number is the median of three runs;
  the variation is JIT warm-up and scheduling noise, not different behaviour.
* The measurement covers validation only (no HTTP, no JSON, no file I/O).
* 5,000 bags is 5,000 hash entries, so the baggage store grew and rehashed
  (capacity doubles from 16); that cost is included in the measured time.
* These numbers say nothing about real airport volumes. A production system would
  process events continuously with persistence and messaging; this is a single
  in-memory simulation.

## 6. Complexity questions and answers

**Does the BFS per scan make the algorithm expensive?** It makes it O(V + E) per
scan instead of O(1), which for a 6-node graph is a handful of operations. If the
reachability check were omitted, the system could no longer distinguish
`ARRIVAL UNREACHABLE` from `INCOMPLETE JOURNEY` - the cost buys a distinct,
provable finding.

**Could the reachability result be cached?** Yes: reachability from a node to a
fixed target only changes when the graph changes, so one reverse BFS from `ARRIVAL`
could precompute a boolean per node (O(V + E) once). We keep the per-scan BFS
because it is the assessed traversal and it also produces the *shortest remaining
route* used by the UI; the optimisation is noted in `docs/viva.md` as the natural
next step rather than silently applied.

**Why is transition checking not itself a graph-traversal problem?** Because it is a
single-edge membership test. Claiming BFS is needed for `SECURITY → ARRIVAL` would
be false; BFS is needed only for reachability. The project keeps that line clear.
