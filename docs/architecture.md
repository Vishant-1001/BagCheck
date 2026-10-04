# Architecture

## 1. Overview

One Java process contains the whole system: the data structures, the validation
engine and a small HTTP layer that serves the dashboard. There are no
frameworks, no build tool and no dependency manager - `javac` and `java` are the
entire toolchain.

```
                    ┌───────────────────────────────────────────┐
   browser  ◄──────►│ api/ApiServer  (com.sun.net.httpserver)   │
   (frontend/)      │  /api/state  /api/scan  /api/process ...  │
                    └───────────────────┬───────────────────────┘
                                        │  JSON maps
                    ┌───────────────────▼───────────────────────┐
                    │ service/Engine                            │
                    │  owns the current graph profile, builds   │
                    │  payloads, orchestrates demos             │
                    └───────────────────┬───────────────────────┘
                                        │
                    ┌───────────────────▼───────────────────────┐
                    │ service/BaggageValidator   (the algorithm)│
                    │  CustomQueue     CustomHashTable   Graph  │
                    └───────────────────┬───────────────────────┘
                                        │  buildGraph / referenceProcess
                    ┌───────────────────▼───────────────────────┐
                    │ service/ConfigLoader  ──► data/*.json     │
                    └───────────────────────────────────────────┘
```

## 2. Layers and responsibilities

| Layer | Files | Responsibility | Must NOT do |
| --- | --- | --- | --- |
| `dsa` | `Graph`, `CustomQueue`, `CustomHashTable` | Generic data structures, no domain knowledge | Know anything about baggage, HTTP or JSON |
| `model` | `Baggage`, `ScanEvent`, `JourneyResult` | Immutable/passive data carriers | Contain validation logic |
| `service` | `BaggageValidator` | The validation algorithm (steps 1-7) | Know about HTTP, files or formatting |
| `service` | `ConfigLoader` | Read `data/*.json`, build the graph | Mutate state or validate events |
| `service` | `Engine` | Profile switching, orchestration, JSON payloads | Implement algorithms |
| `util` | `Json` | Minimal dependency-free JSON read/write | Be part of the assessed DSA work |
| `api` | `ApiServer` | Routing, static files, JSON framing, error mapping | Own domain state |
| `frontend` | `index.html`, `styles.css`, `app.js` | Presentation and interaction only | Contain validation logic or a second graph |

## 3. Request flows

**Bootstrap.** `GET /` returns `frontend/index.html`; the page calls
`GET /api/state` once. `Engine.statePayload()` returns graph (nodes, coordinates,
edges, profiles), queue contents, statistics, bag summaries, the demo list and the
reference process. The dashboard renders itself from that single payload.

**Validate one event.** The user clicks *Process next*.
`POST /api/process {"all":false}` → `Engine.process(false)` →
`BaggageValidator.processNext()` → dequeue → `validate(...)` (hash lookup, edge
check, BFS) → a `JourneyResult`. The API returns `{"ok":true,"results":[...],"state":{...}}`.
The frontend animates the transition and re-renders.

**Validate a batch.** `POST /api/process {"all":true}` loops `processNext()` until
the queue is empty and records the elapsed time (surfaced as "last batch" in the
statistics panel). This is the same code path used by the benchmark.

**Run a demo.** `POST /api/demo {"id":"demo5"}` → `Engine.startDemo` looks the demo
up in `data/demos.json`, switches to the profile the demo declares, creates a fresh
validator and enqueues the demo's scans. The frontend then drives `process` one
event at a time so each step is visible.

**Switch profile.** `POST /api/profile {"profile":"deadend"}` → `Engine.loadProfile`
builds a new `Graph` and a **new validator**. Journey state is intentionally
discarded: those journeys were validated against different rules and are not
comparable. An unknown profile throws `IllegalArgumentException`, which the API
maps to HTTP 400 without touching the existing state.

## 4. Key design decisions

