# K16 rustc Bootstrap Path

> Issue: [#132](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/132)
>
> Strategy: [K16 Rust-First Language Strategy](k16-language-strategy.md)

## Current Evidence

The local host compiler is not enough for the K16 Rust smoke:

```text
rustc 1.94.1
LLVM version: 21.1.8
```

The tracked LLVM submodule build contains the K16 backend:

```text
LLVM version 23.0.0git
Registered Targets:
  k16  - K16 32-bit
```

The Rust source fork is tracked as a repository submodule:

```text
toolchains/Compukter-Kraft-rust
  url: git@github.com:CertifiedBadIdeas/Compukter-Kraft-rust.git
  branch: k16
  commit: d22824cba4ff01b6156aa5a4cd411859a693fcda
```

Fork branch policy:

- `main` mirrors upstream `rust-lang/rust` and is synced manually;
- `k16` contains Compukter-Kraft Rust/K16 toolchain changes;
- the main repository still pins the exact submodule commit for reproducible
  builds.

After fixing `tools/k16-unknown-kraftos.json` so `target-pointer-width` is a
number, the current host `rustc --target tools/k16-unknown-kraftos.json` reaches
the real blocker:

```text
error: could not create LLVM TargetMachine for triple: k16:
No available targets are compatible with triple "k16"
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

## Intended Local Path

Use `toolchains/Compukter-Kraft-rust` as the tracked Rust source tree for
toolchain work. Use `toolchains/Compukter-Kraft-llvm` as the tracked LLVM fork
that contains the K16 backend. The LLVM fork branch used for Rust bootstrap must
be based on the LLVM commit pinned by the Rust source tree's `src/llvm-project`
gitlink; do not initialize or replace that Rust submodule as the build source.

Rust bootstrap is configured to use the already-built K16 LLVM through
`llvm-config`:

```text
toolchains/Compukter-Kraft-llvm/build-k16-min/bin/llvm-config
```

This keeps one K16 LLVM source of truth in `Compukter-Kraft-llvm` while still
proving API compatibility against the Rust-pinned LLVM base.

Expected shape:

```text
toolchains/Compukter-Kraft-rust
  src/llvm-project -> Rust-pinned LLVM commit, left uninitialized
  bootstrap.toml
  build/<host>/stage1/bin/rustc
toolchains/Compukter-Kraft-llvm
  branch: k16-rust-pinned
  build-k16-min/bin/llvm-config
```

Initialize the Rust source checkout with:

```bash
git submodule update --init --recursive toolchains/Compukter-Kraft-rust
```

The source checkout alone is not a successful rustc build. A stage1 rustc must
still be rebuilt after the K16 LLVM fork is aligned to the Rust-pinned LLVM
base.

Run the workspace probe first:

```bash
tools/k16-rustc-bootstrap-probe.sh
```

The probe is intentionally strict. It requires:

- the Rust source checkout at `toolchains/Compukter-Kraft-rust`;
- the Rust checkout to be on branch `k16`;
- `llvm-config --targets-built` to contain `K16`;
- the K16 LLVM source checkout to contain the Rust-pinned LLVM commit from
  `src/llvm-project`;
- `git merge-base --is-ancestor` to prove K16 LLVM `HEAD` is based on that
  Rust-pinned LLVM commit;
- the K16 LLVM bin directory to contain the LLVM tools copied by Rust bootstrap
  into the stage1 sysroot;
- Rust bootstrap entrypoint `x.py` to be present and runnable.

It prints a temporary `bootstrap.toml` path plus dry-run and build commands for
`./x.py build --config <generated-config> compiler/rustc`.

Even `--dry-run` may download and build Rust bootstrap stage0 support files.
That is expected bootstrap behavior, not a K16 codegen proof.

The default build directory is `toolchains/Compukter-Kraft-rust/build/k16`,
which is ignored by the Rust repository.

The K16 Rust fork changes needed for the first stage1 smoke are:

- register the `k16` LLVM component and initialize the K16 LLVM target
  inside `rustc_llvm`;
- add minimal K16 C ABI lowering in `rustc_target`.

Expected bootstrap direction:

```toml
[target.x86_64-unknown-linux-gnu]
llvm-config = "/absolute/path/to/toolchains/Compukter-Kraft-llvm/build-k16-min/bin/llvm-config"
```

### Not Enough: Nightly rustc Only

Installing nightly is useful for unstable `no_core` and custom target JSON
flags, but it will still fail if that rustc's bundled LLVM does not include the
current K16 backend.

## First Success Criterion

The first useful custom rustc result is not `core` or `std`. It is only:

```text
K16_RUSTC=/path/to/custom/stage1/rustc \
K16_LLVM_BIN_DIR=/path/to/k16/llvm/bin \
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

The next core-specific proof is:

```text
K16_RUSTC=/path/to/custom/stage1/rustc \
K16_CARGO=/path/to/nightly/cargo \
K16_LLVM_BIN_DIR=/path/to/k16/llvm/bin \
tools/k16-rust-core-smoke.sh
```

That path uses `-Z build-std=core` and `-Z json-target-spec`, and is
intentionally limited to `core` without `alloc` or hosted `std`.

With the updated `k16` Rust and LLVM branches plus a rebuilt stage1 host `std`
sysroot, this path reaches K16 backend codegen for `core`. The current blocker
is missing lowering for the wider Rust `core` surface, including some
compiler-builtins library call operations. Rust jump tables are disabled for the
current ABI slice with `-Cjump-tables=no`.

## Follow-Up

- [#145](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/145):
  prepare the custom rustc workspace/build path needed before #132 can pass.
- [#148](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/148):
  build the K16 Rust `core` sysroot path.
