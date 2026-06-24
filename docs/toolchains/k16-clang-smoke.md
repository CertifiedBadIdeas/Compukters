# K16 Clang Smoke

> Toolchain: [K16 LLVM Submodule](k16-llvm-submodule.md)

`tools/k16-clang-smoke.sh` verifies the first freestanding C path for K16:

- `clang --target=k16 -ffreestanding -fno-builtin -nostdlib` compiles a tiny C
  `main` into an ELF32 relocatable object.
- `k16 runtime k16-startup` and `k16 link --target program` package the object
  as a normal program `K16E`.
- `k16 disasm --target program --start 0x8000 --count ...` checks the linked
  K16 code without decoding any following non-code payload bytes.
- `k16 run` executes the `K16E` in the VM and observes `42` as
  `debug_bytes=2a`.
- stack-passed arguments and indirect calls lower to the documented K16 call
  ABI; unsupported C shapes such as `long long` return and varargs still fail
  explicitly.

The bundled source-built-dev Gradle path also uses Clang now. `buildK16Llvm`
enables the LLVM `clang` project, tracks both `llvm/` and `clang/` source
inputs, and the NeoForge firmware build compiles production `/bin/cat.kx` and
`/bin/write.kx` from `rust/guest/c/coreutils`. That hosted C path uses
`k16-startup.o` for the real K16 entry ABI, the libc-lite
`rust/guest/c/libc/crt0.c` adapter for ordinary
`main(int argc, char **argv)`, `rust/guest/c/libc/syscalls.c` for ABI wrappers
such as `open(path, flags)`, and minimal standard-shaped libc-lite headers.

The Clang K16 target data layout must match the backend/Rust target layout:

```text
e-p:32:32-i32:32-i64:64-n32-S64
```

The smoke expects a Clang-capable K16 LLVM build at:

```sh
.toolchain/build/llvm/k16/bin
```

The build directory must include the K16 experimental backend:

```sh
cmake -S toolchains/Compukter-Kraft-llvm/llvm -B .toolchain/build/llvm/k16 -DLLVM_EXPERIMENTAL_TARGETS_TO_BUILD=K16
cmake --build .toolchain/build/llvm/k16 --target clang llc llvm-readobj FileCheck not
```

Override it with:

```sh
K16_LLVM_BIN_DIR=/path/to/llvm/bin tools/k16-clang-smoke.sh
```
