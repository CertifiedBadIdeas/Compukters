![Mod logo (Ai generated)](logo_1.png)

**Programmable computers for Minecraft — booted end-to-end on a custom Rust VM.**

Inspired by ComputerCraft. With one big difference: the runtime is not Lua —
it is a native Rust virtual machine (`rux-vm`) wrapped via JNI.

## What you get

- 💻 **Notebook** — a portable player-facing computer item that boots its own
  Rux VM instance on a background daemon thread.
- 🖥 **Rux VM** — a deterministic, sandboxed virtual machine implemented in
  Rust. Loads `rux-laptop.ruxi` images compiled by the `rux-compiler` toolchain.
- 🔌 **Display + input devices** — accelerated framebuffer with `copyRect` /
  `blitMono` primitives, terminal input queue, and a per-device runtime workspace.

## Status

Mid-rewrite. The legacy in-game CKL language stack, the Workbench IDE,
the standalone Computer block, the Workbench/Terminal/Serial items, and the
CKIM bytecode runtime have all been removed. The remaining player-facing
surface is Notebook, booted through the Rust toolchain
(`native/rux-compiler` → `rux-laptop.ruxi` → `native/rux-vm`).

Currently for **NeoForge 1.21.1**.

---

Devlog (in russian): https://t.me/lazyhatdev
Source: https://github.com/LazyHat/Compukter-Kraft
License: GPL-3.0
