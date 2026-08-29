#!/usr/bin/env sh
set -eu

for file in AGENTS.md README.md docs/PRODUCT_SPEC.md docs/ARCHITECTURE.md docs/API.md docs/REALTIME_PROTOCOL.md docs/DATABASE.md docs/TESTING.md docs/BENCHMARKS.md docs/tasks/README.md; do
  test -f "$file"
done

echo "Required project documentation is present."
