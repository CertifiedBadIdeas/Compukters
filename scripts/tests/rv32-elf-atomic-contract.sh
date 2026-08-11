#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
ARTIFACTS="$(mktemp -d)"
trap 'rm -rf "$ARTIFACTS"' EXIT HUP INT TERM

first="$ARTIFACTS/rv32-atomic-first.elf"
second="$ARTIFACTS/rv32-atomic-second.elf"
"$ROOT/scripts/compile-rv32-elf-atomic-fixture.sh" "$first"
"$ROOT/scripts/compile-rv32-elf-atomic-fixture.sh" "$second"
cmp "$first" "$second"

RV32_ELF_ATOMIC_FIXTURE="$first" \
    cargo test --locked --offline --manifest-path "$ROOT/host/compukter-vm/Cargo.toml" \
    --test rv32_elf_atomic stock_toolchain_rv32ima_elf_executes_atomics_and_fences \
    -- --ignored --exact

echo "RV32 ELF atomic contract passed"
