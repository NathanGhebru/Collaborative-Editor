#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir/../frontend"

if [ ! -d node_modules ]; then
  npm ci
fi

npm test -- --run "$@"
