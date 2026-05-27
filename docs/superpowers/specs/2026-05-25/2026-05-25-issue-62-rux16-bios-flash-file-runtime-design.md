# Rux16 BIOS Flash File Runtime Design

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

## Context

The native VM already supports Rux16 BIOS flash boot from bytes and can attach path-backed `storage0`. The in-game path still loads a bundled RUXI resource on the JVM side and passes the program bytes through JNI. That keeps Minecraft in the role of firmware loader, which is not the intended hardware model.

## Goal

Move the in-game Rux computer startup boundary to file-backed BIOS flash: JVM prepares a per-computer `bios.flash` file from a bundled resource, then Rust loads that file and starts the VM from Rux16 BIOS flash.

## Architecture

Each computer workspace owns `bios.flash` beside its persistent volumes. JVM preparation is explicit and limited to copying the bundled BIOS flash resource into the workspace when the file does not exist yet. JNI then passes paths, not program bytes: `bios.flash` and `storage0.ruxvol`.

Rust owns firmware loading for VM startup. The native entrypoint reads `bios.flash`, maps it as read-only BIOS flash, attaches path-backed `storage0`, and starts the Rux16 CPU at the BIOS flash base. Missing, empty, unreadable, or invalid BIOS flash is a hard startup error. There is no `LowImage` startup path and no fallback to bundled RUXI firmware for the in-game Rux computer path.

## Out of Scope

- A Flash BIOS UI operation.
- Stage2 media preparation tooling.
- Replacing old LowImage test/demo firmware resources.
- Full Rux16 BIOS bootloader content beyond the default no-boot-device screen.

## Verification

- JVM workspace tests prove `bios.flash` is prepared from a resource and existing per-computer flash is preserved.
- Native Rust tests prove BIOS flash can be loaded from a path.
- Kotlin/JNI tests prove the new runtime path has no firmware `ByteArray` parameter.
- In-game factory source uses `createFromBiosFlash`, not `createFromResource`.
