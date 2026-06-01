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
./gradlew-sandbox :v1_21_1-neoforge:processResources
./gradlew-sandbox :v1_21_1-neoforge:buildProductionUniversalJar
```

If the release asset is missing or the checksum does not match, the build fails.
It does not build LLVM or rustc locally.

## Explicit Local Toolchain

Toolchain developers can bypass the prebuilt cache with an explicit installed
layout:

```bash
./gradlew-sandbox :v1_21_1-neoforge:processResources \
  -Pk16ToolchainDir=/absolute/path/to/k16-toolchain
```

The path must be absolute and must not be a symlink. It is validated with the
same layout rules as the prebuilt cache.

For manual shell usage, Gradle can print the selected toolchain paths:

```bash
./gradlew-sandbox :v1_21_1-neoforge:printK16ToolchainEnv
```

## Maintainer Publish Flow

Build the K16 toolchain in a maintainer workspace, then stage the install layout
for one host:

```text
stage/
  manifest.json
  bin/cargo
  bin/rustc
  bin/k16-ld
```

Create the archive through the Gradle package task so it uses the same pinned
host archive name and layout validation as the consumer installer:

```bash
./gradlew-sandbox :v1_21_1-neoforge:packageK16Toolchain \
  -Pk16ToolchainDir=/absolute/path/to/stage
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
