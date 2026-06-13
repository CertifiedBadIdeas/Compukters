# K16 Rust Feasibility

> Issue: [#127](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/127)

> Strategy: [K16 Rust-First Language Strategy](k16-language-strategy.md)

## Current State

K16 now has enough LLVM-facing infrastructure for a tiny freestanding C proof:

- a K16 LLVM backend prototype can emit ELF32 relocatable objects;
- Clang can compile the current `--target=k16` backend into `.text.k16`;
- `k16 runtime k16-startup` provides `_start`, calls `main`, and terminates
  through the K16 `EXIT` syscall with the returned `r0` value as status;
- `k16 link --target program` converts K16 objects into a `K16E` program;
- bundled firmware executes that `K16E` under the Rust kernel syscall handler
  and observes the process exit status.

That proves the CPU/object/K16E path, not Rust support. Rust requires a target
description, language runtime decisions, panic behavior, compiler helper
symbols, and eventually an OS ABI surface.

## Rust Path Options

### `no_core`

`no_core` is the smallest useful Rust proof. It avoids `core`, lang items from
the standard library, allocation, formatting, and panic machinery as much as
possible. It is useful for validating that `rustc` can target K16 LLVM output
and that K16 tooling can link the resulting object.

Recommended first Rust slice:

```rust
#![no_core]
#![no_main]

#[no_mangle]
pub extern "C" fn main() -> i32 {
    42
}
```

This should use the same `k16-startup -> main -> debug byte -> halt` boundary
as the C smoke.

### `core`-Only

`core` becomes plausible after the target can satisfy Rust's required target
metadata and compiler-builtins expectations. Even without allocation or `std`,
`core` pulls in assumptions around panic language items, memory intrinsics,
integer helper operations, and target feature metadata.

This is now tracked as the next milestone after the no_core proof:
[`tools/k16-rust-core-smoke.sh`](k16-rust-core-smoke.md). The path must stay
core only: no `alloc`, no hosted `std`, and no hidden host fallback.

### `no_std`

`no_std` means Rust code uses `core` and may use `alloc` when a global allocator
exists. For K16, that requires an explicit panic strategy, allocator boundary,
and OS/runtime calls for any non-trivial program. It should wait until the
kernel/user ABI has a deliberate syscall or capability surface.

### Hosted `std`

Hosted `std` is not near-term. It requires a much larger OS contract:
filesystem, process, environment, time, synchronization, allocation, panics,
and I/O. The current Rux OS direction is not ready for this, and trying to add
`std` early would force hidden host behavior or VM shortcuts.

## Required Pieces

### Rust Target Description

Rust needs a target specification for K16. The likely target shape is:

```text
llvm-target:        k16-unknown-kraftos or k16
pointer-width:      32
data-layout:        e-p:32:32-i32:32-n32
arch:               k16
os/env/vendor:      explicit experimental values
panic strategy:     abort
relocation model:   static
executables:        true, so Cargo can build guest binaries before k16 link
```

The Rust target must emit ordinary K16 ELF objects or intermediate executable
inputs that `k16 link` consumes. Rust must not emit `K16E` directly in the first
slice.

### Entry And Link Boundary

The initial Rust proof should define `main` as an unmangled C ABI symbol and
reuse `k16-startup`. That keeps Rust aligned with the current Clang smoke and
avoids inventing Rust-specific VM entry rules.

The pipeline should be:

```text
rustc
  -> K16 ELF32 object
  -> k16 runtime k16-startup
  -> k16 link --target program
  -> k16 run
```

### Compiler Builtins And Runtime Helpers

K16 currently provides memory helpers and the first i64 integer compiler-rt
helpers, while the remaining compiler-rt surface stays explicit link-time work:

```text
__k16_memcpy
__k16_memset
__k16_memmove
__divdi3
__udivdi3
__moddi3
__umoddi3
__divti3
__udivti3
__modti3
__umodti3
__addsf3
__subsf3
__mulsf3
__divsf3
__adddf3
__subdf3
__muldf3
__divdf3
__eqsf2
__nesf2
__gesf2
__ltsf2
__lesf2
__gtsf2
__unordsf2
__eqdf2
__nedf2
__gedf2
__ltdf2
__ledf2
__gtdf2
__unorddf2
__extendhfsf2
__extendsfdf2
__truncsfhf2
__truncdfhf2
__fixsfsi
__fixsfdi
__fixdfsi
__fixdfdi
__fixunssfsi
__fixunssfdi
__fixunsdfsi
__fixunsdfdi
__floatdisf
__floatdidf
__floatundisf
__floatundidf
```

Rust may also need additional compiler-generated helper routines depending on
emitted IR and target legalization. Missing helper calls must remain link-time
errors until explicit K16 runtime objects provide them.

The first `no_core` proof should choose code that does not require those
helpers. The `core` milestone should add the required helper object(s) before
accepting broader generated Rust code.

### Panic Behavior

The first Rust target should use `panic=abort`. Unwinding, exception tables,
personality functions, and stack unwinding are out of scope.

For `core`, the project still needs one explicit panic boundary:

- either a Rust panic handler that writes a panic code/debug byte and halts;
- or an OS trap/syscall panic path once the kernel ABI is ready.

This should connect to the broader Rux panic/error model instead of becoming a
Rust-only VM hook.

### Allocation

No allocation is required for `no_core` or minimal `core` proofs. `alloc` and
`no_std + alloc` require a global allocator backed by an explicit OS/runtime
service. That belongs after the user/kernel ABI can own memory policy.

### Atomics And Concurrency

Atomics should be treated as unsupported until K16 has a deliberate atomic
or synchronization ABI. Single-threaded Rust proofs should avoid atomic APIs.

### Filesystem And I/O

Rust `std` filesystem APIs should not map to host paths. Guest file access must
go through the future Rux OS filesystem/capability ABI, the same direction as
Rux `std::fs`. Hosted Rust is blocked until that exists.

## Blocking Gaps

- Rust target spec and driver invocation for a local experimental K16 target.
- A no-core smoke that proves `rustc -> object -> K16E -> VM`.
- A core smoke that proves `cargo rustc -Z build-std=core -> object -> K16E -> VM`.
- Explicit K16 runtime helper objects for memory intrinsics and compiler
  builtins that Rust/LLVM may emit.
- Panic-abort behavior tied to the Rux panic/error model.
- Allocator and filesystem/syscall surfaces for any meaningful `no_std + alloc`
  or hosted `std` work.

## Recommendation

Do not start with hosted `std`, `no_std + alloc`, or a broad `core` promise.
Start with a `no_core` smoke that reuses the existing Clang proof boundary:
return `42` from `main`, link with `k16-startup`, execute through `k16 run`,
and observe `debug_bytes=2a`.

After that, use the core smoke to build the smallest `core` sysroot K16 can
support. Only after the OS syscall/capability boundary is stable should K16 try
`no_std + alloc` or hosted Rust paths.

## Follow-Up Issues

- [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132):
  Add first Rust `no_core` smoke for K16.
- [#148](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/148):
  Build K16 Rust `core` sysroot.
- [#133](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/133):
  Add K16 runtime helper objects for compiler-generated memory operations.
- [#29](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/29):
  Reuse the Rux panic/error model work for Rust `panic=abort`.
- [#57](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/57):
  Reuse future Rux filesystem/capability work before attempting hosted `std`.
