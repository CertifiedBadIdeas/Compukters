#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
ARTIFACTS="$(mktemp -d)"
trap 'rm -rf "$ARTIFACTS"' EXIT HUP INT TERM

first="$ARTIFACTS/rv32-trap-first.elf"
second="$ARTIFACTS/rv32-trap-second.elf"
"$ROOT/scripts/compile-rv32-elf-trap-fixture.sh" "$first"
"$ROOT/scripts/compile-rv32-elf-trap-fixture.sh" "$second"
cmp "$first" "$second"

RV32_ELF_TRAP_FIXTURE="$first" \
    cargo test --locked --offline --manifest-path "$ROOT/host/k16-vm/Cargo.toml" \
    --test rv32_elf_trap stock_toolchain_rv32_elf_handles_ecall_and_returns -- --ignored --exact

echo "RV32 ELF trap contract passed"
