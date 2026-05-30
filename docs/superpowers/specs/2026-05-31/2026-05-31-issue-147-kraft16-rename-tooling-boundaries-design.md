# Kraft16 Rename And Tooling Boundaries Design
> Issue: [#147](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/147)

## Decision

The project will retire the `Rux` name from the VM, architecture, artifact
formats, command-line tools, and Rust target naming. The public architecture
name is `Kraft16`; the short technical prefix is `K16`.

This is broader than retiring the old Rux source language. Existing Rux source,
compiler frontend, stdlib, and examples remain legacy retirement targets, but
the machine/tooling names are now retirement targets too.

## Naming Model

Use `Kraft16` in prose where readability matters:

- architecture and VM documentation;
- public feature descriptions;
- high-level module and issue titles.

Use `K16` where compact identifiers matter:

- binary format magic names;
- CLI subcommands and flags;
- crate/module prefixes;
- target triple components;
- filenames where long names become noisy.

The intended mapping is:

- `Rux16` -> `Kraft16` / `K16`;
- `rux-vm` -> `k16-vm`;
- `rux-compiler` -> a tooling crate, tentatively `k16-tools`;
- `rux` CLI -> `k16`;
- `RUXE` -> `K16E`;
- `RUXVOL` -> `K16VOL`;
- `RuxFS` -> `K16FS`;
- `RUXSNAP` -> `K16SNAP`;
- `rux16-unknown-ruxos` -> `k16-unknown-kraftos`.

`KraftOS` is the future OS target name. It does not imply that an OS exists
yet; it gives the Rust target triple a non-Rux system component.

## Boundaries

The reorganization must split language retirement from machine tooling:

- legacy Rux language code: `.rx` frontend, stdlib, source compiler behavior,
  source advice, and Rux-authored guest examples;
- Kraft16 machine tooling: ISA definitions, assembler/disassembler, object
  linker, executable packaging, volume/filesystem tools, runtime helper object
  generation, Rust/LLVM smoke support, and VM-facing artifact inspection;
- VM runtime: native guest execution, firmware loading, storage devices,
  display/serial devices, snapshots, and host integration boundaries.

The future shape should avoid a crate that looks like a language compiler while
it is actually the machine toolbox. `native/rux-compiler` should not be carried
forward as the conceptual home for Rust-first Kraft16 tooling.

## Compatibility Policy

Do not add compatibility fallbacks during the rename. A migration step may
temporarily keep old file or command names only when the next step cannot be
made atomic, but the intended path must be explicit and the old path must remain
visibly legacy.

Format magic changes such as `RUXE` -> `K16E` are compatibility-affecting and
must be isolated from pure crate/CLI/documentation renames. The first code
slices should rename host-side organization and public terminology before
changing persisted binary formats.

## Implementation Order

1. Rename documentation and roadmap language so new work says `Kraft16`/`K16`.
2. Split or rename host tooling boundaries so the Rust-first machine toolbox is
   not presented as `rux-compiler`.
3. Rename CLI scripts and commands from `rux*` to `k16*`, keeping old names only
   as explicitly tracked legacy removal work if atomic removal is too large.
4. Rename VM crate/package surfaces from `rux-vm` to `k16-vm`.
5. Rename artifact format names and magic values in separate ABI slices:
   `RUXE`, `RUXVOL`, `RuxFS`, and snapshots.
6. Rename Rust target components from `rux16-unknown-ruxos` to
   `k16-unknown-kraftos` once the compiler fork and repo tooling can move
   together.
7. Remove old Rux language frontend/source code after Rust-authored BIOS,
   bootloader, kernel, and user-space fixtures cover the boot chain.

## Out Of Scope

This design does not implement the rename. It only fixes the accepted direction
and the order of work. It also does not require deleting all Rux language files
before the Rust boot chain is ready.

## Verification Expectations

Each implementation slice should run the narrowest relevant checks. For pure
docs/spec changes, `git diff --check` is enough. For tooling/crate changes, keep
the Rust crate tests and Rust no_core smoke path green. For VM/runtime changes,
run the focused Gradle tests through `./gradlew-sandbox`.
