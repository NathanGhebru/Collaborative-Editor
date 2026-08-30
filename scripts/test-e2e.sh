#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root/frontend"

if [[ ! -d node_modules ]]; then
  npm ci
fi

npm run test:e2e -- "$@"
