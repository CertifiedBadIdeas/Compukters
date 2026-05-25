# Rux16 Compiler And Volume Tooling Design

> Issue: [#73](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/73)

## Context

The runtime now boots from a per-computer BIOS flash file and executes the Rux16 guest CPU path. The remaining compiler tooling still exposes the old LowImage/RUXI model through `rux emit`, `rux-disasm`, `rux-run`, and the `native/rux-compiler` backend. That compiler crate should not be deleted; its frontend should be migrated so it can produce real Rux16 machine artifacts for BIOS, boot records, and future programs.

The old `.flash.words` resource is a human-readable seed format, not the real firmware artifact. The real BIOS input for the VM should be binary `bios.flash`. Any readable instruction listing should come from the disassembler output, not from a second persisted `.lst` artifact.

## Goals

- Replace the old public compiler tooling surface with one `rux` entrypoint and explicit subcommands.
- Make `rux compile` emit binary Rux16 artifacts, not RUXI images and not text word listings.
- Add volume tooling between BIOS and storage media so boot artifacts can be placed into a `storage0.ruxvol` image explicitly.
- Make `rux disasm` read binary Rux16 artifacts and print a human-readable disassembly to stdout.
- Avoid compatibility fallbacks from Rux16 artifacts back to LowImage/RUXI.

## CLI Shape

The public CLI should converge on:

```bash
rux compile --target bios path/to/bios.rx -o bios.flash
rux compile --target boot path/to/boot.rx -o boot.bin
rux compile --target program path/to/app.rx -o app.bin

rux disasm --target bios bios.flash
rux disasm --target boot boot.bin
rux disasm --target program app.bin

rux volume create storage0.ruxvol --size 1M
rux volume put-boot storage0.ruxvol boot.bin
rux volume put storage0.ruxvol /bin/app app.bin
rux volume ls storage0.ruxvol
rux volume extract storage0.ruxvol /bin/app -o app.bin
rux volume inspect storage0.ruxvol
```

`rux-emit`, `rux-run`, and `rux-disasm` should stop being the public interface. A future `rux run` may exist as an explicit Rux16 development harness, but it must not run RUXI as a fallback path.

## Artifact Contracts

`bios.flash` is raw binary flash content mapped at the Rux16 BIOS flash base by the VM. The compiler target decides the expected entry address and address model; the file itself does not need a RUXI-style header.

`boot.bin` is a binary boot artifact stored in a reserved ruxvol boot slot. BIOS reads the ruxvol header, validates the boot slot, copies or maps the boot artifact according to the boot ABI, and transfers execution to it.

`program.bin` is a binary program artifact intended for the future filesystem loader. It should remain separate from `boot.bin` because boot code and regular programs will have different loader contracts.

## Ruxvol Minimum Viable Format

The first ruxvol version should support a boot slot before it supports a full filesystem:

```text
offset  size  meaning
0       8     magic "RUXVOL1\0"
8       4     total volume size, little-endian u32
12      4     boot entry offset, little-endian u32, 0 if absent
16      4     boot entry size, little-endian u32, 0 if absent
20      4     boot entry checksum, little-endian u32, additive checksum over boot bytes
24      ...   reserved zero bytes until boot/data area
```

The MVP can place the boot slot at a fixed aligned offset after the header. Later versions can add a file table and allocation map without changing the compiler contract.

## Error Handling

Commands should require explicit targets for raw binary artifacts. `rux disasm bios.flash` should be an error; `rux disasm --target bios bios.flash` is valid. This keeps behavior deterministic and avoids guessing formats.

Ruxvol commands should fail on invalid magic, truncated headers, volume size mismatches, boot entries outside the volume, and boot entries that do not match their checksum. They should not try alternate legacy formats.

## Testing

The first implementation slice should add tests for ruxvol creation and boot-slot insertion. Later slices should add tests for `rux compile --target bios`, `rux disasm --target bios`, and removal of the old public `rux emit` path.
