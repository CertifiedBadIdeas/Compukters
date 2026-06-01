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

This file is the source of truth for the mod build. It is intentionally small
and stable so Gradle, shell scripts, and manual users can all consume the same
pin.

## Installed Layout

The default installed layout is:

```text
~/.cache/compukter-kraft/k16-toolchains/
  <pin>/
    <host>/
      manifest.json
      bin/
        cargo
        rustc
        k16-ld
```

The archive format is zip for every host in the Gradle-installed path. Zip is
used because Gradle can unpack it directly without adding another decompression
toolchain dependency to normal mod builds.

`downloadK16ToolchainArchive` downloads the host archive from
`artifactBaseUrl/archive` into the module build directory.
`installK16Toolchain` unpacks it into the cache layout and validates the
installed root before firmware tasks use it.

## Explicit Local Toolchain

A source-built local toolchain can be used only by explicitly pointing Gradle at
an installed-layout directory:

```text
-Pk16ToolchainDir=/absolute/path/to/k16-toolchain
```

That directory must contain the same `manifest.json` and `bin/*` files as a
prebuilt install. This keeps local toolchain development explicit while keeping
the firmware build code identical for prebuilt and local sources.

When `k16ToolchainDir` is set, the install task is skipped. The selected local
directory is still validated by the firmware tasks before Cargo is executed.

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
- Add a Gradle resolver used by BIOS, bootloader, and kernel firmware tasks.
- Replace the three large per-binary environment variables with one explicit
  optional `k16ToolchainDir` Gradle property.
- Add Gradle download/install tasks for pinned zip archives.
- Keep publishing and checksum verification out of this slice.

## Verification

- `./gradlew-sandbox :v1_21_1-neoforge:compileKotlin`
- `./gradlew-sandbox :build-scripts:test --tests K16PrebuiltToolchainBuildScriptTest`
- `./gradlew-sandbox :v1_21_1-neoforge:downloadK16ToolchainArchive -Pk16ToolchainArchiveUrl=file:///tmp/missing.zip`
  must fail before Cargo starts and tell the user to publish a prebuilt archive
  or pass `-Pk16ToolchainDir`.
- `./gradlew-sandbox :v1_21_1-neoforge:installK16Toolchain -Pk16ToolchainCacheDir=<tmp-cache> -Pk16ToolchainArchiveUrl=file:///<tmp-archive>.zip`
  must download, unpack, and validate a correctly shaped archive.
