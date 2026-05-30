# Rux16 rustc Bootstrap Path

> Issue: [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132)
>
> Strategy: [Rux16 Rust-First Language Strategy](rux16-language-strategy.md)

## Current Evidence

The local host compiler is not enough for the Rux16 Rust smoke:

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
  commit: c58275e0369d09fc3959b8ba87dcbcbe73797465
```

Fork branch policy:

- `main` mirrors upstream `rust-lang/rust` and is synced manually;
- `rux16` contains Compukter-Kraft Rust/Rux16 toolchain changes;
- the main repository still pins the exact submodule commit for reproducible
  builds.

After fixing `tools/rux16-unknown-ruxos.json` so `target-pointer-width` is a
number, the current host `rustc --target tools/rux16-unknown-ruxos.json` reaches
the real blocker:

```text
error: could not create LLVM TargetMachine for triple: rux16:
No available targets are compatible with triple "rux16"
```

So the remaining #132 blocker is not the target JSON. It is that rustc must be
built with an LLVM that includes the Rux16 target.

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

### Preferred: Rust Source With Rux16 LLVM Submodule

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
can build against the Rux16 LLVM backend.

### Fast Probe: Rust Source With Prebuilt Rux16 LLVM

Use a Rust source checkout and configure bootstrap to use the already-built
LLVM from `toolchains/Compukter-Kraft-llvm/build-rux/bin/llvm-config`.

This may avoid rebuilding LLVM, but it depends on API compatibility between
the selected Rust source revision and the prebuilt LLVM 23.0.0git checkout.
If rustc and LLVM APIs do not match, this path fails during compiler build.

Expected bootstrap direction:

```toml
[target.x86_64-unknown-linux-gnu]
llvm-config = "/absolute/path/to/toolchains/Compukter-Kraft-llvm/build-rux/bin/llvm-config"
```

### Not Enough: Nightly rustc Only

Installing nightly is useful for unstable `no_core` and custom target JSON
flags, but it will still fail if that rustc's bundled LLVM does not include the
Rux16 backend.

## First Success Criterion

The first useful custom rustc result is not `core` or `std`. It is only:

```text
RUX16_RUSTC=/path/to/custom/stage1/rustc \
RUX16_LLVM_BIN_DIR=/path/to/rux16/llvm/bin \
tools/rux16-rust-nocore-smoke.sh
```

Expected final output:

```text
Rust no_core object checks passed
RUXE link and execution checks passed
Rux16 Rust no_core smoke passed
```

Only after that should the project move to Rust target runtime helpers,
`panic=abort`, and `core`/`no_std`.

## Follow-Up

- [#145](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/145):
  prepare the custom rustc workspace/build path needed before #132 can pass.
