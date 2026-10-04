#!/usr/bin/env bash
# Baggage Journey Integrity Checker - one script for build, test, serve and benchmark.
# No Maven, no Gradle, no dependencies: javac + java are enough.
set -euo pipefail
cd "$(dirname "$0")"

# Use a project-local JDK when one is present (e.g. a portable JDK in .tools/),
# otherwise fall back to javac/java from the PATH.
JAVAC="javac"
JAVA="java"
for candidate in .tools/jdk*/bin; do
  if [ -x "$candidate/javac" ]; then
    JAVAC="$candidate/javac"
    JAVA="$candidate/java"
  fi
done

build() {
  mkdir -p build build-tests
  find backend/src -name '*.java' > build/sources.txt
  "$JAVAC" -d build @build/sources.txt
  find backend/tests -name '*.java' > build/test-sources.txt
  "$JAVAC" -cp build -d build-tests @build/test-sources.txt
  echo "Build OK  (engine -> build/, tests -> build-tests/)"
}

case "${1:-serve}" in
  build)
    build
    ;;
  test)
    build
    echo
    "$JAVA" -cp "build:build-tests" tests.TestRunner
    ;;
  serve)
    build
    echo
    "$JAVA" -cp build api.ApiServer "${2:-8080}" data frontend
    ;;
  benchmark)
    build
    echo
    if [ -n "${2:-}" ]; then
      "$JAVA" -cp build benchmark.Benchmark "$2"
    else
      "$JAVA" -cp build benchmark.Benchmark
    fi
    ;;
  *)
    echo "Usage: ./run.sh [build|test|serve|benchmark] [port|maxBags]"
    exit 1
    ;;
esac
