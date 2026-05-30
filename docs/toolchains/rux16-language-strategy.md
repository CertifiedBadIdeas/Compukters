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

The existing Rux language/compiler/source layer is legacy. It can remain only
while Rust support is incomplete, and the goal is to retire it completely once
Rust replacements exist. BIOS, bootloader, kernel, and user-space source code
should target Rust.

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

## Rux Retirement

Deprecating Rux does not mean deleting existing files immediately. It means:

- no new Rux guest/source code by default;
- existing Rux source, standard library, compiler frontend, and Rux-language
  examples are retirement targets;
- no new general-purpose Rux language features by default;
- no Rux standard-library expansion as a strategic direction;
- no new long-term application APIs designed primarily for Rux;
- no new BIOS, bootloader, kernel, or user-space examples in Rux unless the
  goal is explicitly to preserve an existing legacy behavior until it can be
  replaced;
- existing Rux examples may stay only as legacy compatibility fixtures until
  Rust replacements exist.

Before the custom Rux16 Rust target is ready, new work should focus on the
toolchain boundary rather than adding more Rux source. Acceptable temporary
artifacts are:

- raw Rux16 object fixtures for linker/VM tests;
- LLVM IR or freestanding C smoke inputs when they validate the machine target;
- host-side tooling needed to package, inspect, link, or run Rux16 artifacts;
- documentation that explains how existing Rux code will be replaced.

Those artifacts must not become a new Rux-language feature path. If a slice
needs guest logic that cannot yet be written in Rust, prefer narrowing the
slice to toolchain readiness over adding new `.rx` code.

## What Is Being Retired

The retirement target is the Rux language stack:

- `.rx` source files;
- the Rux parser/resolver/frontend;
- Rux standard library modules;
- Rux-language BIOS, bootloader, kernel, and program examples;
- CLI behavior whose purpose is compiling Rux source.

These pieces should be replaced by Rust-authored artifacts as the Rust target
becomes capable enough. Until then they may remain only to preserve the current
boot chain and to provide comparison fixtures.

The Rux language name remains Rux while the language exists. `.rx`, the Rux
frontend, Rux stdlib modules, and Rux-language commands are not renamed by the
machine rename.

The machine/tooling naming decision now lives in
[#147](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/147):
future VM, ISA, artifact, storage, filesystem, and Rust target names should use
`Kraft16`/`K16`. Current ABI documents may still describe existing Rux-named
formats until each compatibility-affecting ABI migration lands.

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
Existing Rux bootstrap examples
  -> Rust no_core smoke
  -> Rust runtime-helper coverage
  -> Rust no_std kernel
  -> Rust bootloader/kernel/user-space examples
  -> remove Rux source examples
  -> remove Rux compiler frontend
  -> remove Rux-language CLI surfaces
```

## Roadmap Impact

- [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132) is
  the next Rust proof: `rustc -> Rux16 object -> RUXE -> VM`.
- [#133](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/133)
  should be framed as Rust target runtime/helper work, not Rux source runtime
  expansion.
- [#29](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/29) should
  define the panic/error boundary reused by Rust `panic=abort`.
- [#57](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/57)
  remains the right direction for future guest filesystem access.
- [#27](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/27) should
  remain dropped; Rux stdlib growth is not the project direction.
- [#138](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/138)
  records the stronger goal: existing Rux language/compiler/source code should
  be retired, not only frozen for new work.
