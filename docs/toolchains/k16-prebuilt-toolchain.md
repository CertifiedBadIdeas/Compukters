# K16 Prebuilt Toolchain

> Issue: [#155](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/155)

Normal mod builds consume a pinned prebuilt K16 toolchain. They must not build
LLVM, rustc, or cargo from source.

## Consumer Path

The repo pin lives in `config/k16-toolchain.json`. Gradle reads that file,
selects the current host archive, downloads it from the configured release URL,
checks SHA-256, and installs it into:

```text
~/.cache/compukter-kraft/k16-toolchains/<pin>/<host>/
```

The installed root must contain:

```text
manifest.json
bin/cargo
bin/rustc
bin/k16-ld
```

The normal entry points are still regular Gradle tasks:

```bash
./gradlew-sandbox :v1_21_1-neoforge:processResources \
  -Pk16ToolchainMode=prebuilt
./gradlew-sandbox :v1_21_1-neoforge:buildProductionUniversalJar \
  -Pk16ToolchainMode=prebuilt
```

If the release asset is missing or the checksum does not match, the build fails.
It does not build LLVM or rustc locally.

`prebuilt` is the default mode, so the property can be omitted for normal mod
builds. Passing it explicitly is useful in scripts that must document which
toolchain source they expect.

An already-unpacked prebuilt layout can be used explicitly:

```bash
./gradlew-sandbox :v1_21_1-neoforge:processResources \
  -Pk16ToolchainMode=prebuilt \
  -Pk16ToolchainDir=/absolute/path/to/k16-toolchain
```

The path must be absolute and must not be a symlink. It is validated with the
same layout rules as the prebuilt cache.

For manual shell usage, Gradle can print the selected toolchain paths:

```bash
./gradlew-sandbox :v1_21_1-neoforge:printK16ToolchainEnv
```

## Explicit Local Toolchain

Toolchain developers do not need to publish a release asset for every local
toolchain change. Use `local` mode to stage a fresh local toolchain from
already-built binaries:

```bash
./gradlew-sandbox :v1_21_1-neoforge:processResources \
  -Pk16ToolchainMode=local \
  -Pk16CargoPath=/absolute/path/to/cargo \
  -Pk16RustcPath=/absolute/path/to/rustc \
  -Pk16LdPath=/absolute/path/to/k16-ld
```

`local` mode does not download a prebuilt archive and does not accept
`k16ToolchainDir`. The three input paths are required, absolute, and non-symlink.

Firmware Gradle tasks set `RUSTC_BOOTSTRAP=1` internally because the staged
Cargo currently comes from Rust bootstrap stage0 while K16 firmware builds use
`-Zbuild-std=core`. Users should not need to provide that environment variable.

## Maintainer Publish Flow

Build the K16 toolchain in a maintainer workspace, then stage the install layout
for one host from already-built binaries:

```bash
./gradlew-sandbox :v1_21_1-neoforge:stageK16Toolchain \
  -Pk16ToolchainMode=local \
  -Pk16CargoPath=/absolute/path/to/cargo \
  -Pk16RustcPath=/absolute/path/to/rustc \
  -Pk16LdPath=/absolute/path/to/k16-ld
```

This task only copies explicit binaries. It does not build LLVM, rustc, cargo,
or `k16-ld`. `k16RustcPath` must point at a Rust bootstrap `stage1/bin/rustc`;
that stage1 sysroot must already contain matching host runtime libraries, so
Cargo can compile host build scripts while building K16 `core`.

The staged layout is:

```text
stage/
  manifest.json
  bin/cargo
  bin/rustc
  bin/k16-ld
  lib/librustc_driver-*.so
  lib/rustlib/src/rust/library/
  lib/rustlib/<host>/lib/
```

Create the archive through the Gradle package task so it uses the same pinned
host archive name and layout validation as the consumer installer:

```bash
./gradlew-sandbox :v1_21_1-neoforge:packageK16Toolchain \
  -Pk16ToolchainMode=local \
  -Pk16CargoPath=/absolute/path/to/cargo \
  -Pk16RustcPath=/absolute/path/to/rustc \
  -Pk16LdPath=/absolute/path/to/k16-ld
```

The task prints the archive path and SHA-256. Update the matching `sha256` in
`config/k16-toolchain.json`, then upload the archive to the release named by
`artifactBaseUrl`:

```bash
gh release upload k16-toolchain-k16-dev-2026-06-01 \
  k16-toolchain-k16-dev-2026-06-01-linux-x86_64.zip \
  --repo CertifiedBadIdeas/Compukter-Kraft
```

Repeat for each supported host archive. Do not change the pin to point at a
different archive without updating its SHA-256.
