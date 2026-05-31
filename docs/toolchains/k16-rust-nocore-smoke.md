# K16 Rust no_core Smoke

> Issue: [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132)
>
> Toolchain: [K16 LLVM Submodule](k16-llvm-submodule.md)
>
> rustc bootstrap: [K16 rustc Bootstrap Path](k16-rustc-bootstrap.md)

`tools/k16-rust-nocore-smoke.sh` is the first intended Rust smoke path for
K16. It compiles a tiny `#![no_core]` Rust program with an unmangled
`extern "C" fn main() -> i32`, links it with `rux16-startup` and the explicit
`rux16-memory-helpers` runtime object, executes the resulting KX program,
and expects `debug_bytes=2a`.

`rux16-memory-helpers` is also Rust-owned: `k16 runtime rux16-memory-helpers`
builds `native/rux-compiler/runtime/rux16_memory_helpers.rs` with the same
custom K16 rustc, then lowers the generated LLVM IR with `llc` from
`K16_LLVM_BIN_DIR`. The tool does not keep a host-generated helper object
path.

The script is strict. It requires a custom rustc that is:

- nightly-capable, because `no_core` and custom target JSON are unstable;
- built with the K16 LLVM backend available inside rustc's LLVM;
- paired with `tools/k16-unknown-kraftos.json`;
- paired with the K16 LLVM tools used for object inspection.

The embedded no_core source defines the current minimal lang item set required
by this Rust revision: `sized`, `meta_sized`, and `pointee_sized`.

Run it with explicit tool paths:

```bash
K16_RUSTC=/path/to/custom-k16-rustc \
K16_LLVM_BIN_DIR=/path/to/rux16/llvm/bin \
tools/k16-rust-nocore-smoke.sh
```

The current host rustc is not enough if it does not contain the current Rux16
LLVM backend. In that case the script stops before compiling instead of routing
through a host target or another fallback.

Expected successful output includes:

```text
Rust no_core object checks passed
KX link and execution checks passed
K16 Rust no_core smoke passed
```
