# BIOS BOOT RuxFS Loader Design
> Issue: [#113](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/113)

## Context

The partitioned boot chain already uses `ROOT`/RuxFS for `/boot/kernel.ruxe`
and `/bin/init.ruxe`, but BIOS still used a `RUXB` record inside the `BOOT`
partition. That left the first boot step as a special record while the rest of
the chain used normal filesystem paths.

## Design

For partitioned `RUXPT` volumes, `rux volume put-boot` installs the bootloader
as a regular RuxFS file:

```text
BOOT:/boot/loader.ruxe
```

The bundled BIOS now treats the partitioned path as:

```text
storage0 LBA0 -> RUXPT -> BOOT partition -> RuxFS -> /boot/loader.ruxe
```

BIOS validates the loaded artifact as `RUXE` ABI kind `bootloader`, copies the
single load section into guest RAM at `load_addr`, and jumps to `entry_pc`.
Missing paths, malformed RuxFS metadata, invalid RUXE headers, or wrong ABI
kinds are hard boot failures.

The legacy raw-media `RUXB` path remains only for volumes created with
`rux volume create`. The partitioned path does not probe `RUXB` as a fallback.

## Scope

- Install bootloader RUXE into `BOOT`/RuxFS `/boot/loader.ruxe`.
- Teach BIOS to read the BOOT RuxFS path and execute the bootloader RUXE.
- Keep the existing ROOT `/boot/kernel.ruxe` and `/bin/init.ruxe` chain intact.
- Update boot inspection output and active ABI docs.

## Out of Scope

- Full guest filesystem APIs.
- Multiple filesystem implementations.
- Process isolation, syscalls, or scheduling.
- Compatibility fallback from partitioned BOOT/RuxFS to fixed `RUXB` records.

## Verification

- `cargo fmt -- --check` in `native/rux-compiler`.
- `cargo test` in `native/rux-compiler`.
- `cargo test` in `native/rux-vm`.
- `git diff --check`.
