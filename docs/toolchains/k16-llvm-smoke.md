# K16 LLVM Smoke

Issue: [#126](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/126)

`tools/k16-llvm-smoke.sh` verifies the first external LLVM backend path without adding LLVM-specific execution logic to the VM.

The smoke command requires the out-of-tree LLVM checkout to be built at:

```text
toolchains/Compukter-Kraft-llvm/build-k16-min/bin
```

Run:

```bash
tools/k16-llvm-smoke.sh
```

For a different LLVM build, pass the exact bin directory:

```bash
K16_LLVM_BIN_DIR=/path/to/llvm-build/bin tools/k16-llvm-smoke.sh
```

The script fails explicitly if required tools are missing. It checks that:

- `llc` registers `k16`.
- `add(i32, i32)` lowers to K16 assembly with `add r0, r1, r2`.
- LLVM emits an ELF relocatable object with `Machine: 0x5258` and `.text.k16`.
- `k16 runtime k16-startup` and `k16 link --target program` turn an LLVM-produced `main.o` into a program `K16E`.
- `k16 disasm --target program` sees the linked startup path and LLVM-produced `main`.
- LLVM emits `R_K16_CALL32` for a direct external call, and `k16 link` resolves it across LLVM-produced objects.
- LLVM lowers a volatile stack local through K16 stack adjustment, `store32`, and `load32`, then `k16 link` packages it as normal program `K16E`.
- Stack-passed arguments and indirect calls lower to the documented K16 call
  ABI; unsupported `i64` returns and varargs still fail explicitly before any
  object is produced.
