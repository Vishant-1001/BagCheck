# References

## 1. How this list is used

Every real-world claim in this project is traceable to a source below. Statistics are
**quoted from the source and attributed**, never invented or extrapolated. Where we
simplify (our checkpoint graph, our synthetic data), the document says so.

## 2. Primary sources: real-world baggage tracking

| # | Source | What it supports |
| --- | --- | --- |
| 1 | **IATA - Baggage Tracking (Resolution 753)**<br><https://www.iata.org/en/programs/ops-infra/baggage/baggage-tracking/> | Resolution 753 requires member airlines to track baggage at a minimum of four core tracking points: acceptance, loading, transfer and arrival/delivery. |
| 2 | **IATA press release, 9 May 2024 - "Industry Makes Progress to Reduce Baggage Mishandling, New Survey Reveals"**<br><https://www.iata.org/en/pressroom/2024-releases/2024-05-09-01/> | Adoption and technology facts: survey of 155 airlines and 94 airports; 44% of airlines fully implemented Res. 753, 41% in progress; 75% of airports capable; barcode scanning at 73% of airports, RFID at 27%; mishandling fell nearly 60% between 2007 and 2022; global mishandled-bag rate of 7.6 per 1,000 passengers in 2022 (SITA data), most returned within 48 hours; Res. 753 adopted June 2018; the requirement to exchange tracking messages with interline partners. |
| 3 | **IATA - Resolution 753 Implementation Guide (2023, issue 4.0, PDF)**<br><https://www.iata.org/contentassets/d22bcfe86aa54f22a2919b0e4224f68f/reso753-implementation-guide---2023_issue-4.0.pdf> | The operational meaning of the individual tracking events (e.g. that an arrival/delivery event ends the baggage journey), used to justify a *terminal* `ARRIVAL` state in our model. |
| 4 | **SITA - Baggage IT Insights**<br><https://www.sita.aero/resources/surveys-reports/sita-baggage-it-insights/> | The industry mishandled-bag statistics quoted by IATA in source 2; the broader motivation that baggage tracking quality is a live industry concern. |

### How they map onto the project

| Real-world element | In this project |
| --- | --- |
| Acceptance (check-in) tracking point | `CHECK_IN` node |
| Loading tracking point | `LOADING` node |
| Transfer tracking point | `TRANSFER` node |
| Arrival / delivery tracking point (ends the journey) | `ARRIVAL` terminal node |
| (our simplification) security screening step | `SECURITY` node |
| (our simplification) a dead-end holding area used to demonstrate unreachability | `QUARANTINE` node |
| Tracking messages exchanged between systems | scan events pushed onto the queue |
| Use of tracking data to detect handling problems | sequence validation against the graph |

**What we do not claim from these sources:** we do not claim our graph is IATA's
process, we do not claim to implement IATA message standards (BSM/XML), we do not
claim our synthetic dataset reflects real airport data distributions, and we do not
claim that our system prevents baggage loss.

## 3. Data-structure references

| # | Source | Used for |
| --- | --- | --- |
| 5 | Cormen, Leiserson, Rivest, Stein. *Introduction to Algorithms*, 4th ed. MIT Press, 2022. | Adjacency-list representation of graphs (§20.1); breadth-first search, shortest-path property of BFS trees (§20.2); depth-first search (§20.3); hash tables, chaining and load factor (§11) |
| 6 | Sedgewick, R., Wayne, K. *Algorithms*, 4th ed. Addison-Wesley, 2011. | Directed-graph API design and adjacency-list implementation; FIFO queue as a linked list (Chapter 1.3); symbol tables (Chapter 3.1) |

## 4. Documentation hygiene

* Statistics appear in the README and here with an explicit source and year. If a
  number has no source, it does not appear.
* The performance table in `README.md` and `docs/complexity.md` is produced by
  `backend/src/benchmark/Benchmark.java` and is reproducible with
  `./run.sh benchmark`; the machine and JDK used are stated next to the numbers.
* All figures in `data/*.json` are hand-written synthetic examples. They are labelled
  as such in the file (`"note": "…Simulated…"`) and in the UI disclaimer banner.

## 5. Academic honesty statement

This project is a **simulation** built for a Data Structures and Algorithms course. It
is not connected to any airline, airport or baggage-handling system, and it uses no
real baggage data.

We therefore do not describe the system as AI-powered, predictive, intelligent or
autonomous; we do not claim real-time airline integration; we do not claim real-world
accuracy from synthetic data; we do not claim global uniqueness; and we do not claim
that graph traversal predicts physical baggage movement.

The claim the project does support is the one in the title:

> A DSA-based system for validating baggage scan sequences against a predefined
> checkpoint-transition graph and identifying journey inconsistencies.

## 6. How to cite this project

```
Baggage Journey Integrity Checker: a DSA-based system for validating baggage scan
sequences against a predefined checkpoint-transition graph. Data Structures and
Algorithms capstone project, 2026. Source code and documentation in this repository.
```
