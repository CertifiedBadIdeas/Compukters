# K16 Rust-First Language Strategy

> Issue: [#135](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/135)
>
> Previous decision: [#134](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/134)
>
> Update: [#367](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/367)
> supersedes the user-space part of this older Rust-first direction. KraftOS
> now follows a C-first userland/coreutils policy while the Rust K16 kernel and
> OS internals remain Rust for now. The current crate-by-crate map lives in
> [K16 Guest Rust Migration Map](k16-guest-rust-migration-map.md).

## Decision

K16 should be treated as a real machine target for Rust-first development,
not as a reason to maintain a custom project language indefinitely.

The long-term language direction is:

```text
K16 CPU/ABI
  -> ELF32 object ABI
  -> K16E packaging
  -> custom rustc/LLVM K16 target
  -> Rust no_std kernels
  -> Rust user-space programs
  -> hosted Rust std only after real OS services exist
```

The existing Rux language/compiler/source layer is retired as an active path.
Guest-owned BIOS, bootloader, kernel, runtime, and user-space source code
should live under `rust/guest` and target Rust.

## Rationale

Maintaining a custom language is expensive and not central to the project goal.
Every language feature pulls a chain of follow-up work: parser rules, type
checking, diagnostics, formatter behavior, standard library APIs, examples,
tests, docs, compatibility policy, and migration support.

K16 already needs the harder and more reusable layer: a precise machine ABI,
object format, linker, boot flow, kernel ABI, storage model, and runtime helper
objects. Once that layer is real, Rust can target it directly. That is a better
use of effort than growing Rux into a parallel language ecosystem.

Rust is the target language because a `no_std` kernel is a realistic way to
write OS code without requiring hosted `std`, while still keeping access to a
real compiler, type system, tooling culture, and future ecosystem path.

## Rux Retirement

Deprecating Rux does not mean deleting existing files immediately. It means:

- no new Rux guest/source code;
- existing Rux source, standard library, compiler frontend, and Rux-language
  examples are retired active paths;
- no new general-purpose Rux language features by default;
- no Rux standard-library expansion as a strategic direction;
- no new long-term application APIs designed primarily for Rux;
- no new BIOS, bootloader, kernel, or user-space examples in Rux;
- remaining guest software should be organized under `rust/guest`.

Before the custom K16 Rust target is ready, new work should focus on the
toolchain boundary rather than adding more Rux source. Acceptable temporary
artifacts are:

- raw K16 object fixtures for linker/VM tests;
- LLVM IR or freestanding C smoke inputs when they validate the machine target;
- host-side tooling needed to package, inspect, link, or run K16 artifacts;
- documentation that explains the transition away from existing Rux code.

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

These pieces are not part of the active guest software structure. New guest
work belongs under `rust/guest`.

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

- rustc emits ordinary K16 ELF32 relocatable objects;
- `k16 link` packages those objects into `K16E`;
- firmware, bootloaders, kernels, and user-space programs use the same machine
  ABI rules, with different load contracts where needed;
- missing runtime helpers remain link-time errors until explicit K16 object
  implementations exist.

The Rust path should progress in this order:

```text
no_core smoke
  -> runtime helper objects
  -> panic=abort boundary
  -> core-only no_std firmware and kernel code
  -> alloc after kernel memory management
  -> hosted std only after real OS services exist
```

Hosted `std` is intentionally not an early target. It would require files,
processes, time, synchronization, environment variables, allocation, and I/O
semantics provided by the guest OS. It must not be emulated with hidden host
paths or VM shortcuts.

## Migration Rule

Do not extend Rux. Build new guest slices under `rust/guest`; keep host tools
under `rust/host/k16-tools` limited to artifact, linker, volume, filesystem,
inspect, disassembly, runtime-object build, and run helpers.

The active sequence is:

```text
rust/guest/k16-rt
  -> Rust BIOS
  -> Rust bootloader
  -> Rust kernel
  -> Rust user-space programs
```

## Roadmap Impact

- [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132) is
  the next Rust proof: `rustc -> K16 object -> K16E -> VM`.
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
- [#148](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/148)
  tracks the K16 Rust `core` sysroot milestone. BIOS and bootloader should stay
  on `core` only, without `alloc` or `std`.
