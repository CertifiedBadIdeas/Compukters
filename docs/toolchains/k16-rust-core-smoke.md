# K16 Rust core Smoke

> Issue: [#148](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/148)
>
> Previous proof: [K16 Rust no_core Smoke](k16-rust-nocore-smoke.md)

`tools/k16-rust-core-smoke.sh` is the first strict proof path for ordinary
freestanding Rust on K16. It uses Cargo's nightly `-Z build-std=core` and
`-Z json-target-spec` path to build Rust `core` for
`tools/k16-unknown-kraftos.json`, then compiles a tiny `#![no_std]` library crate
with an exported C ABI `main`, links the emitted object through `k16 link`, and
executes it through the VM. The final executable link is owned by `k16 link`,
not by Cargo or host `lld`.

The smoke passes `-C jump-tables=no` because the current K16 ABI slice does not
yet define a jump-table object/relocation contract for Rust `core`. Switches
must lower to explicit branch code until that ABI is added.

The smoke also passes `-Cdebuginfo=0` because the current object linker
explicitly rejects debug relocation semantics. Debug object support must be
added as a separate ABI/tooling slice before enabling debuginfo here.

The smoke uses `RUSTC_BOOTSTRAP=1` and `-Copt-level=z`, matching the production
guest firmware build profile closely enough for standalone local verification.
That keeps the script aligned with the same unstable custom-target path used by
the Gradle-built guest artifacts.

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
contract when the K16 `usize` width is 32 bits. A successful VM run observes:

```text
signal=halt exit_status=42 debug_bytes=
```

## Current Result

The smoke passes with the aligned local K16 stage1 Rust toolchain and the pinned
K16 LLVM checkout. That proves the strict `core` sysroot path can build a
`#![no_std]` K16 object, link it through the K16 object pipeline, execute the
resulting `K16E` program in the VM, and report the `main` return value through
the standalone `k16 run` path.

This does not enable `alloc`, hosted `std`, panic unwinding, debug object
relocations, or direct Rust `u64` division libcalls. Those remain separate ABI
and runtime slices.

## Firmware Tier

BIOS and bootloader artifacts should stay on this same tier:

```text
-Z build-std=core
```

They should not use `alloc` or `std`. `alloc` will require an explicit global
allocator and kernel memory policy. Hosted `std` will require real guest OS
services such as files, environment, time, synchronization, and I/O.
