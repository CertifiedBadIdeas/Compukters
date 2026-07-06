# KraftOS Artifact Contract

> Issue: [#451](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/451)

The Minecraft/runtime side consumes KraftOS as built runtime artifacts, not as
the `guest/kraftos` source tree.

## Bundle Boundary

The generated production runtime bundle is assembled under
`build/generated/kraftos-bundles/production` and is the only generated KraftOS
bundle published into the main NeoForge resources today. The older
`build/generated/k16-firmware-resources` directory remains an internal producer
output; runtime resource packaging consumes the production bundle boundary
instead of that producer-internal directory.

The production bundle currently contains:

- `firmware/kraftos-artifacts.properties`
- `firmware/k16-bios.kflash`
- `firmware/k16-system-storage0.kv`

## Runtime Artifacts

- `firmware/kraftos-artifacts.properties` is the bundled manifest that names the
  runtime artifact resources and their formats.
- `firmware/k16-bios.kflash` is the bundled BIOS flash image.
- `firmware/k16-system-storage0.kv` is the bundled initial system storage
  volume.
- A per-computer workspace stores those artifacts as `bios.kflash` and
  `volumes/storage0.kv`.

`KraftOsArtifacts` is the runtime-facing boundary for these names and
preparation steps. Upper layers should depend on that contract instead of
calling low-level BIOS or storage workspace helpers directly.

## Manifest Schema

The current manifest uses Java `Properties` syntax and schema `1`:

```properties
schema=1
target=k16
profile=production
artifact.biosFlash.resource=firmware/k16-bios.kflash
artifact.biosFlash.format=kflash
artifact.systemStorage0.resource=firmware/k16-system-storage0.kv
artifact.systemStorage0.format=kfs-kv
```

The manifest is generated with the bundled firmware resources. Runtime code
loads and validates it before using default artifact paths.

`profile=production` is the only generated bundled runtime profile today.
`profile=development` is accepted by the manifest contract so a future KraftOS
producer can publish a development bundle without changing the parser, but this
repository does not generate or package a development runtime bundle yet.

## Repository Split Direction

When KraftOS moves into an independent repository, this contract is the
integration point that should remain stable inside the mod repository. The
producer side may change how the BIOS flash and system volume are built, but it
must still publish a manifest-backed bundle with the same runtime meaning. The
consumer side should discover artifact resources through the manifest instead of
depending on the source tree or on producer-internal build paths.
