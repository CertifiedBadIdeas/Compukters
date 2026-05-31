# K16 Rust core Smoke

> Issue: [#148](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/148)
>
> Previous proof: [K16 Rust no_core Smoke](k16-rust-nocore-smoke.md)

`tools/k16-rust-core-smoke.sh` is the first strict proof path for ordinary
freestanding Rust on K16. It uses Cargo's nightly `-Z build-std=core` and
`-Z json-target-spec` path to build Rust `core` for
`tools/k16-unknown-kraftos.json`, then compiles a tiny `#![no_std]` program,
links it through `k16 link`, and executes it through the VM.

The smoke passes `-C jump-tables=no` because the current K16 ABI slice does not
yet define a jump-table object/relocation contract for Rust `core`. Switches
must lower to explicit branch code until that ABI is added.

This smoke is core only: no alloc, no std, no panic unwinding, and no hidden VM
or host fallback path. Missing target support or missing helper symbols must
fail the build or link explicitly.

## Required Toolchain

The script requires:

- `K16_RUSTC` pointing at a custom nightly rustc with the K16 LLVM target;
- nightly-capable Cargo with `-Z build-std` and `-Z json-target-spec` support;
- `K16_LLVM_BIN_DIR` pointing at LLVM tools built with the K16 target;
- `tools/k16-unknown-kraftos.json` as the Rust target specification.

Example:

```bash
K16_RUSTC=/path/to/custom/stage1/rustc \
K16_CARGO=/path/to/nightly/cargo \
K16_LLVM_BIN_DIR=/path/to/k16/llvm/bin \
tools/k16-rust-core-smoke.sh
```

Expected final output after the K16 `core` path is complete:

```text
Rust core object checks passed
KX link and execution checks passed
K16 Rust core smoke passed
```

The test program returns `42` through the existing `k16-startup -> main`
contract, so a successful VM run observes:

```text
debug_bytes=2a
```

## Current State

The strict bootstrap probe requires the external K16 LLVM checkout to be based
on the Rust-pinned `src/llvm-project` commit. The main repository now pins
`toolchains/Compukter-Kraft-llvm` to the `k16-rust-pinned` branch commit that
satisfies that check.

The next required step is rebuilding stage1 rustc against that aligned LLVM
checkout before treating any `core` codegen result as valid.

After a compatible stage1 host `std` sysroot is rebuilt, the smoke reaches real
K16 codegen for Rust `core`. The known backend blocker after that point is:

```text
rustc-LLVM ERROR: unsupported library call operation
```

Standalone LLVM lowering for register and constant `shr` is covered by the K16
LLVM tests, and Rust jump tables are disabled for this ABI slice. The remaining
work is the broader Rust `core` codegen surface exercised by `build-std=core`.

## Firmware Tier

BIOS and bootloader artifacts should stay on this same tier:

```text
-Z build-std=core
```

They should not use `alloc` or `std`. `alloc` will require an explicit global
allocator and kernel memory policy. Hosted `std` will require real guest OS
services such as files, environment, time, synchronization, and I/O.
