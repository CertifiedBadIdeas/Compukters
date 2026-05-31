# K16 Clang Smoke

> Toolchain: [K16 LLVM Submodule](k16-llvm-submodule.md)

`tools/k16-clang-smoke.sh` verifies the first freestanding C path for K16:

- `clang --target=k16 -ffreestanding -fno-builtin -nostdlib` compiles a tiny C
  `main` into an ELF32 relocatable object.
- `k16 runtime k16-startup` and `k16 link --target program` package the object
  as a normal program `K16E`.
- `k16 disasm --target program` checks the linked K16 code.
- `k16 run` executes the `K16E` in the VM and observes `42` as
  `debug_bytes=2a`.
- unsupported C shapes fail explicitly: `long long` return, varargs, stack
  arguments, and indirect calls.

The smoke expects a Clang-capable K16 LLVM build at:

```sh
toolchains/Compukter-Kraft-llvm/build-rux/bin
```

The build directory must include the K16 experimental backend:

```sh
cmake -S llvm -B build-rux -DLLVM_EXPERIMENTAL_TARGETS_TO_BUILD=K16
cmake --build build-rux --target clang llc llvm-readobj FileCheck not
```

Override it with:

```sh
K16_LLVM_BIN_DIR=/path/to/llvm/bin tools/k16-clang-smoke.sh
```
