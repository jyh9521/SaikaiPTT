#!/usr/bin/env bash
#
# Type-checks the :core module without running Gradle.
#
# Why this exists: a full Gradle build needs the Android SDK and network access,
# which the environment Claude works in does not have (see .claude/CLAUDE.md
# 34.1). Everything that matters most in this project -- the packet codec, the
# session state machine, presence rules -- lives in :core and is plain Kotlin,
# so it can be checked with the compiler already sitting in the Gradle cache.
#
# It runs the compiler frontend only in practice: code generation needs a JDK
# and dependencies that are not present, and crashes after the frontend has
# finished. That is fine. Type errors, unresolved references, bad overrides and
# wrong arities are all frontend diagnostics, and they are what this catches.
#
# This is a fast pre-check, not a substitute for `gradlew :core:test`.
#
# Usage:
#   tools/typecheck-core.sh              # main sources
#   tools/typecheck-core.sh --with-tests # main + test sources
set -uo pipefail

KOTLIN_VERSION="2.2.10"

# The Gradle cache is reached differently depending on where this runs.
for candidate in \
    "$HOME/mnt/files-2.1" \
    "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"; do
  if [ -d "$candidate" ]; then CACHE="$candidate"; break; fi
done
if [ -z "${CACHE:-}" ]; then
  echo "Gradle module cache not found. Run a Gradle build once first." >&2
  exit 2
fi

jar() { find "$CACHE/$1" -name "$2" 2>/dev/null | grep -v sources | sort -V | tail -1; }

COMPILER=$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar")
STDLIB=$(jar org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$KOTLIN_VERSION.jar")
REFLECT=$(jar org.jetbrains.kotlin/kotlin-reflect "kotlin-reflect-$KOTLIN_VERSION.jar")
DAEMON=$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$KOTLIN_VERSION.jar")
COROUTINES=$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm "*.jar")

if [ -z "$COMPILER" ] || [ -z "$STDLIB" ]; then
  echo "Kotlin $KOTLIN_VERSION not in the cache at $CACHE. Run a Gradle build once first." >&2
  exit 2
fi

SOURCES=("core/src/main/kotlin")
TARGET_CP="$STDLIB:$COROUTINES"

if [ "${1:-}" = "--with-tests" ]; then
  JUNIT=$(jar junit/junit "junit-4.13.2.jar")
  HAMCREST=$(jar org.hamcrest/hamcrest-core "*.jar")
  if [ -z "$JUNIT" ]; then
    echo "junit not in the cache; run 'gradlew :core:test' once first." >&2
    exit 2
  fi
  SOURCES+=("core/src/test/kotlin")
  COROUTINES_TEST=$(jar org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm "*.jar")
  TARGET_CP="$TARGET_CP:$JUNIT${HAMCREST:+:$HAMCREST}${COROUTINES_TEST:+:$COROUTINES_TEST}"
fi

OUT_DIR=$(mktemp -d)
trap 'rm -rf "$OUT_DIR"' EXIT

OUTPUT=$(java -Xmx1g \
  -cp "$COMPILER:$STDLIB:$REFLECT:$DAEMON:$COROUTINES" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$TARGET_CP" -jvm-target 11 \
  -d "$OUT_DIR" "${SOURCES[@]}" 2>&1)

# CLI diagnostics are "path:line:col: error: message". Gradle rewrites these to
# "e:"; the raw compiler does not.
ERRORS=$(echo "$OUTPUT" | grep -E ': error: ' || true)
WARNINGS=$(echo "$OUTPUT" | grep -E ': warning: ' || true)

if [ -n "$ERRORS" ]; then
  echo "Type check FAILED:"
  echo "$ERRORS"
  exit 1
fi

echo "Type check passed: ${SOURCES[*]}"
if [ -n "$WARNINGS" ]; then
  echo
  echo "Warnings:"
  echo "$WARNINGS"
fi
