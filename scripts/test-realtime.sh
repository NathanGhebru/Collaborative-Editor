#!/usr/bin/env bash
set -euo pipefail

echo "========================================="
echo "  Running Realtime Collaboration Suite   "
echo "========================================="

cd "$(dirname "$0")/../backend"

mvn test -Dtest="com.collaborativeeditor.realtime.RealtimeTicketControllerTest"
mvn test -Dtest="com.collaborativeeditor.realtime.RealtimeWebSocketHandshakeTest"
mvn test -Dtest="com.collaborativeeditor.realtime.RealtimeSessionLifecycleTest"
mvn test -Dtest="com.collaborativeeditor.realtime.RealtimeOperationBroadcastTest"
mvn test -Dtest="com.collaborativeeditor.realtime.RealtimeErrorHandlingTest"
mvn test -Dtest="com.collaborativeeditor.realtime.RealtimeIntegrationTest"

echo "==> All Realtime collaboration test suites passed successfully!"
