package tests;

/**
 * Runs every test suite and exits non-zero if anything failed.
 *
 *   javac -d build $(find backend/src -name '*.java')
 *   javac -cp build -d build-tests $(find backend/tests -name '*.java')
 *   java -cp build:build-tests tests.TestRunner      (run from the project root)
 */
public class TestRunner {

    public static void main(String[] args) {
        System.out.println("=== Baggage Journey Integrity Checker - automated tests ===");
        GraphTest.register();
        QueueTest.register();
        HashTableTest.register();
        ValidatorTest.register();
        DemoScenarioTest.register();
        IntegrationTest.register();

        int exitCode = TestHarness.runAll();
        System.out.println(exitCode == 0 ? "ALL TESTS PASSED" : "TEST FAILURES - see above");
        System.exit(exitCode);
    }
}
