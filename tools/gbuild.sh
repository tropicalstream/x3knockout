#!/bin/bash
# Serialised gradle build. Several agents share this checkout, and two gradle invocations on one
# project fight over the same lock and fail in ways that look like compile errors. mkdir is the
# atomic primitive macOS actually has (no flock), so it is the lock; a stale one older than 15
# minutes is broken, because an agent that died holding it must not block the build forever.
LOCK=/tmp/x3knockout_gradle.lock
cd "$(dirname "$0")/.."
for i in $(seq 1 600); do
  if mkdir "$LOCK" 2>/dev/null; then trap 'rmdir "$LOCK" 2>/dev/null' EXIT; break; fi
  if [ -d "$LOCK" ] && [ -n "$(find "$LOCK" -maxdepth 0 -mmin +15 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null; fi
  sleep 1
done
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew "${@:-assembleDebug}" -q 2>&1 | grep -vE "^w: " | tail -40
exit ${PIPESTATUS[0]}
