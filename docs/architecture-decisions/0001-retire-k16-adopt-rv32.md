# Retire K16 and Adopt RV32

> Decision issue: [#485](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/485)
>
> Previous decision: [#475](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/475)
>
> Status: Accepted on 2026-08-09; XLEN and psABI amended by #485 on 2026-08-10

## Decision

Compukter Kraft retires K16 as a product ISA and adopts one standard RISC-V
target for future computers and microcontrollers:

```text
RV32IMA_Zicsr_Zifencei
little-endian ELF32
ILP32 ABI
```

The first implementation excludes the compressed `C` extension so every
implemented instruction is one 32-bit word. `C`, floating point, vectors, and
other standard or custom extensions require later evidence-backed decisions.

KraftOS remains the guest operating-system direction. Its product-level
contracts survive the ISA replacement: guest-owned BIOS and software, MMIO
devices, isolation, W^X memory, deterministic tick-relative execution,
explicit CPU and I/O quotas, snapshots, and portable programs. Their machine
encoding and low-level ABI are redesigned for the standard RISC-V platform;
K16 binary compatibility is not a requirement.

## Why K16 Is Retired

K16 proved that the mod can own a deterministic native VM, a complete guest
boot path, KraftOS, processes, storage, display and input devices, a C SDK,
and in-guest compilation. It also demonstrated useful interpreter techniques
such as eager predecode and precise host-visible accounting.

The remaining cost is not justified. A product K16 target requires permanent
ownership of compiler backends, assemblers, linkers, relocations, ABI rules,
debugger support, language ports, and optimization quality. Standard RISC-V
provides mature LLVM, Clang, LLD, GCC, Rust, ELF, and psABI support while still
allowing the VM to retain deterministic execution and resource control.

The recorded Gate 3 result in `docs/benchmarks/isa-gate3-current.txt` is not a
valid basis for selecting K16. The comparison inherited an i386
`frame-pointer=all` attribute that the RISC-V backend honored while the K16
backend ignored it, and it disabled RISC-V linker relaxation while K16 used a
single direct-call instruction. Correcting those controls reversed the
diagnostic winner. The historical snapshot remains reproducibility evidence
for the flawed run, not an architecture decision.

## Why RV32 Instead Of RV64

The original #475 decision selected RV64IMA, ELF64, and LP64 provisionally.
The later symmetric XLEN gates provide the evidence needed to amend that
choice.

The scalar32 compiled-C snapshot in
`docs/benchmarks/riscv-xlen-current.txt` records an RV64/RV32 warm-time
geometric mean of `1.158598`. Both candidates retire the same instruction
counts and use the same code and predecode bytes, so the current RV64
interpreter pays about 15.9 percent more host time on these predominantly
32-bit workloads.

The deliberately u64-heavy snapshot in
`docs/benchmarks/riscv-xlen-u64-current.txt` records an RV64/RV32 warm-time
geometric mean of `0.427173`. RV64 retires 59 to 72 percent fewer instructions
and is about 2.34 times faster across those kernels. This establishes the
64-bit boundary, but the synthetic corpus is not a KraftOS workload model.

Current KraftOS code predominantly uses 32-bit addresses, sizes, MMIO values,
filesystem metadata, and geometry. Its explicit 64-bit work is concentrated in
diagnostic counters, timer values, GPU sequence values, and on-demand compiler
runtime or libc helpers. Current computer memory profiles are also far below
the RV32 address-space limit. ILP32 therefore better matches the expected
machine density and workload shape while retaining ordinary C and Rust 64-bit
integer types when programs need them.

Shipping RV32 and RV64 together is not accepted. It would create two psABIs,
two KraftOS artifact families, and two guest compatibility matrices without
present product evidence that both are required.

## What Transfers From K16

- the Rust-owned isolated VM and JNI boundary;
- fixed per-tick execution entitlements and explicit throttling;
- eager predecode and an internal decoded-operation representation;
- future internal superinstructions that do not alter the guest ISA;
- separate fast RAM and observable MMIO paths;
- precise traps, permissions, W^X, and process isolation;
- guest-owned BIOS, KraftOS, filesystems, device drivers, and shell model;
- versioned device, executable, snapshot, and SDK contracts;
- cold, warm, many-VM, traffic, and native-reference profiling.

## What Does Not Transfer

- K16 instruction encodings and registers;
- the K16 calling convention and relocations;
- K16E as the machine executable format;
- the custom K16 LLVM and TinyCC backends as product dependencies;
- K16-specific firmware binaries and compatibility shims;
- the Gate 3 K16 selection claim;
- the provisional RV64/LP64 product ABI from #475;
- a dual-RV32/RV64 product or cross-XLEN binary compatibility promise.

## Migration Boundary

K16 is no longer an active product target. No new K16 ISA, ABI, compiler,
firmware, or userland features are accepted. Existing K16 implementation and
tests may remain temporarily only as a behavioral oracle while their RV32
replacement is built. They are removed after the equivalent RV32 vertical
slice passes; this does not create a supported dual-ISA product or a backward
compatibility promise.

The migration proceeds through independently verifiable slices:

1. Establish a production-shaped RV32IMA interpreter, eager predecode path,
   corrected compiled-C benchmark, standard ELF32 loader, and ILP32 contract.
2. Define the RISC-V privileged machine, traps, timer, MMIO map, protection,
   and virtual-memory contract required by KraftOS.
3. Port BIOS, KraftOS, storage, display, input, networking, libc, and the
   in-guest compiler path without K16 compatibility layers.
4. Switch the JNI/Minecraft runtime and bundled resources to RV32.
5. Delete retired K16 runtime, guest artifacts, build tasks, and custom
   toolchain dependencies.

The repository remains operational on its current K16 boot path until the RV32
runtime reaches the product cutover slice. Documentation must distinguish this
temporary implementation fact from the accepted architecture direction.

## RV64 And Future 64-Bit Acceleration

The isolated RV64 interpreter remains useful as a benchmark and semantic
reference. It does not receive a product ABI, KraftOS build, bundled firmware,
release artifact, or compatibility promise.

The first RV32 implementation adds no custom guest-visible extension. If
representative traces show a material 64-bit bottleneck, investigation proceeds
in this order:

1. optimize the ordinary RV32 interpreter and memory path;
2. evaluate the standard RV32 `Zilsd` load/store-pair extension;
3. evaluate transparent VM-internal superinstructions while charging their
   original architectural instruction cost;
4. reconsider RV64 only through a new evidence-backed architecture decision.

Internal fusion must preserve standard RV32 binaries, precise traps, results,
and deterministic quota accounting. It must not require a custom compiler
backend.

RV64 reconsideration requires end-to-end measurements of KraftOS boot, idle,
filesystem and process work, in-guest compilation, representative automation
applications, retained RAM per machine, aggregate many-VM density, retired
instructions, server time, and quota behavior. An isolated arithmetic win or a
larger theoretical address space is insufficient by itself.

## Consequences

The project gains standard toolchains while selecting the narrower psABI that
best matches its small isolated computers and expected many-machine density.
It can add internal fusion, host accelerators, or a future JIT without changing
guest binaries. Programs retain standard 64-bit integer and floating-point
language types even though some operations require multi-instruction lowering
on RV32.

The project accepts slower execution for genuinely 64-bit-heavy programs in
exchange for smaller pointers and guest structures, a smaller CPU state, and
the measured scalar32 advantage. Those costs and benefits must be checked again
with a complete RV32 KraftOS vertical slice before product cutover.
