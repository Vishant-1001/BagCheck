# Data structures used in this project

Three assessed structures, implemented from scratch, plus graph traversal where it
does genuine work. Nothing in `dsa/` extends or wraps a `java.util` collection.

## 1. Directed graph - `backend/src/dsa/Graph.java`

**Representation.** Adjacency list: a `CustomHashTable<List<String>>` maps each
checkpoint to the ordered list of checkpoints reachable by one legal transition.
Insertion order is tracked separately so iteration and display are deterministic.

**Invariants.**

* every endpoint of an edge is a node (adding an edge auto-creates missing nodes),
* no duplicate nodes, no duplicate edges, no implicit self-loops,
* direction matters: `hasEdge(a, b)` does not imply `hasEdge(b, a)`.

**Operations used by the project.**

| Method | Used for | Complexity |
| --- | --- | --- |
| `addCheckpoint(id)` | building the vocabulary from `data/checkpoints.json` | O(1) avg |
| `addEdge(from,to)` | building a profile from `data/transitions.json` | O(out-degree) |
| `hasCheckpoint(id)` | distinguishing *unknown checkpoint* from *invalid transition* | O(1) avg |
| `hasEdge(from,to)` | **the** transition check for every scan | O(out-degree) |
| `neighbors(id)` | BFS/DFS expansion, degree reporting in the UI | O(out-degree) |
| `bfsPath(from,to)` | remaining route to `ARRIVAL`, reachability, unreachability proof | O(V + E) |
| `bfsOrder(from)` | traversal demonstration (tests) | O(V + E) |
| `dfsOrder(from)`, `dfsReachable(from,to)` | second traversal strategy, cross-check | O(V + E) |
| `nodesThatCannotReach(target)` | marking dead-end regions in the dashboard | O(V · (V + E)) |

**Why adjacency list.** The graph is sparse (V = 6, E = 5 in the standard profile)
and configurable at runtime. An adjacency matrix would cost O(V²) memory and would
have to be rebuilt whenever a checkpoint is added; the list costs O(V + E) and
adding a checkpoint is a single bucket insert.

### BFS, in detail

`bfsPath(start, target)` is used for two jobs at once:

1. **Reachability proof.** If BFS from the bag's current checkpoint never reaches
   `ARRIVAL`, then no sequence of legal transitions leads there - this is what
   produces `ARRIVAL UNREACHABLE`.
2. **Shortest remaining route.** Every transition has the same cost (one hop), so
   the first time BFS reaches `ARRIVAL` it is via a path with the fewest
   checkpoints. The dashboard draws that path as the remaining journey.

Implementation notes worth defending:

* the FIFO **`CustomQueue`** drives the frontier - that is what makes it
  breadth-first rather than depth-first,
* the **`CustomHashTable` is used as the visited set** (`Boolean` values), so the
  traversal does not depend on `java.util.HashSet`,
* a `parent` map (`CustomHashTable<String>`) is filled during the search so the
  path can be reconstructed without re-searching,
* `start.equals(target)` returns a one-element path, which is why a bag already at
  `ARRIVAL` reports "already there" rather than an empty path.

### DFS, in detail

`dfsOrder` and `dfsReachable` are recursive with an explicit visited set built from
the same custom hash table. DFS answers the reachability question too, but it does
**not** return a shortest path, so the dashboard uses BFS for display. DFS exists
because the course requires both to be demonstrated, and because two independent
traversal results agreeing on reachability is a useful cross-check (see
`GraphTest`: *"DFS traversal agrees with BFS on reachability"*). Recursion depth is
bounded by V, which is fine for a checkpoint graph and is stated as a limitation for
arbitrarily large graphs.

## 2. Hash table - `backend/src/dsa/CustomHashTable.java`

**Design.** String keys, values of any type (the class is generic). Bucket array of
`Entry` objects, each bucket holding a short singly-linked chain - i.e.
**separate chaining** as the collision-resolution technique.

