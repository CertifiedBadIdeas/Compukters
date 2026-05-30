# Rux16 Rust no_core Smoke

> Issue: [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132)
>
> Toolchain: [Rux16 LLVM Submodule](rux16-llvm-submodule.md)
>
> rustc bootstrap: [Rux16 rustc Bootstrap Path](rux16-rustc-bootstrap.md)

`tools/rux16-rust-nocore-smoke.sh` is the first intended Rust smoke path for
Rux16. It compiles a tiny `#![no_core]` Rust program with an unmangled
`extern "C" fn main() -> i32`, links it with `rux16-startup` and the explicit
`rux16-memory-helpers` runtime object, executes the resulting program `RUXE`,
and expects `debug_bytes=2a`.

The script is strict. It requires a custom rustc that is:

- nightly-capable, because `no_core` and custom target JSON are unstable;
- built with the Rux16 LLVM backend available inside rustc's LLVM;
- paired with `tools/rux16-unknown-ruxos.json`;
- paired with the Rux16 LLVM tools used for object inspection.

The embedded no_core source defines the current minimal lang item set required
by this Rust revision: `sized`, `meta_sized`, and `pointee_sized`.

Run it with explicit tool paths:

```bash
RUX16_RUSTC=/path/to/custom-rux16-rustc \
RUX16_LLVM_BIN_DIR=/path/to/rux16/llvm/bin \
tools/rux16-rust-nocore-smoke.sh
```

The current host rustc is not enough if it does not contain the Rux16 LLVM
target. In that case the script stops before compiling instead of routing
through a host target or another fallback.

Expected successful output includes:

```text
Rust no_core object checks passed
RUXE link and execution checks passed
Rux16 Rust no_core smoke passed
```
