#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)

if [ -f "$repo_dir/.env" ]; then
  set -a
  . "$repo_dir/.env"
  set +a
fi

cd "$repo_dir/frontend"
if [ ! -d node_modules ]; then
  npm ci
fi

exec npm run dev -- --host 0.0.0.0
