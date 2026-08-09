#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"

export RISCV_XLEN_SOURCE_ROOT="$ROOT/tools/fixtures/riscv-xlen-u64"
export RISCV_XLEN_LINKER_SCRIPT="$ROOT/tools/fixtures/isa-gate2-c/rv32im.ld"
export RISCV_XLEN_WORKLOADS="u64-mix fixed64-geometry u64-memory"
export RISCV_XLEN_BUILD_NATIVE_C=1
export RISCV_XLEN_NATIVE_RUNNER_SOURCE="$ROOT/tools/fixtures/riscv-xlen-native-runner.c"
exec "$SCRIPT_DIR/compile-riscv-xlen-gate-corpus.sh" "$@"
