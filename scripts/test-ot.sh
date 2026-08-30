#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

echo "==> Running Java OT suite..."
cd "$script_dir/../backend"
mvn test -Dtest="com.collaborativeeditor.ot.*Test" "$@"

echo "==> Running TypeScript OT suite..."
cd "$script_dir/../frontend"
if [ ! -d node_modules ]; then
  npm ci
fi
npm test -- --run src/ot/

