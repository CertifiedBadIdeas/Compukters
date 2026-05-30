# Rux16 Language Strategy

> Issue: [#134](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/134)

## Decision

Rux16 should be treated as a real machine target first, not as a reason to grow
a custom general-purpose language indefinitely.

The long-term language direction is:

```text
Rux16 CPU/ABI
  -> ELF32 object ABI
  -> RUXE packaging
  -> freestanding external languages
  -> Rust no_std kernels and programs when the target is ready
```

The existing Rux compiler should remain useful for bootstrap programs,
firmware/kernel experiments, tests, examples, and Rux16 tooling validation. It
should not become the project's main application language by growing its own
large standard library, package ecosystem, IDE, and long-term language runtime.

## Rationale

Maintaining a custom language is expensive. Every language feature pulls a
chain of follow-up work: parser rules, type checking, diagnostics, formatter
behavior, standard library APIs, examples, tests, docs, compatibility policy,
and migration support.

Rux16 already needs the harder and more reusable layer: a precise machine ABI,
object format, linker, boot flow, kernel ABI, storage model, and runtime helper
objects. Once that layer is real, existing freestanding languages can target
it. Rust is the most important near-term candidate because a `no_std` kernel is
a realistic way to write OS code without requiring hosted `std`.

## Role Of Rux

Rux remains valuable as a small project-native language for:

- BIOS, bootloader, and kernel loader experiments while external toolchains are
  still incomplete;
- deterministic examples that are easy to compile inside the repository;
- Rux16 VM, linker, RUXE, filesystem, and device tests;
- ABI exploration before the same boundary is exposed to external compilers.

Rux should stay small and explicit. New Rux features should be accepted only
when they directly support the boot/OS/tooling path or make current examples
materially clearer. General-purpose language growth is not the default.

## External-Language Target

The preferred target shape is freestanding:

- compilers emit ordinary Rux16 ELF32 relocatable objects;
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
  no longer be treated as a default language-growth track.
