# Rux16 Rust-First Language Strategy

> Issue: [#135](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/135)
>
> Previous decision: [#134](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/134)

## Decision

Rux16 should be treated as a real machine target for Rust-first development,
not as a reason to maintain a custom project language indefinitely.

The long-term language direction is:

```text
Rux16 CPU/ABI
  -> ELF32 object ABI
  -> RUXE packaging
  -> custom rustc/LLVM Rux16 target
  -> Rust no_std kernels
  -> Rust user-space programs
  -> hosted Rust std only after real OS services exist
```

The existing Rux language is legacy/bootstrap tooling. It can remain in the
repository while Rust support is incomplete, but it should not be the future
application, firmware, kernel, or user-space language.

## Rationale

Maintaining a custom language is expensive and not central to the project goal.
Every language feature pulls a chain of follow-up work: parser rules, type
checking, diagnostics, formatter behavior, standard library APIs, examples,
tests, docs, compatibility policy, and migration support.

Rux16 already needs the harder and more reusable layer: a precise machine ABI,
object format, linker, boot flow, kernel ABI, storage model, and runtime helper
objects. Once that layer is real, Rust can target it directly. That is a better
use of effort than growing Rux into a parallel language ecosystem.

Rust is the target language because a `no_std` kernel is a realistic way to
write OS code without requiring hosted `std`, while still keeping access to a
real compiler, type system, tooling culture, and future ecosystem path.

## Rux Deprecation

Deprecating Rux does not mean deleting it immediately. It means:

- no new general-purpose Rux language features by default;
- no Rux standard-library expansion as a strategic direction;
- no new long-term application APIs designed primarily for Rux;
- new boot/kernel/program examples should move to Rust once Rust can build
  equivalent Rux16 objects;
- existing Rux examples may stay only as compatibility and tooling fixtures
  until Rust replacements exist.

Rux can still be used temporarily for BIOS, bootloader, kernel-loader, VM,
linker, RUXE, filesystem, and device tests while Rust support is incomplete.
That use is transitional. It should not create new language commitments.

## Rust Target

The preferred target shape is freestanding Rust:

- rustc emits ordinary Rux16 ELF32 relocatable objects;
- `rux link` packages those objects into `RUXE`;
- firmware, bootloaders, kernels, and user-space programs use the same machine
  ABI rules, with different load contracts where needed;
- missing runtime helpers remain link-time errors until explicit Rux16 object
  implementations exist.

The Rust path should progress in this order:

```text
no_core smoke
  -> runtime helper objects
  -> panic=abort boundary
  -> core/no_std kernel code
  -> alloc after kernel memory management
  -> hosted std only after real OS services exist
```

Hosted `std` is intentionally not an early target. It would require files,
processes, time, synchronization, environment variables, allocation, and I/O
semantics provided by the guest OS. It must not be emulated with hidden host
paths or VM shortcuts.

## Migration Rule

Keep Rux only where it is still the only working path. As soon as a Rust path
can cover the same slice, prefer Rust and convert the Rux example/test into a
compatibility fixture or remove it in a scoped cleanup.

This gives the project a clear replacement sequence:

```text
Rux bootstrap examples
  -> Rust no_core smoke
  -> Rust runtime-helper coverage
  -> Rust no_std kernel
  -> Rust boot/user-space examples
  -> Rux compiler retirement decision
```

## Roadmap Impact

- [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132) is
  the next Rust proof: `rustc -> Rux16 object -> RUXE -> VM`.
- [#133](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/133)
  follows with explicit runtime helpers for compiler-generated calls.
- [#29](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/29) should
  define the panic/error boundary reused by Rust `panic=abort`.
- [#57](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/57)
  remains the right direction for future guest filesystem access.
- [#27](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/27) should
  remain dropped; Rux stdlib growth is not the project direction.
