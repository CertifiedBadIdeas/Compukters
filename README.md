![Mod logo (AI generated)](logo_1.png)

**Programmable computers for Minecraft with deterministic, resource-bounded execution.**

Compukter Kraft is rebuilding its computer platform around a native Rust
RISC-V virtual machine. The selected product architecture is
`RV32IMA_Zicsr_Zifencei`, little-endian ELF32 with the standard ILP32 ABI.

## Status

The project is in an intentional clean-break migration interval. The retired
custom ISA and its Minecraft runtime have been removed; no VM-backed computer
is currently registered in the loadable mod.

The active VM implementation is `host/compukter-vm`. It currently provides a
bounded RV32IM interpreter, Cached and Predecoded execution backends, Zicsr and
machine-mode traps, a strict stock-toolchain ELF32 loader, and deterministic
instruction accounting.

KraftOS remains the guest operating-system direction. BIOS, KraftOS, devices,
storage, networking, the in-game programming loop, and Minecraft integration
will be rebuilt directly for RV32 without binary or source compatibility with
the retired architecture.

See [ADR 0001](docs/architecture-decisions/0001-retire-k16-adopt-rv32.md)
and [the current architecture](docs/ARCHITECTURE.md).

Currently targets **NeoForge 1.21.1**.

---

Devlog (in Russian): https://t.me/lazyhatdev

Source: https://github.com/CertifiedBadIdeas/Compukter-Kraft

License: GPL-3.0
