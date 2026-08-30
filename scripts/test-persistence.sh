#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir/../backend"

mvn test -Dtest="com.collaborativeeditor.persistence.*Test" "$@"
# Ensure docker-java API version compatibility for Docker Desktop on Windows
export DOCKER_HOST="${DOCKER_HOST:-npipe:////./pipe/docker_engine}"

mode="${1:-all}"

case "$mode" in
  --fast|--unit)
    shift || true
    echo "==> Running fast H2 persistence unit and integration tests..."
    mvn test -Dtest="com.collaborativeeditor.persistence.*Test" "-Dapi.version=1.44" "$@"
    ;;
  --postgres|--acceptance)
    shift || true
    echo "==> Running PostgreSQL Testcontainers acceptance IT suite..."
    mvn test -Dtest="com.collaborativeeditor.persistence.*IT" "-Dapi.version=1.44" "$@"
    ;;
  *)
    echo "==> Running full persistence test suite (H2 unit + PostgreSQL Testcontainers)..."
    mvn test -Dtest="com.collaborativeeditor.persistence.*Test,com.collaborativeeditor.persistence.*IT" "-Dapi.version=1.44" "$@"
    ;;
esac

