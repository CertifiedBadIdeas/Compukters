# Prebuilt K16 Toolchain Resolution

> Issue: [#155](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/155)

## Context

The bundled Rust BIOS, bootloader, and kernel build path currently needs a
custom K16 Rust toolchain. Requiring every developer or user to build LLVM,
rustc, cargo, and the K16 linker from source makes normal mod builds too heavy
and ties the mod build to toolchain-maintainer work.

The normal build path should consume a pinned prebuilt toolchain artifact. A
source-built toolchain remains useful for maintainers, but it must be selected
explicitly and must not become a fallback path.

## Goals

- Keep one repository pin that identifies the K16 toolchain expected by normal
  mod builds.
- Resolve toolchain binaries from one installed prebuilt layout.
- Allow local source-built toolchains only through explicit configuration.
- Fail clearly when the selected toolchain is missing or invalid.
- Avoid symlink-based `current` or `latest` selection.

## Repository Pin

The repository stores the selected toolchain contract in
`config/k16-toolchain.json`. The file contains:

- `schemaVersion`: the manifest schema understood by the build;
- `pin`: the logical prebuilt toolchain version expected by the repo;
- `requiredExecutables`: paths that must exist inside an installed toolchain;
- `hosts`: supported host platform identifiers and their future archive names.
- `artifactBaseUrl`: the release/download base URL used by the Gradle installer.
- per-host `sha256`: the expected archive checksum.

This file is the source of truth for the mod build. It is intentionally small
and stable so Gradle, shell scripts, and manual users can all consume the same
pin.

## Installed Layout

The default installed layout is:

```text
.toolchain/
  build/
    llvm/k16-min/
    llvm/k16/
    rust/k16/
    cargo/k16-tools/
    cargo/k16-vm/
  k16/
    <pin>/
      <host>/
        manifest.json
        bin/
          cargo
          rustc
          k16-ld
```

`toolchains/Compukter-Kraft-llvm` and `toolchains/Compukter-Kraft-rust` are
source checkouts only. LLVM, Rust bootstrap, and host Cargo build outputs belong
under `.toolchain/build`, while `.toolchain/k16/<pin>/<host>` is the clean
installed toolchain root consumed by Gradle firmware builds.

The archive format is zip for every host in the Gradle-installed path. Zip is
used because Gradle can unpack it directly without adding another decompression
toolchain dependency to normal mod builds.

`downloadK16ToolchainArchive` downloads the host archive from
`artifactBaseUrl/archive` into the root build directory.
`installK16Toolchain` verifies the archive SHA-256 before unpacking it into the
repo-local `.toolchain` layout, then validates the installed root before
firmware tasks use it.

## Explicit Local Toolchain

A source-built local toolchain can be used only by explicitly pointing Gradle at
already-built local toolchain binaries:

```text
-Pk16ToolchainMode=local
-Pk16CargoPath=/absolute/path/to/cargo
-Pk16RustcPath=/absolute/path/to/rustc
-Pk16LdPath=/absolute/path/to/k16-ld
```

`stageK16Toolchain` copies those explicit binaries and required runtime libs
into the same `.toolchain/k16/<pin>/<host>/` layout used by the prebuilt path.
This keeps local toolchain development explicit while keeping the firmware build
code identical after preparation.

When `k16ToolchainMode=local` is set, download/install tasks are skipped. The
staged layout is still validated by `prepareK16Toolchain` before Cargo is
executed.

## Symlink Policy

Toolchain resolution does not use symlinks. There is no `current`, `latest`, or
implicit path discovery. The resolver rejects a symlinked toolchain root or
symlinked required executable because those paths hide the selected toolchain
identity and make cache failures ambiguous.

## Failure Model

Missing or unsupported toolchains are hard configuration errors:

- unsupported host id: fail before running Cargo;
- missing cache directory: fail with the expected path and install guidance;
- missing manifest or required executable: fail with the exact missing file;
- symlinked root or executable: fail and require an explicit real path.

There is no fallback to local source builds, host rustc, host cargo, debug
artifacts, or another installed K16 toolchain.

## First Implementation Slice

- Add `config/k16-toolchain.json`.
- Add a top-level Gradle resolver used by BIOS, bootloader, and kernel firmware
  tasks.
- Replace ad hoc per-module toolchain wiring with explicit `prebuilt` and
  `local` toolchain modes.
- Add Gradle download/install tasks for pinned zip archives.
- Add Gradle local staging and packaging tasks that write to `.toolchain`.
- Verify archive SHA-256 before unpacking.
- Document the maintainer publish flow.

## Verification

- `./gradlew-sandbox :v1_21_1-neoforge:compileKotlin`
- `./gradlew-sandbox :build-scripts:test --tests K16PrebuiltToolchainBuildScriptTest`
- `./gradlew-sandbox :downloadK16ToolchainArchive -Pk16ToolchainArchiveUrl=file:///tmp/missing.zip`
  must fail before Cargo starts and tell the user to publish a prebuilt archive
  or pass `-Pk16ToolchainDir`.
- `./gradlew-sandbox :installK16Toolchain -Pk16ToolchainArchiveUrl=file:///<tmp-archive>.zip`
  with a mismatched archive checksum must fail before unpacking into `.toolchain`.
- `./gradlew-sandbox :stageK16Toolchain -Pk16ToolchainMode=local ...`
- `./gradlew-sandbox :v1_21_1-neoforge:processResources -Pk16ToolchainMode=local ...`
