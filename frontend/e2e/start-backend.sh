#!/usr/bin/env bash
# Starts the backend for the E2E suite (KAN-53), as Playwright's first webServer (playwright.config.ts
# passes the environment, see e2e/env.ts):
#   1. builds the normal jar and runs the test-sources seeder (auth.E2eSeeder) once: it wipes the
#      E2E database and Redis database, then creates the E2E club and its admin;
#   2. runs the server under test from that jar (never the test classpath), dev profile, on the
#      E2E port. Playwright waits for it to answer, and kills this whole process group at the end.
# Both write their output to $E2E_LOG_DIR; on a failure it is printed here.
set -euo pipefail

cd "$(dirname "$0")/../../backend"
mkdir -p "$E2E_LOG_DIR"
seed_log="$E2E_LOG_DIR/seed.log"
backend_log="$E2E_LOG_DIR/backend.log"

echo "[e2e] building the backend jar and seeding the E2E database (log: $seed_log)"
if ! ./mvnw --batch-mode --no-transfer-progress -DskipTests \
  package spring-boot:test-run -Dspring-boot.run.profiles=e2e-seed >"$seed_log" 2>&1; then
  echo "[e2e] the build or the seeding failed:" >&2
  cat "$seed_log" >&2
  exit 1
fi

jar=$(ls target/squadpulse-backend-*.jar | grep -v -- '-plain\.jar$' | head -n 1)
echo "[e2e] starting $jar on port $SERVER_PORT (log: $backend_log)"
java -jar "$jar" --spring.profiles.active=dev >"$backend_log" 2>&1 &
backend_pid=$!

status=0
wait "$backend_pid" || status=$?
echo "[e2e] the backend exited (status $status) — its log:" >&2
cat "$backend_log" >&2
exit 1
