#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

"$script_dir/start-infra.sh"
echo "Infrastructure is healthy. In separate terminals run:"
echo "  ./scripts/start-backend.sh"
echo "  ./scripts/start-frontend.sh"
echo "Then validate the running stack with ./scripts/smoke-test.sh"
