#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir/../backend"

mvn test -Dtest="com.collaborativeeditor.persistence.*Test" "$@"

