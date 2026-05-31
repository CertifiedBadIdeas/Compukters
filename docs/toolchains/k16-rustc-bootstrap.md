# K16 rustc Bootstrap Path

> Issue: [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132)
>
> Strategy: [K16 Rust-First Language Strategy](rux16-language-strategy.md)

## Current Evidence

The local host compiler is not enough for the K16 Rust smoke:

```text
rustc 1.94.1
LLVM version: 21.1.8
```

The tracked LLVM submodule build contains the Rux16 backend:

```text
LLVM version 23.0.0git
Registered Targets:
  rux16  - Rux16 32-bit
```

The Rust source fork is tracked as a repository submodule:

```text
toolchains/Compukter-Kraft-rust
  url: git@github.com:CertifiedBadIdeas/Compukter-Kraft-rust.git
  branch: rux16
  commit: 8fba61f0e772bd97c4c27b67bbb090db1f4f4210
```

Fork branch policy:

- `main` mirrors upstream `rust-lang/rust` and is synced manually;
- `rux16` contains Compukter-Kraft Rust/Rux16 toolchain changes;
- the main repository still pins the exact submodule commit for reproducible
  builds.

After fixing `tools/k16-unknown-kraftos.json` so `target-pointer-width` is a
number, the current host `rustc --target tools/k16-unknown-kraftos.json` reaches
the real blocker:

```text
error: could not create LLVM TargetMachine for triple: rux16:
No available targets are compatible with triple "rux16"
```

The custom stage1 rustc now builds against that LLVM and can pass the no_core
smoke:

```text
rustc 1.98.0-dev
LLVM version: 23.0.0
Rust no_core object checks passed
KX link and execution checks passed
K16 Rust no_core smoke passed
```

## Upstream Rust Guidance

Rust's compiler development guide describes two relevant paths for a new target:

- replace the Rust repository's `src/llvm-project` submodule with a custom LLVM
  fork when a target needs LLVM changes;
- or configure `bootstrap.toml` to use a prebuilt LLVM through `llvm-config`.

The rustc book also documents that custom target specifications are unstable
and should be pinned to the compiler version that consumes them.

References:

- <https://rustc-dev-guide.rust-lang.org/building/new-target.html>
- <https://rustc-dev-guide.rust-lang.org/building/how-to-build-and-run>
- <https://doc.rust-lang.org/nightly/rustc/targets/custom.html>

## Viable Local Paths

### Preferred: Rust Source With K16 LLVM Submodule

Use `toolchains/Compukter-Kraft-rust` as the tracked Rust source tree for
toolchain work. In that Rust source tree, point `src/llvm-project` at the
Compukter-Kraft LLVM fork that contains the Rux16 backend. Then build a stage1
rustc and link it through rustup as a local custom toolchain.

This is the most reproducible path because the Rust source tree records the
LLVM commit it was built against.

Expected shape:

```text
toolchains/Compukter-Kraft-rust
  src/llvm-project -> Compukter-Kraft-llvm commit with Rux16
  bootstrap.toml
  build/<host>/stage1/bin/rustc
```

Initialize the Rust source checkout with:

```bash
git submodule update --init --recursive toolchains/Compukter-Kraft-rust
```

The submodule checkout alone is not a successful rustc build. The next #145
slice is to add the bootstrap configuration/probe that proves this Rust source
can build against the K16 LLVM backend.

### Fast Probe: Rust Source With Prebuilt Rux16 LLVM

Use a Rust source checkout and configure bootstrap to use the already-built
LLVM from `toolchains/Compukter-Kraft-llvm/build-rux-min/bin/llvm-config`.

This may avoid rebuilding LLVM, but it depends on API compatibility between
the selected Rust source revision and the prebuilt LLVM 23.0.0git checkout.
If rustc and LLVM APIs do not match, this path fails during compiler build.

Run the workspace probe first:

```bash
tools/k16-rustc-bootstrap-probe.sh
```

The probe is intentionally strict. It requires:

- the Rust source checkout at `toolchains/Compukter-Kraft-rust`;
- the Rust checkout to be on branch `rux16`;
- `llvm-config --targets-built` to contain `Rux16`;
- Rust bootstrap entrypoint `x.py` to be present and runnable.

It prints a temporary `bootstrap.toml` path plus dry-run and build commands for
`./x.py build --config <generated-config> compiler/rustc`.

Even `--dry-run` may download and build Rust bootstrap stage0 support files.
That is expected bootstrap behavior, not a Rux16 codegen proof.

The default build directory is `toolchains/Compukter-Kraft-rust/build/rux16`,
which is ignored by the Rust repository.

The Rux16 Rust fork changes needed for the first stage1 smoke are:

- register the `rux16` LLVM component and initialize the K16 LLVM target
  inside `rustc_llvm`;
- add minimal Rux16 C ABI lowering in `rustc_target`.

Expected bootstrap direction:

```toml
[target.x86_64-unknown-linux-gnu]
llvm-config = "/absolute/path/to/toolchains/Compukter-Kraft-llvm/build-rux-min/bin/llvm-config"
```

### Not Enough: Nightly rustc Only

Installing nightly is useful for unstable `no_core` and custom target JSON
flags, but it will still fail if that rustc's bundled LLVM does not include the
Rux16 backend.

## First Success Criterion

The first useful custom rustc result is not `core` or `std`. It is only:

```text
K16_RUSTC=/path/to/custom/stage1/rustc \
K16_LLVM_BIN_DIR=/path/to/rux16/llvm/bin \
tools/k16-rust-nocore-smoke.sh
```

Expected final output:

```text
Rust no_core object checks passed
KX link and execution checks passed
K16 Rust no_core smoke passed
```

Only after that should the project move to Rust target runtime helpers,
`panic=abort`, and `core`/`no_std`.

## Follow-Up

- [#145](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/145):
  prepare the custom rustc workspace/build path needed before #132 can pass.
