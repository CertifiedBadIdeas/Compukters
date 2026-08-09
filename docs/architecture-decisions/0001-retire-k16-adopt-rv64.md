# Retire K16 and Adopt RV64

> Issue: [#475](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/475)
>
> Status: Accepted on 2026-08-09

## Decision

Compukter Kraft retires K16 as a product ISA and adopts one standard RISC-V
target for future computers and microcontrollers:

```text
RV64IMA_Zicsr_Zifencei
little-endian ELF64
LP64 ABI
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
- the Gate 3 K16 selection claim.

## Migration Boundary

K16 is no longer an active product target. No new K16 ISA, ABI, compiler,
firmware, or userland features are accepted. Existing K16 implementation and
tests may remain temporarily only as a behavioral oracle while their RV64
replacement is built. They are removed after the equivalent RV64 vertical
slice passes; this does not create a supported dual-ISA product or a backward
compatibility promise.

The migration proceeds through independently verifiable slices:

1. Establish a production-shaped RV64IMA interpreter, eager predecode path,
   corrected compiled-C benchmark, standard ELF64 loader, and LP64 contract.
2. Define the RISC-V privileged machine, traps, timer, MMIO map, protection,
   and virtual-memory contract required by KraftOS.
3. Port BIOS, KraftOS, storage, display, input, networking, libc, and the
   in-guest compiler path without K16 compatibility layers.
4. Switch the JNI/Minecraft runtime and bundled resources to RV64.
5. Delete retired K16 runtime, guest artifacts, build tasks, and custom
   toolchain dependencies.

The repository remains operational on its current K16 boot path until the RV64
runtime reaches the product cutover slice. Documentation must distinguish this
temporary implementation fact from the accepted architecture direction.

## Consequences

The project gains standard toolchains and can add internal fusion, host
accelerators, or a future JIT without changing guest binaries. An explicit
custom RISC-V extension remains possible but must be justified by measurements;
transparent VM-internal optimizations are preferred.

RV64 increases pointer and guest data-structure size relative to RV32 and K16.
That cost is accepted provisionally in exchange for a uniform target, a large
address space, guest LLVM feasibility, and mature LP64 tooling. The many-VM
capacity gates must measure the resulting memory cost before product cutover.

