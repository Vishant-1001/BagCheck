package tests;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal test harness - intentionally not JUnit.
 *
 * Reason: the project must run with NO external dependencies and no build tool,
 * so `javac` + `java` on a plain JDK is enough to build and test everything. The
 * harness is ~60 lines and gives clear pass/fail output, which is all a capstone
 * needs.
 */
public final class TestHarness {

    public interface Body {
        void run() throws Exception;
    }

    private static final class Case {
        final String name;
        final Body body;

        Case(String name, Body body) {
            this.name = name;
            this.body = body;
        }
    }

    private static final List<Case> CASES = new ArrayList<Case>();

    private TestHarness() {
    }

    public static void test(String name, Body body) {
        CASES.add(new Case(name, body));
    }

    // ---- assertions ------------------------------------------------------

    public static void assertEquals(Object expected, Object actual, String message) {
        // Numbers are compared numerically so Integer/Long/counters mix freely.
        boolean equal;
        if (expected == null) {
            equal = actual == null;
        } else if (expected instanceof Number && actual instanceof Number) {
            equal = ((Number) expected).doubleValue() == ((Number) actual).doubleValue();
        } else {
            equal = expected.equals(actual);
        }
        if (!equal) {
            throw new AssertionError(message + " (expected <" + expected + "> but was <" + actual + ">)");
        }
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message + " (expected true)");
        }
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message + " (expected false)");
        }
    }

    public static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + " (expected null but was <" + value + ">)");
        }
    }

    public static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message + " (expected a value but was null)");
        }
    }

    public static void assertContains(String haystack, String needle, String message) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(message + " (expected to contain <" + needle + "> but was <" + haystack + ">)");
        }
    }

    // ---- runner ----------------------------------------------------------

    public static int runAll() {
        int passed = 0;
        List<String> failures = new ArrayList<String>();
        System.out.println("Running " + CASES.size() + " tests");
        System.out.println("------------------------------------------------------------");
        for (Case testCase : CASES) {
            try {
                testCase.body.run();
                passed++;
                System.out.println("  PASS  " + testCase.name);
            } catch (Throwable failure) {
                failures.add(testCase.name + " : " + failure.getMessage());
                System.out.println("  FAIL  " + testCase.name);
                System.out.println("        " + failure);
            }
        }
        System.out.println("------------------------------------------------------------");
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.out.println("Failures:");
            for (String failure : failures) {
                System.out.println("  - " + failure);
            }
        }
        return failures.isEmpty() ? 0 : 1;
    }
}
