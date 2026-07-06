# KraftOS Artifact Contract

> Issue: [#451](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/451)

The Minecraft/runtime side consumes KraftOS as built runtime artifacts, not as
the `guest/kraftos` source tree.

## Runtime Artifacts

- `firmware/k16-bios.kflash` is the bundled BIOS flash image.
- `firmware/k16-system-storage0.kv` is the bundled initial system storage
  volume.
- A per-computer workspace stores those artifacts as `bios.kflash` and
  `volumes/storage0.kv`.

`KraftOsArtifacts` is the runtime-facing boundary for these names and
preparation steps. Upper layers should depend on that contract instead of
calling low-level BIOS or storage workspace helpers directly.

## Repository Split Direction

When KraftOS moves into an independent repository, this contract is the
integration point that should remain stable inside the mod repository. The
producer side may change how the BIOS flash and system volume are built, but
the consumer side should continue to consume explicit artifacts with the same
runtime meaning.
