# Rux16 LLVM Smoke

Issue: [#126](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/126)

`tools/rux16-llvm-smoke.sh` verifies the first external LLVM backend path without adding LLVM-specific execution logic to the VM.

The smoke command requires the out-of-tree LLVM checkout to be built at:

```text
toolchains/Compukter-Kraft-llvm/build-rux-min/bin
```

Run:

```bash
tools/rux16-llvm-smoke.sh
```

For a different LLVM build, pass the exact bin directory:

```bash
RUX16_LLVM_BIN_DIR=/path/to/llvm-build/bin tools/rux16-llvm-smoke.sh
```

The script fails explicitly if required tools are missing. It checks that:

- `llc` registers `rux16`.
- `add(i32, i32)` lowers to Rux16 assembly with `add r0, r1, r2`.
- LLVM emits an ELF relocatable object with `Machine: 0x5258` and `.text.rux16`.
- `rux runtime rux16-startup` and `rux link --target program` turn an LLVM-produced `main.o` into a program `RUXE`.
- `rux disasm --target program` sees the linked startup path and LLVM-produced `main`.
