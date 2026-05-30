# Rux16 LLVM Submodule

> Issue: [#136](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/136)

The Rux16 LLVM fork is tracked as a git submodule at:

```text
toolchains/Compukter-Kraft-llvm
```

It is an essential toolchain input for Rux16 LLVM, Clang, and future Rust
target work. The main repository tracks the LLVM fork as a gitlink, not as
copied source files or build outputs.

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
5f56e021a651aa59b03d873ea749cf3eb15ed398
```

Local LLVM build directories such as `build-rux/` and `build-rux-min/` remain
inside the submodule checkout and are not tracked by the main repository.
