# K16 ABI Conformance Matrix

> Issue: [#245](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/245)

This matrix is the maintainer checklist for the currently supported
LLVM-facing and Rust-produced K16 ABI surface. It does not define a new ABI;
the normative contracts remain in `k16-cpu-v1.md`, `k16-object-v1.md`, and
`k16e-v1.md`. Its job is to make the supported subset and its regression
coverage explicit before publishing a K16 toolchain archive.

The LLVM/Rust rows below describe the production toolchain. The TinyCC table
records the host-backend implementation of the same K16 C ABI, including the
cross-compiler boundary completed by issues #464 and #465.

## Supported Patterns

| Pattern | Status | Coverage |
| --- | --- | --- |
| scalar arguments and returns | Supported for the C ABI register path using `r1` through `r3` for the first three 32-bit arguments and `r0` for the first return word. | `call-args.ll`, `ret.ll`, `ret-i32.ll`; Rust smoke through `k16_runtime_cli`. |
| stack-passed arguments | Supported for outgoing and incoming arguments beyond the first register arguments. The caller reserves outgoing stack space and the callee reads incoming stack arguments relative to the return-PC word. | `call-stack-args.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| multi-register returns | Supported for small multi-word returns that fit the active return register convention. | `multi-return-registers.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| small aggregate returns | Supported only when the lowered value fits the current small aggregate register-return path. | `aggregate-byvalue.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| C multiword scalar layout and transport | Supported for `long long`, binary64 `double`, and binary64 `long double`, all with eight-byte size and alignment and low-address word first. Floating representation transport does not imply soft-float arithmetic helpers. | `type-layout.c`, `wide-direct.c`, `k16-varargs.c`; `verifyK16TinyCc`. |
| aggregate-by-value arguments | The K16 C classifier passes one 32-bit guest pointer to an aligned caller-owned copy. Copy storage remains outside the logical argument stream and lives through the call. Existing LLVM/Rust scalarized aggregate paths remain supported where separately covered. | `varargs-calls.ll`, `aggregate-byvalue.ll`, `aggregate-args.c`, `varargs-caller.c`, `varargs-callee.c`. |
| C variadic calls | Supported with fixed arguments on the ordinary ABI and every unnamed promoted argument in an aligned caller-owned stack stream. `va_list` is a `char *`; `va_copy` copies the cursor and `va_end` is a no-op. | `varargs-formals.ll`, `varargs-calls.ll`, `k16-varargs.c`; all four TinyCC/Clang caller-callee combinations in `verifyK16TinyCc`. |
| global-address addends | Supported for direct symbol addresses plus constant field offsets. Object relocations must carry the addend instead of losing it during lowering or linking. | `global-address-offset.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| static aggregate field addresses | Supported for direct field access into static aggregate objects and for aggregate copies emitted from Rust. | `aggregate-static-frame.ll`; Rust aggregate smoke through `k16_runtime_cli`. |
| signed imm16 addressing | Supported for `addi` and base-plus-offset K16 load/store forms. Stack/frame lowering should use these compact forms when the offset fits signed 16 bits and use explicit materialization only for larger values. | `stack-local.ll`, `frame-index-address.ll`, `call-stack-args.ll`, `aggregate-byvalue.ll`; VM and assembler/disassembler Rust tests. |
| memory helper libcalls | Supported as explicit runtime helper symbols. Generated objects may call `memcpy`, `memset`, `memmove`, and the `__k16_*` aliases, but the helper object is never implicit. | `mem-intrinsics.ll`; `k16_object_abi_docs`; `k16_rust_smoke_artifacts`. |
| runtime helper symbols | Supported when supplied by `k16 runtime k16-startup`, `k16 runtime k16-memory-helpers`, and `k16 runtime k16-cpu-helpers`. Missing symbols are link-time errors. | `k16_runtime_cli`; `k16_rust_smoke_artifacts`; `k16_object_abi_docs`. |
| i64 integer helper calls | Supported through explicit helper functions for div/rem and shift cases used by current Rust output. | `libcall-i64-divrem.ll`, `i64-shift-parts.ll`; `k16_rust_smoke_artifacts`. |

## TinyCC K16 Proof Backend

| Pattern | TinyCC status | Coverage |
| --- | --- | --- |
| ELF object boundary | Supported for little-endian ELF32 `ET_REL`, `.text.k16`, `.rodata`, `.data`, `.bss`, and explicit RELA records. TinyCC does not produce K16E. | `return-42.c`, `rodata.c`, `relocations.c`; `verifyK16TinyCcBackend`. |
| scalar arguments and returns | Supported for direct values spanning one or more 32-bit ABI slots: `r1` through `r3` hold the first fixed slots and `r0` through `r3` carry supported direct returns. | `calls.c`, `wide-direct.c`; TinyCC/Clang mixed-link execution. |
| stack-passed arguments | Supported for caller-owned arguments after the first three registers, including required stack alignment and callee frame access. | Six-argument call in `calls.c`. |
| narrow integer memory | Supported for byte and half-word loads/stores, signed extension, unsigned extension, and casts. | `narrow-memory.c`. |
| scalar locals and pointers | Supported for frame locals, local arrays, pointer arithmetic, dereference, globals, symbol addends, and writable pointer initializers. | `pointers-locals.c`, `relocations.c`. |
| integer operations | Supported for 32-bit arithmetic, bitwise operations, shifts, comparisons, division, and remainder. | `arithmetic.c`; freestanding `compiler-runtime.c` for Clang libcall parity. |
| structured control flow | Supported for conditions, loops, switches, and internal long branches using canonical K16 relocations. | `control-flow.c`. |
| direct and indirect calls | Supported for the proven C signatures, including wide direct values, indirect aggregate arguments, and variadic callees. Direct calls use `R_K16_CALL32`; function pointers use `call r14` without a symbol relocation. | `calls.c`, `aggregate-args.c`, `varargs-caller.c`; exact byte and relocation checks in `tools/k16-tinycc-smoke.sh`. |
| aggregate arguments | Supported through aligned caller-owned copies and 32-bit guest pointers, with mutation isolation across the call. | `aggregate-args.c`, `varargs-caller.c`, `varargs-callee.c`. |
| variadic calls and callees | Supported for default-promoted integers, pointers, 64-bit integers, `double`, `long double`, and aggregates. TinyCC/TinyCC, TinyCC/Clang, Clang/TinyCC, and Clang/Clang objects execute the same corpus. | `varargs-basic.c`, `varargs-caller.c`, `varargs-callee.c`; `verifyK16TinyCcBackend`. |
| dynamic KraftOS importer | Proven for the existing `crt0.c` plus `uname.c`, linked by `k16 link` against `libkraft.kso` and executed inside a copied real KraftOS volume. | `compileK16TinyCcUnameProof`; `K16TinyCcRuntimeSmokeTest`. |
| unsupported C extensions and execution modes | Floating arithmetic, vectors, complex values, empty GNU aggregates, over-aligned values, integrated assembly, and JIT/`-run` are rejected before an output object is accepted. Floating and wide object representations remain transportable. GNU declaration symbol labels remain supported. | `reject-*.c`, `asm-label.c`; `verifyK16TinyCcBackend`. |

## Unsupported Patterns

These shapes are intentionally outside the current conformance target:

- struct returns are unsupported when they require a hidden return pointer or a
  larger aggregate ABI than the active small-register-return subset.
- C vector extensions, complex values, empty GNU aggregates, and alignments
  greater than eight bytes are unsupported.
- floating arithmetic requires explicit runtime helper support; the ABI only
  promises transport of floating object representations.
- exceptions and unwinding are unsupported.
- compiler-driver-owned dynamic linking is unsupported; final imported dynamic
  K16E linkage remains owned by the explicit `k16 link` path.
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
./gradlew-sandbox-dev-parallel verifyK16TinyCc
```

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