| Decision | Rationale | Alternative rejected |
| --- | --- | --- |
| Rules live in `data/transitions.json`, not in code | The graph must be configurable and inspectable; a professor can change one JSON file and the validation behaviour changes | Hard-coded `if/else` chains (explicitly forbidden by the brief) |
| Custom structures, not `java.util` | The DSA is the deliverable and must be visible in the source | `HashMap`/`ArrayDeque` wrappers |
| JDK `HttpServer` inside the same process | The engine is Java; one process, one command, one origin, no CORS, no sidecar | Python/Node backend, Spring Boot, multiple services |
| Single-threaded executor | The engine is stateful and in-memory; handling requests sequentially removes all locking from the DSA code and keeps demo output deterministic | Thread pool (would require synchronisation inside the structures) |
| Graph stored in the custom hash table | Shows the structure doing real work and avoids `java.util.HashMap` inside the assessed graph | `HashMap<String, List<String>>` |
| Hand-rolled SVG graph instead of a JS graph library | Zero dependencies, offline operation, and the visual is guaranteed to be the engine's graph | Cytoscape.js (acceptable per the brief but adds a dependency and needs downloading) |
| Hand-rolled JSON parser (~200 lines) | Data files are JSON but the project must have no dependencies | Jackson/Gson jars |
| Zero-dependency test harness | `javac` + `java` must be enough to build and test | JUnit jar |

## 5. One graph, one truth

`/api/graph` (part of `/api/state`) is generated from the same `Graph` instance the
validator uses:

* nodes come from `ConfigLoader.checkpointDefinitions()` plus
  `Graph.outDegree` / `Graph.nodesThatCannotReach(final)`,
* edges come from the profile in `data/transitions.json`,
* `onReferenceProcess` is computed from `BaggageValidator.getReferenceProcess()`.

The dashboard never invents nodes or edges. Coordinates are also configuration
(`x`, `y` in `data/checkpoints.json`), so the layout is deterministic and belongs
to the data, not the JavaScript.

## 6. State ownership

| State | Owner | Reset by |
| --- | --- | --- |
| Baggage records (`CustomHashTable`) | `BaggageValidator` | `/api/reset`, `/api/profile`, starting a demo |
| Pending scan events (`CustomQueue`) | `BaggageValidator` | same |
| Counters (valid/duplicate/invalid/unknown) | `BaggageValidator` | same |
| Current graph + profile name | `Engine` | `/api/profile`, starting a demo |
| Selected bag, animation state | browser | page reload |

`Engine` is the only class that mutates the validator's profile, and all public
methods are `synchronized`, so the API layer cannot race with itself.

## 7. Error handling

| Situation | Behaviour |
| --- | --- |
| Unknown profile | HTTP 400, message returned, existing state untouched |
| Unknown demo id | HTTP 400 (thrown as `IllegalArgumentException`) |
| Missing `bagId`/`checkpoint` | HTTP 400, nothing enqueued |
| Unknown checkpoint in a scan | *Not* an error: classified as `UNKNOWN CHECKPOINT` and recorded on the bag |
| Unexpected server failure | HTTP 500 with the exception message; the stack trace goes to the server console |
| Path traversal (`..` in a static path) | HTTP 403 |

## 8. Extension points

* **New checkpoint** - add an entry (with `label`, `description`, `x`, `y`) to
  `data/checkpoints.json`.
* **New legal transition** - add an edge to a profile in `data/transitions.json`.
* **New graph profile** - add another entry under `profiles`; it appears in the
  dashboard dropdown automatically.
* **New demo** - add an entry to `data/demos.json` (with `profile`, `bagId`,
  `scans`, `expected`, `explanation`); it becomes a button and is covered by
  `DemoScenarioTest` automatically.
* **New anomaly type** - classify in `BaggageValidator.validate`, add the label to
  the UI findings renderer.

None of these require touching the `dsa/` package, which is the point.
