package benchmark;

import model.JourneyResult;
import service.BaggageValidator;
import service.ConfigLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Performance demonstration: how does the engine scale with the number of bags?
 *
 * This performs a REAL measurement. It generates deterministic synthetic scan
 * events, pushes them through the custom FIFO queue and runs the real validator
 * (hash lookups + graph edge checks + BFS reachability) on them. The numbers are
 * printed as measured on the machine that ran it, and nothing is estimated.
 *
 * Run: java -cp build benchmark.Benchmark [maxBags]
 */
public class Benchmark {

    private static final int[] DEFAULT_SIZES = { 100, 500, 1000, 5000 };
    private static final int SCANS_PER_BAG = 5;
    private static final int REPEATS = 3;

    public static void main(String[] args) {
        int[] sizes = DEFAULT_SIZES;
        if (args.length > 0) {
            sizes = new int[] { Integer.parseInt(args[0]) };
        }

        ConfigLoader config = new ConfigLoader("data");
        System.out.println("Baggage Journey Integrity Checker - performance demonstration");
        System.out.println("Graph profile: standard, scans per bag: " + SCANS_PER_BAG
                + ", repeats per size: " + REPEATS + " (median reported)");
        System.out.println();
        System.out.printf("%8s %10s %12s %12s %14s%n",
                "bags", "events", "median ms", "events/sec", "micros/event");
        System.out.println("---------------------------------------------------------------------");

        for (int size : sizes) {
            long[] times = new long[REPEATS];
            int events = 0;
            for (int repeat = 0; repeat < REPEATS; repeat++) {
                long[] measurement = runOnce(config, size);
                events = (int) measurement[0];
                times[repeat] = measurement[1];
            }
            long median = median(times);
            double eventsPerSecond = median == 0 ? 0 : (events * 1000.0 / median);
            double microsPerEvent = events == 0 ? 0 : (median * 1000.0 / events);
            System.out.printf("%8d %10d %12d %12.0f %14.3f%n",
                    size, events, median, eventsPerSecond, microsPerEvent);
        }

        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - measured with System.nanoTime() around BaggageValidator.processAll()");
        System.out.println("  - includes hash-table lookups, graph edge checks and BFS reachability per scan");
        System.out.println("  - synthetic data: every bag follows CHECK_IN -> SECURITY -> LOADING -> (TRANSFER) -> ARRIVAL,");
        System.out.println("    every 10th bag repeats SECURITY (duplicate) and every 17th bag jumps SECURITY -> ARRIVAL (invalid)");
    }

    /**
     * Builds a synthetic dataset of 'bagCount' bags, enqueues every event and
     * processes the queue. Returns {eventCount, elapsedMillis}.
     */
    private static long[] runOnce(ConfigLoader config, int bagCount) {
        BaggageValidator validator = new BaggageValidator(config.buildGraph("standard"),
                config.referenceProcess(), config.finalCheckpoint());

        Random random = new Random(42);   // fixed seed => the same dataset every run
        int events = 0;
        for (int i = 0; i < bagCount; i++) {
            String bagId = "BAG" + i;
            validator.submitScan(bagId, "CHECK_IN");
            validator.submitScan(bagId, "SECURITY");
            events += 2;
            if (i % 10 == 0) {
                validator.submitScan(bagId, "SECURITY");   // duplicate scan
                events++;
            }
            if (i % 17 == 0) {
                validator.submitScan(bagId, "ARRIVAL");    // invalid transition (SECURITY -> ARRIVAL)
                validator.submitScan(bagId, "LOADING");    // recovery
                events += 2;
            }
            validator.submitScan(bagId, "LOADING");
            events++;
            if (random.nextBoolean()) {
                validator.submitScan(bagId, "TRANSFER");   // longer legal route
                events++;
            }
            validator.submitScan(bagId, "ARRIVAL");
            events++;
        }

        List<JourneyResult> results = new ArrayList<JourneyResult>();
        long start = System.nanoTime();
        results = validator.processAll();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        if (results.size() != events) {
            throw new IllegalStateException("expected " + events + " results but got " + results.size());
        }
        return new long[] { events, elapsedMillis };
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
