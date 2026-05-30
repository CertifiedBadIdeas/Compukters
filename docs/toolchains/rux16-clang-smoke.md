# Rux16 Clang Smoke

> Toolchain: [Rux16 LLVM Submodule](rux16-llvm-submodule.md)

`tools/rux16-clang-smoke.sh` verifies the first freestanding C path for Rux16:

- `clang --target=rux16 -ffreestanding -fno-builtin -nostdlib` compiles a tiny C
  `main` into an ELF32 relocatable object.
- `rux runtime rux16-startup` and `rux link --target program` package the object
  as a normal program `RUXE`.
- `rux disasm --target program` checks the linked Rux16 code.
- `rux run` executes the `RUXE` in the VM and observes `42` as
  `debug_bytes=2a`.
- unsupported C shapes fail explicitly: `long long` return, varargs, stack
  arguments, and indirect calls.

The smoke expects a Clang-capable Rux16 LLVM build at:

```sh
toolchains/Compukter-Kraft-llvm/build-rux/bin
```

The build directory must include the Rux16 experimental backend:

```sh
cmake -S llvm -B build-rux -DLLVM_EXPERIMENTAL_TARGETS_TO_BUILD=Rux16
cmake --build build-rux --target clang llc llvm-readobj FileCheck not
```

Override it with:

```sh
RUX16_LLVM_BIN_DIR=/path/to/llvm/bin tools/rux16-clang-smoke.sh
```
