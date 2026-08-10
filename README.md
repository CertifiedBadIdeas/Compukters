![Mod logo (Ai generated)](logo_1.png)

**Programmable computers for Minecraft — booted end-to-end on a native Rust VM.**

Inspired by ComputerCraft. With one big difference: the runtime is not Lua —
it is a native Rust virtual machine (`k16-vm`) wrapped via JNI.

## What you get

- 💻 **Notebook** — a portable player-facing computer item that boots its own
  Kraft16 VM instance on a background daemon thread.
- 🖥 **Kraft16 VM** — a deterministic, sandboxed virtual machine implemented in
  Rust. Boots K16 code from per-computer `bios.kflash` files and storage0
  `.kv` boot media.
- 🔌 **Display + input devices** — retained `gpu0` resources rendered by the
  Minecraft client, keyboard/input queues, and a per-device runtime workspace.

## Status

Mid-rewrite. The legacy in-game CKL language stack, the Workbench IDE,
the standalone Computer block, the Workbench/Terminal/Serial items, and the
CKIM bytecode runtime have all been removed. The remaining player-facing
surface is Notebook, booted through the Kraft16 BIOS path
(`bios.kflash` → `storage0.kv` boot entry → guest K16 execution).

K16 is now retired as the long-term product ISA. The accepted target is
`RV32IMA_Zicsr_Zifencei` with ILP32/ELF32; the current K16 boot path remains only
while equivalent RV32 vertical slices replace it. RV64 remains an isolated
benchmark/reference candidate, not a second product target. See
[ADR 0001](docs/architecture-decisions/0001-retire-k16-adopt-rv32.md).

Currently for **NeoForge 1.21.1**.

---

Devlog (in russian): https://t.me/lazyhatdev
Source: https://github.com/CertifiedBadIdeas/Compukter-Kraft
License: GPL-3.0
