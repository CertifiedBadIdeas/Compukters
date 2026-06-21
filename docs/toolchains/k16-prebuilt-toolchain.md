# K16 Prebuilt Toolchain

> Issue: [#155](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/155)

Normal mod builds consume a pinned prebuilt K16 toolchain. They must not build
LLVM, rustc, or cargo from source.

## Consumer Path

The repo pin lives in `config/k16-toolchain.json`. Gradle reads that file,
selects the current host archive, downloads it from the configured release URL,
checks SHA-256, and installs it into:

```text
.toolchain/k16/<pin>/<host>/
```

The installed root must contain:

```text
manifest.json
bin/cargo
bin/rustc
bin/k16-ld
bin/k16
```

## Workspace Layout

The repo uses one ignored `.toolchain` workspace for both source-built outputs
and installed toolchains:

```text
.toolchain/
  build/
    llvm/k16-min/
    llvm/k16/
    rust/k16/
    cargo/k16-tools/
    cargo/k16-vm/
  k16/
    <pin>/<host>/
```

`toolchains/Compukter-Kraft-llvm` and `toolchains/Compukter-Kraft-rust` remain
tracked source checkouts. Their build trees should live under `.toolchain/build`
instead of inside the source checkouts. The clean install layout consumed by mod
and firmware builds remains `.toolchain/k16/<pin>/<host>/`.

The root Gradle `clean` task depends on `cleanWorkspace`, which deletes
repo-local `build/` and `target/` directories but preserves `.toolchain`. It
also does not descend into nested Git checkout roots or submodules, because
toolchain source trees can contain tracked fixtures named `build` or `target`.
Use it when you want to remove generated JVM and Rust build outputs without
discarding installed or source-built K16 toolchains:

```bash
./gradlew-sandbox clean
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
./gradlew-sandbox :printK16ToolchainEnv
```

## Development Host Tools

When only `rust/host/k16-tools` or its local Rust dependencies changed, use the
development sandbox wrapper:

```bash
./gradlew-sandbox-dev :v1_21_1-neoforge:processResources
```

It is equivalent to running `./gradlew-sandbox` with
`-Pk16ToolchainMode=source-host-tools` appended after the user-provided
arguments. This mode prepares the normal pinned prebuilt toolchain first, then
rebuilds the checked-out K16 host tools and overlays only:

```text
bin/k16-ld
bin/k16
```

The pinned `cargo`, `rustc`, sysroot, and host runtime libraries remain from the
prebuilt toolchain. This keeps normal builds reproducible while letting local
firmware builds pick up fresh linker and volume-tool changes without publishing
a new prebuilt archive.

The source-host-tools install layout is separate from the pinned prebuilt
layout:

```text
.toolchain/k16/<pin>-source-host-tools/<host>/
```

Regular `prebuilt` builds continue to resolve `.toolchain/k16/<pin>/<host>/`.

The explicit form is:

```bash
./gradlew-sandbox :v1_21_1-neoforge:processResources \
  -Pk16ToolchainMode=source-host-tools
```

`source-host-tools` does not accept `k16ToolchainDir`; it always stages a
dedicated dev layout from the pinned `.toolchain/k16/<pin>/<host>/` workspace.

## Explicit Local Toolchain

Toolchain developers do not need to publish a release asset for every local
toolchain change. Use `local` mode to stage a fresh local toolchain from
already-built binaries:

```bash
./gradlew-sandbox :v1_21_1-neoforge:processResources \
  -Pk16ToolchainMode=local \
  -Pk16CargoPath=/absolute/path/to/cargo \
  -Pk16RustcPath=/absolute/path/to/rustc \
  -Pk16LdPath=/absolute/path/to/k16-ld \
  -Pk16ToolPath=/absolute/path/to/k16
```

`local` mode does not download a prebuilt archive and does not accept
`k16ToolchainDir`. The four input paths are required, absolute, and non-symlink.
After staging, the same pinned `.toolchain/k16/<pin>/<host>/` install layout is
available to every Gradle module in the repo.

Firmware Gradle tasks set `RUSTC_BOOTSTRAP=1` internally because the staged
Cargo currently comes from Rust bootstrap stage0 while K16 firmware builds use
`-Zbuild-std=core`. Users should not need to provide that environment variable.

## Maintainer Publish Flow

Maintainers can build the K16 toolchain from the checked-out source repositories
directly into `.toolchain/build`:

```bash
./gradlew-sandbox :prepareBuiltK16Toolchain
```

This task depends on `buildK16ToolchainFromSource`, validates the staged
install layout, and prints `K16_CARGO`, `K16_RUSTC`, `K16_LD`, and `K16_TOOL`
shell exports.
The lower-level source build task builds:

- patched LLVM in `.toolchain/build/llvm/k16-min` with the native host LLVM backend plus K16;
- patched Rust bootstrap outputs in `.toolchain/build/rust/k16`, including the
  stage1 compiler and host `library/std`;
- K16 host tools in `.toolchain/build/cargo/k16-tools`.

Source toolchain builds run in parallel by default using the available CPU
count. Override all K16 source build jobs with `-Pk16BuildJobs=<jobs>`, or tune
individual phases with `-Pk16LlvmBuildJobs=<jobs>`,
`-Pk16RustBuildJobs=<jobs>`, and `-Pk16HostToolsBuildJobs=<jobs>` when a machine
needs a tighter CPU or memory limit.

The source checkouts are:

```text
toolchains/Compukter-Kraft-llvm
toolchains/Compukter-Kraft-rust
```

`buildK16ToolchainFromSource` runs the strict Rust/LLVM compatibility probe
before `x.py` builds `compiler/rustc` and host `library/std`. The staged
toolchain is then copied into the same clean install layout used by prebuilt
consumers:

```text
.toolchain/k16/<pin>/<host>/
```

Packaging a source-built staged toolchain uses a dedicated task:

```bash
./gradlew-sandbox :packageBuiltK16Toolchain
```

It writes the pinned host archive name from `config/k16-toolchain.json` and
prints the resulting SHA-256. Release archives are produced only from this
source-built path.

Before packaging or publishing a new archive, run the relevant checks from
`docs/abi/k16-abi-conformance-matrix.md`. At minimum, backend or Rust target
changes should pass the K16 lit suite and the host-tool smoke tests:

```bash
.toolchain/build/llvm/k16-min/bin/llvm-lit toolchains/Compukter-Kraft-llvm/llvm/test/CodeGen/K16
```

```bash
cd rust/host/k16-tools
cargo test --test k16_runtime_cli
cargo test --test k16_rust_smoke_artifacts
```

If the toolchain was built outside Gradle, stage the install layout for one host
from already-built binaries:

```bash
./gradlew-sandbox :stageK16Toolchain \
  -Pk16ToolchainMode=local \
  -Pk16CargoPath=/absolute/path/to/cargo \
  -Pk16RustcPath=/absolute/path/to/rustc \
  -Pk16LdPath=/absolute/path/to/k16-ld \
  -Pk16ToolPath=/absolute/path/to/k16
```

This task only copies explicit binaries. It does not build LLVM, rustc, cargo,
`k16-ld`, or `k16`. `k16RustcPath` must point at a Rust bootstrap
`stage1/bin/rustc`; that stage1 sysroot must already contain matching host
runtime libraries, so Cargo can compile host build scripts while building K16
`core`.

Bundled firmware tasks use the staged `bin/k16-ld` linker and `bin/k16` volume
CLI from the prepared toolchain. They do not build or run `k16-tools` from the
repository during normal mod resource generation.

The staged layout is:

```text
.toolchain/k16/<pin>/<host>/
  manifest.json
  bin/cargo
  bin/rustc
  bin/k16-ld
  bin/k16
  lib/librustc_driver-*.so
  lib/rustlib/src/rust/library/
  lib/rustlib/<host>/lib/
```

Do not publish archives from ad-hoc local binaries. Build and package through
`packageBuiltK16Toolchain`, update the matching `sha256` in
`config/k16-toolchain.json`, then upload the archive to the release named by
`artifactBaseUrl`:

```bash
gh release upload k16-toolchain-k16-dev-2026-06-13 \
  k16-toolchain-k16-dev-2026-06-13-linux-x86_64.zip \
  --repo CertifiedBadIdeas/Compukter-Kraft
```

Repeat for each supported host archive. Do not change the pin to point at a
different archive without updating its SHA-256.
