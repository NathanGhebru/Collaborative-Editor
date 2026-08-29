#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

"$script_dir/test-backend.sh" "$@"
"$script_dir/test-frontend.sh" "$@"
