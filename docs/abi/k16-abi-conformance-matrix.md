# K16 ABI Conformance Matrix

> Issue: [#245](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/245)

This matrix is the maintainer checklist for the currently supported
LLVM-facing and Rust-produced K16 ABI surface. It does not define a new ABI;
the normative contracts remain in `k16-cpu-v1.md`, `k16-object-v1.md`, and
`k16e-v1.md`. Its job is to make the supported subset and its regression
coverage explicit before publishing a K16 toolchain archive.

## Supported Patterns

| Pattern | Status | Coverage |
| --- | --- | --- |
| scalar arguments and returns | Supported for the C ABI register path using `r1` through `r3` for the first three 32-bit arguments and `r0` for the first return word. | `call-args.ll`, `ret.ll`, `ret-i32.ll`; Rust smoke through `k16_runtime_cli`. |
| stack-passed arguments | Supported for outgoing and incoming arguments beyond the first register arguments. The caller reserves outgoing stack space and the callee reads incoming stack arguments relative to the return-PC word. | `call-stack-args.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| multi-register returns | Supported for small multi-word returns that fit the active return register convention. | `multi-return-registers.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| small aggregate returns | Supported only when the lowered value fits the current small aggregate register-return path. | `aggregate-byvalue.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| aggregate-by-value arguments | Supported for the currently tested scalarized and stack-backed aggregate shapes. | `aggregate-byvalue.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| global-address addends | Supported for direct symbol addresses plus constant field offsets. Object relocations must carry the addend instead of losing it during lowering or linking. | `global-address-offset.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| static aggregate field addresses | Supported for direct field access into static aggregate objects and for aggregate copies emitted from Rust. | `aggregate-static-frame.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| memory helper libcalls | Supported as explicit runtime helper symbols. Generated objects may call `memcpy`, `memset`, `memmove`, and the `__k16_*` aliases, but the helper object is never implicit. | `mem-intrinsics.ll`; `k16_object_abi_docs`; `k16_rust_smoke_artifacts`. |
| runtime helper symbols | Supported when supplied by `k16 runtime k16-startup`, `k16 runtime k16-memory-helpers`, and `k16 runtime k16-cpu-helpers`. Missing symbols are link-time errors. | `k16_runtime_cli`; `k16_rust_smoke_artifacts`; `k16_object_abi_docs`. |
| i64 integer helper calls | Supported through explicit helper functions for div/rem and shift cases used by current Rust output. | `libcall-i64-divrem.ll`, `i64-shift-parts.ll`; `k16_rust_smoke_artifacts`. |

## Unsupported Patterns

These shapes are intentionally outside the current conformance target:

- struct returns are unsupported when they require a hidden return pointer or a
  larger aggregate ABI than the active small-register-return subset.
- varargs are unsupported.
- exceptions and unwinding are unsupported.
- dynamic linking is unsupported.
- position-independent code is unsupported.
- tail calls are unsupported as a stable ABI promise.
- callee cleanup is unsupported.
- stack bounds metadata is unsupported.
- jump-table object and relocation semantics are unsupported for Rust firmware
  builds; production guest builds use `-Cjump-tables=no`.
- debug object relocations are unsupported for K16 linking; production guest
  builds use `-Cdebuginfo=0`.

Unsupported means toolchains must reject, avoid, or lower through an explicit
helper path. Silent host fallback behavior is not a valid K16 ABI result.

## Maintainer Verification

Before publishing or pinning a K16 toolchain archive, run the conformance
commands that match the changed layer:

```bash
.toolchain/build/llvm/k16-min/bin/llvm-lit toolchains/Compukter-Kraft-llvm/llvm/test/CodeGen/K16
```

```bash
cd host/k16-tools
K16_CARGO="$PWD/../../../.toolchain/k16/k16-dev-2026-06-13/linux-x86_64/bin/cargo" \
K16_RUSTC="$PWD/../../../.toolchain/k16/k16-dev-2026-06-13/linux-x86_64/bin/rustc" \
cargo test --test k16_runtime_cli
```

```bash
cd host/k16-tools
cargo test --test k16_rust_smoke_artifacts
```

For documentation-only changes, also run:

```bash
cd host/k16-tools
cargo test --test k16_object_abi_docs
```

If a future backend change supports one of the currently unsupported rows, add
the focused lit or Rust smoke first, update this matrix in the same commit, and
only then rely on the new shape from kernel or userland code.
