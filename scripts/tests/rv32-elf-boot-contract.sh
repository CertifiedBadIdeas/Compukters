#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
ARTIFACTS="$(mktemp -d)"
trap 'rm -rf "$ARTIFACTS"' EXIT HUP INT TERM

first="$ARTIFACTS/rv32-boot-first.elf"
second="$ARTIFACTS/rv32-boot-second.elf"
"$ROOT/scripts/compile-rv32-elf-boot-fixture.sh" "$first"
"$ROOT/scripts/compile-rv32-elf-boot-fixture.sh" "$second"
cmp "$first" "$second"

RV32_ELF_BOOT_FIXTURE="$first" \
    cargo test --locked --offline --manifest-path "$ROOT/host/compukter-vm/Cargo.toml" \
    --test rv32_elf_boot stock_toolchain_rv32_elf_boots_and_halts -- --ignored --exact

echo "RV32 ELF boot contract passed"
