# K16 LLVM Submodule

> Retired product direction: [ADR 0001](../architecture-decisions/0001-retire-k16-adopt-rv64.md)
> ends new K16 backend work. The fork remains temporarily available only for
> reproducing the current implementation and migration comparisons.

> Issue: [#136](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/136)

The K16 LLVM fork is tracked as a git submodule at:

```text
toolchains/Compukter-Kraft-llvm
```

It is an essential toolchain input for K16 LLVM, Clang, and future Rust target
work. The main repository tracks the LLVM fork as a gitlink, not as copied
source files or build outputs.

Fork branch policy:

- `main` mirrors upstream `llvm/llvm-project` and is synced manually;
- `k16` contains Compukter-Kraft LLVM/K16 changes on the current upstream LLVM
  line;
- `k16-rust-pinned` contains the K16 backend on top of the LLVM commit pinned
  by `toolchains/Compukter-Kraft-rust` for the current Rust bootstrap work;
- the main repository still pins the exact submodule commit for reproducible
  builds.

Initialize it after cloning:

```bash
git submodule update --init toolchains/Compukter-Kraft-llvm
```

Update it after pulling main repository changes:

```bash
git submodule update --init --recursive toolchains/Compukter-Kraft-llvm
```

The current tracked commit is:

```text
23746a91aea3176947f9792201ca1c581a049580
```

That commit is carried by the fork branch `k16-rust-pinned` and is based on the
Rust-pinned LLVM commit:

```text
08c84e69a84d95936296dfcab0e38b34100725d5
```

Local LLVM build directories such as `build-k16/` and `build-k16-min/` remain
inside the submodule checkout and are not tracked by the main repository.
