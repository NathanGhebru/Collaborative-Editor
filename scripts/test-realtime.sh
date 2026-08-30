#!/usr/bin/env bash
set -e

echo "========================================="
echo "  Running Realtime Collaboration Suite   "
echo "========================================="

cd "$(dirname "$0")/../backend"

mvn test -Dtest="com.collaborativeeditor.realtime.*Test"

echo "==> All Realtime collaboration test suites passed successfully!"
