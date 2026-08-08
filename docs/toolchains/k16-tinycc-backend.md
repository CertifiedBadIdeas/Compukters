# K16 TinyCC Host Backend

> Issues: [#464](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/464),
> [#465](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/465)

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
- representation transport for `long long`, binary64 `double`, and binary64
  `long double`, including paired-register and stack arguments and returns;
- aggregate-by-value arguments through aligned caller-owned copies and 32-bit
  guest pointers, with mutation isolation across the call;
- variadic calls and callees using the shared K16 C stack stream, C default
  argument promotions, `va_start`, `va_arg`, independent `va_copy` cursors, and
  no-op `va_end`;
- global variables, read-only constants, symbol addresses and addends, writable
  pointer initializers, and external calls through K16 RELA records;
- GNU declaration symbol labels such as `__asm__("kraft_sys_write")`, which
  rename an ELF symbol but do not contain integrated assembly.

`tools/k16-tinycc-smoke.sh` compiles the positive corpus with both TinyCC and
Clang, inspects both objects, links them with `k16 link`, and compares observable
VM results. Its complete variadic fixture executes TinyCC/TinyCC,
TinyCC/Clang, Clang/TinyCC, and Clang/Clang caller-callee combinations across
promoted integers, pointers, wide scalars, floating representations, and
aggregates. It also checks exact instruction bytes for constant returns and
direct calls. A checked-in freestanding compiler-runtime fixture supplies the
current Clang 32-bit division libcalls; no host libc is linked.

The isolated `compileK16TinyCcUnameProof` task additionally compiles the real
KraftOS `crt0.c` and `uname.c`, links them against the existing
`libkraft.kso`, and writes
`build/generated/k16-tinycc-proof/uname.kx` in the NeoForge module. The runtime
smoke copies the production KraftOS volume, replaces only `/bin/uname.kx`,
boots the real OS, and observes `K16` followed by a returned shell prompt.

## Unsupported Surface

The backend rejects these boundaries instead of guessing an ABI:

- floating-point arithmetic: `K16 TinyCC does not support floating-point code
  yet`. Loading, storing, passing, returning, and inspecting floating object
  representations are supported and do not require arithmetic helpers;
- C vector extensions: `K16 TinyCC does not support vector values`;
- complex values: `K16 TinyCC does not support complex values`;
- empty GNU aggregate arguments: `K16 TinyCC does not support empty aggregate
  arguments`;
- values requiring alignment above the K16 maximum: `K16 TinyCC arguments
  cannot require alignment above 8 bytes`;
- integrated assembly statements and assembly source files: `K16 TinyCC does
  not support integrated assembly`;
- aggregate returns that require the unsupported structure-return path: `K16
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
