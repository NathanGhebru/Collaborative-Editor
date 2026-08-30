#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir/../backend"

# Ensure Windows Docker Desktop pipe and API version fallback are present if running under Docker on Windows
if [ "${OS:-}" = "Windows_NT" ] || uname -s | grep -qi "mingw\|cygwin\|msys"; then
    export DOCKER_HOST="${DOCKER_HOST:-npipe:////./pipe/docker_engine}"
    MAVEN_EXTRA_ARGS="-Dapi.version=1.44"
else
    MAVEN_EXTRA_ARGS=""
fi

if [ "${1:-}" = "--fast" ]; then
    echo "Running unit and in-memory sequencing tests..."
    mvn test -Dtest="com.collaborativeeditor.service.sequencing.*Test,com.collaborativeeditor.sequencing.*Test"
elif [ "${1:-}" = "--postgres" ]; then
    echo "Running PostgreSQL 17 Testcontainers sequencing acceptance suite..."
    mvn test -Dtest="com.collaborativeeditor.sequencing.DurableSequencingAcceptanceIT" ${MAVEN_EXTRA_ARGS}
else
    echo "Running all PERS-002 sequencing tests (fast + PostgreSQL 17 acceptance)..."
    mvn test -Dtest="com.collaborativeeditor.service.sequencing.*Test,com.collaborativeeditor.sequencing.*Test,com.collaborativeeditor.sequencing.DurableSequencingAcceptanceIT" ${MAVEN_EXTRA_ARGS}
fi

