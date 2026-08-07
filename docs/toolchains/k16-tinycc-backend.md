# K16 TinyCC Host Backend

> Issue: [#464](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/464)

The K16 TinyCC backend is a host-running cross-compiler proof. It compiles a
deliberately bounded C subset into K16 relocatable objects quickly enough to be
a candidate for future interactive tooling. It is not the production KraftOS
compiler and it does not run inside KraftOS.

## Source And Build Boundary

The fork is pinned as the submodule `toolchains/Compukter-Kraft-tinycc`. Its
`main` branch preserves the imported upstream TinyCC history, while the `k16`
branch starts at upstream commit
`64552b3faa39ee7948a9ea21bfcc11045b90c70d` and owns the target changes. Gradle
never downloads or substitutes another TinyCC checkout.

Build the pinned host compiler with:

```bash
./gradlew-sandbox-dev-parallel buildK16TinyCc
```

The installed executable is:

```text
.toolchain/build/tinycc/k16/bin/tcc-k16
```

It reports `k16-unknown-kraftos`. The compiler is built out of tree under
`.toolchain/build/tinycc/k16/build`; a missing submodule is a hard error rather
than a fallback to a system `tcc`.

## Object And Linker Ownership

`tcc-k16 -c` emits little-endian ELF32 `ET_REL` objects for machine `0x5258`.
K16 uses explicit RELA records and the established relocation numbers
`R_K16_ABS32`, `R_K16_CALL32`, and `R_K16_BRANCH4`. Executable text is emitted
in two-byte-aligned `.text.k16`; constants use `.rodata`; writable data and
zero-filled data use `.data` and `.bss`.

TinyCC stops at the object boundary. The existing `k16 link` implementation is
the only owner of final K16E programs and shared-object linkage. TinyCC final
linking, a second K16E writer, and an implicit host linker path are explicitly
out of scope.

## Proven C Subset

The checked-in corpus under `tools/fixtures/k16-tinycc` proves:

- scalar 8-, 16-, and 32-bit integer and pointer values;
- narrow loads, stores, casts, signed extension, and unsigned extension;
- local variables, aligned stack frames, local arrays, and pointer arithmetic;
- integer arithmetic, bitwise operations, shifts, comparisons, division, and
  remainder;
- structured control flow through conditions, loops, and switches;
- scalar direct and indirect calls, with `r1` through `r3` register arguments,
  caller-owned stack arguments, and a scalar return in `r0`;
- global variables, read-only constants, symbol addresses and addends, writable
  pointer initializers, and external calls through K16 RELA records;
- GNU declaration symbol labels such as `__asm__("kraft_sys_write")`, which
  rename an ELF symbol but do not contain integrated assembly.

`tools/k16-tinycc-smoke.sh` compiles the positive corpus with both TinyCC and
Clang, inspects both objects, links them with `k16 link`, and compares observable
VM results. It also checks TinyCC/Clang caller-callee combinations and exact
instruction bytes for constant returns and direct calls. A checked-in
freestanding compiler-runtime fixture supplies the current Clang 32-bit
division libcalls; no host libc is linked.

The isolated `compileK16TinyCcUnameProof` task additionally compiles the real
KraftOS `crt0.c` and `uname.c`, links them against the existing
`libkraft.kso`, and writes
`build/generated/k16-tinycc-proof/uname.kx` in the NeoForge module. The runtime
smoke copies the production KraftOS volume, replaces only `/bin/uname.kx`,
boots the real OS, and observes `K16` followed by a returned shell prompt.

## Unsupported Surface

The first backend slice rejects all of these instead of guessing an ABI:

- floating-point values and operations: `K16 TinyCC does not support
  floating-point code yet`;
- variadic functions: `K16 TinyCC does not support variadic functions yet`;
- integrated assembly statements and assembly source files: `K16 TinyCC does
  not support integrated assembly`;
- wide integer values that require more than one 32-bit ABI slot: `K16 TinyCC
  does not support values wider than one 32-bit ABI slot yet`;
- aggregate arguments or returns, including hidden structure returns: `K16
  TinyCC does not support aggregate arguments or returns yet`;
- JIT execution and the `-run` option: `K16 TinyCC is a cross-compiler; -run is
  unavailable`.

Exceptions, unwinding, debug relocations, jump-table relocations, and TinyCC
final linking are likewise not part of the proof. A future slice must add an
ABI contract and regression coverage before accepting any of these shapes.

## Verification And Production Status

Run the complete backend and real-OS proof with:

```bash
./gradlew-sandbox-dev-parallel verifyK16TinyCc
```

The backend-only differential corpus is available as:

```bash
./gradlew-sandbox-dev-parallel verifyK16TinyCcBackend
```

The runtime-only proof is available as:

```bash
./gradlew-sandbox-dev-parallel :v1_21_1-neoforge:verifyK16TinyCcRuntime
```

The production KraftOS remains Clang-built. Existing
`compileK16System*` tasks still use the K16 LLVM/Clang path; the TinyCC uname
artifact exists only under its dedicated proof directory and is injected only
into a temporary test volume.

A guest TinyCC port, a C SDK module, libc packaging for that SDK, and an
in-game editor/compiler workflow are later work. Guest-side `-run` or JIT is
not implied by this host backend: executing a program remains an explicit
compile, `k16 link`, load, and KraftOS process operation.