**Hash function.** Polynomial rolling hash, `h = 31 * h + c` for each character,
then `(h & 0x7fffffff) % buckets.length`. This is the textbook string hash. It is
implemented in a named method (`indexFor`) so it can be shown directly in the viva.

**Collision resolution.** Chaining was chosen over open addressing because it is
the simplest technique that never fails to insert and never degenerates below
"walk a short list": open addressing would need tombstones (because of `remove`) and
careful probe sequences, which is more code to get wrong.

**Rehashing.** When `size > 0.75 * capacity` the bucket array doubles and every
entry is re-linked. Rehashing preserves all entries (verified by a test that inserts
500 keys and checks all 500 afterwards).

**Operations required by the brief and where they are used.**

| Operation | Signature | Used for |
| --- | --- | --- |
| `put` | `V put(String key, V value)` | insert a new baggage record; returns the previous value or `null` |
| `get` | `V get(String key)` | **step 1 of every validation**: find the bag for this scan |
| `update` | `void update(String key, V value)` | explicit alias used when the validator updates a record in place |
| `contains` | `boolean contains(String key)` | "is this the first scan of this bag?" and graph node membership |
| `remove` | `V remove(String key)` | `reset()` |
| introspection | `size`, `capacity`, `loadFactor`, `collisions`, `longestChain`, `rehashes`, `keys` | shown live in the dashboard's statistics panel and asserted in tests |

**Three uses inside the system** (this is why it is not a token data structure):

1. `BAG_ID → Baggage` - the assessed baggage store.
2. `Graph` adjacency list - `checkpoint → list of successors`.
3. Visited set and parent map inside BFS/DFS.

## 3. Queue - `backend/src/dsa/CustomQueue.java`

**Design.** Singly linked list with `head` and `tail` pointers. Chosen because it is
the simplest correct FIFO: every operation touches only the two ends, so there is no
array wraparound arithmetic or capacity juggling to get wrong.

| Operation | Complexity | Used for |
| --- | --- | --- |
| `enqueue(value)` | O(1) | a new scan event arrives (from the UI, a demo or the sample stream) |
| `dequeue()` | O(1) | validating the next event, in arrival order |
| `peek()` | O(1) | showing which event is next (the queue panel marks it "next") |
| `isEmpty()` | O(1) | loop termination in `processAll()` and inside BFS |
| `size()` | O(1) | the queue badge in the UI |
| `toList()` | O(n) | rendering the pending queue (display only) |

**Why FIFO matters here.** Events for different bags are interleaved, e.g.

```
BAG001 → CHECK_IN
BAG002 → CHECK_IN
BAG001 → SECURITY
BAG003 → CHECK_IN
```

If event 3 were validated before event 1, `BAG001`'s history would be wrong: a
LIFO structure would report a duplicate or an invalid transition that never
happened. The queue is what preserves *causality of observation*. It also decouples
intake from validation, which mirrors how a scanner gateway buffers events.

## 4. How the three structures collaborate

One scan event, in code order (`BaggageValidator.validate`):

```java
Baggage bag = bags.get(bagId);                     // CustomHashTable: O(1) average
if (checkpoint.equals(current)) { ... DUPLICATE }  // no traversal needed
if (!graph.hasEdge(current, checkpoint)) { ... }   // Graph.hasEdge: O(out-degree)
bag.setCurrentCheckpoint(checkpoint);              // update the record in place
boolean reachable = graph.reachable(current, F);   // BFS over the adjacency list,
                                                   // CustomQueue + CustomHashTable
```

and the event itself arrived through `CustomQueue.dequeue()` inside
`processNext()`.

## 5. What is deliberately *not* literal `java.util`

* baggage storage - custom hash table,
* the graph's adjacency list - custom hash table + `java.util.List` for the ordered
  successors,
* scan-event buffering - custom queue,
* BFS frontier - custom queue,
* BFS/DFS visited sets and the BFS parent map - custom hash table.

`java.util.List`/`Map`/`Random` are used for configuration loading, payload
building, display and synthetic data generation, none of which is assessed.
