# Rux16 Rust Feasibility

> Issue: [#127](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/127)

> Strategy: [Rux16 Rust-First Language Strategy](rux16-language-strategy.md)

## Current State

Rux16 now has enough LLVM-facing infrastructure for a tiny freestanding C proof:

- a Rux16 LLVM backend prototype can emit ELF32 relocatable objects;
- Clang can compile `--target=rux16` freestanding C into `.text.rux16`;
- `rux runtime rux16-startup` provides `_start`, calls `main`, and exposes the
  low byte of the return value through `debug::WRITE`;
- `rux link --target program` converts Rux16 objects into a `RUXE` program;
- `rux run` executes that `RUXE` in the VM and observes `debug_bytes=2a`.

That proves the CPU/object/RUXE path, not Rust support. Rust requires a target
description, language runtime decisions, panic behavior, compiler helper
symbols, and eventually an OS ABI surface.

## Rust Path Options

### `no_core`

`no_core` is the smallest useful Rust proof. It avoids `core`, lang items from
the standard library, allocation, formatting, and panic machinery as much as
possible. It is useful for validating that `rustc` can target Rux16 LLVM output
and that Rux tooling can link the resulting object.

Recommended first Rust slice:

```rust
#![no_core]
#![no_main]

#[no_mangle]
pub extern "C" fn main() -> i32 {
    42
}
```

This should use the same `rux16-startup -> main -> debug byte -> halt` boundary
as the C smoke.

### `core`-Only

`core` becomes plausible after the target can satisfy Rust's required target
metadata and compiler-builtins expectations. Even without allocation or `std`,
`core` pulls in assumptions around panic language items, memory intrinsics,
integer helper operations, and target feature metadata.

This is the right second milestone, not the first one.

### `no_std`

`no_std` means Rust code uses `core` and may use `alloc` when a global allocator
exists. For Rux16, that requires an explicit panic strategy, allocator boundary,
and OS/runtime calls for any non-trivial program. It should wait until the
kernel/user ABI has a deliberate syscall or capability surface.

### Hosted `std`

Hosted `std` is not near-term. It requires a much larger OS contract:
filesystem, process, environment, time, synchronization, allocation, panics,
and I/O. The current Rux OS direction is not ready for this, and trying to add
`std` early would force hidden host behavior or VM shortcuts.

## Required Pieces

### Rust Target Description

Rust needs a target specification for Rux16. The likely target shape is:

```text
llvm-target:        rux16-unknown-ruxos or rux16
pointer-width:      32
data-layout:        e-p:32:32-i32:32-n32
arch:               rux16
os/env/vendor:      explicit experimental values
panic strategy:     abort
relocation model:   static
executables:        false at rustc layer; RUXE is produced by rux link
```

The Rust target must emit ordinary Rux16 ELF objects. Rust must not emit `RUXE`
directly in the first slice.

### Entry And Link Boundary

The initial Rust proof should define `main` as an unmangled C ABI symbol and
reuse `rux16-startup`. That keeps Rust aligned with the current Clang smoke and
avoids inventing Rust-specific VM entry rules.

The pipeline should be:

```text
rustc
  -> Rux16 ELF32 object
  -> rux runtime rux16-startup
  -> rux link --target program
  -> rux run
```

### Compiler Builtins And Runtime Helpers

Rux16 currently reserves helper symbols such as:

```text
__rux16_memcpy
__rux16_memset
__rux16_memmove
```

Rust may also need integer helper routines depending on emitted IR and target
legalization. Missing helper calls must remain link-time errors until explicit
Rux16 runtime objects provide them.

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

Atomics should be treated as unsupported until Rux16 has a deliberate atomic
or synchronization ABI. Single-threaded Rust proofs should avoid atomic APIs.

### Filesystem And I/O

Rust `std` filesystem APIs should not map to host paths. Guest file access must
go through the future Rux OS filesystem/capability ABI, the same direction as
Rux `std::fs`. Hosted Rust is blocked until that exists.

## Blocking Gaps

- Rust target spec and driver invocation for a local experimental Rux16 target.
- A no-core smoke that proves `rustc -> object -> RUXE -> VM`.
- Explicit Rux16 runtime helper objects for memory intrinsics and compiler
  builtins that Rust/LLVM may emit.
- Panic-abort behavior tied to the Rux panic/error model.
- Allocator and filesystem/syscall surfaces for any meaningful `no_std + alloc`
  or hosted `std` work.

## Recommendation

Do not start with hosted `std`, `no_std + alloc`, or a broad `core` promise.
Start with a `no_core` smoke that reuses the existing Clang proof boundary:
return `42` from `main`, link with `rux16-startup`, execute through `rux run`,
and observe `debug_bytes=2a`.

After that, add the smallest runtime-helper object set needed by `core`, then
define `panic=abort` behavior. Only after the OS syscall/capability boundary is
stable should Rux16 try `no_std + alloc` or hosted Rust paths.

## Follow-Up Issues

- [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132):
  Add first Rust `no_core` smoke for Rux16.
- [#133](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/133):
  Add Rux16 runtime helper objects for compiler-generated memory operations.
- [#29](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/29):
  Reuse the Rux panic/error model work for Rust `panic=abort`.
- [#57](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/57):
  Reuse future Rux filesystem/capability work before attempting hosted `std`.
