#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
exec "$SCRIPT_DIR/record-riscv-xlen-gate-benchmark.sh" "${1:-100000}" "${2:-9}" u64
