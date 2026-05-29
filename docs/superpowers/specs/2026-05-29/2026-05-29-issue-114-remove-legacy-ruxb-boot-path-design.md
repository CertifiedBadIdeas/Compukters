# Remove Legacy RUXB Boot Path Design
> Issue: [#114](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/114)

## Context

The active boot chain is now fully partitioned and filesystem-backed:

```text
RUXPT -> BOOT/RuxFS /boot/loader.ruxe -> ROOT/RuxFS /boot/kernel.ruxe -> ROOT/RuxFS /bin/init.ruxe
```

Before this slice, two legacy fixed-media paths still existed: the bundled BIOS
could execute a raw `RUXB` record at LBA 0, and `rux volume put-boot` could write
that record into an unpartitioned volume.

## Design

The only supported boot path is the partitioned `RUXPT` path. BIOS reads LBA 0
as `RUXPT`, finds `BOOT`, opens `/boot/loader.ruxe` in BOOT/RuxFS, validates it
as a `RUXE` bootloader, and jumps to its entry point. If LBA 0 is not a valid
partition table, boot fails with the existing no-bootable-device state.

`rux volume put-boot` now requires a RUXPT partitioned volume. Unpartitioned
volumes created by `rux volume create` are valid storage containers, but they
are not boot media and are rejected by boot installation tooling.

## Scope

- Remove raw `RUXB` probing from bundled BIOS.
- Remove raw `RUXB` writing from `rux volume put-boot`.
- Keep rejection tests for raw `RUXB` media and unpartitioned volumes.
- Update active ABI docs to describe only the partitioned path.

## Out of Scope

- Removing historical design docs that mention earlier `RUXB` experiments.
- Changing bootloader, kernel, or init loading semantics.
- Adding alternate boot paths.

## Verification

- `cargo fmt -- --check` in `native/rux-compiler`.
- `cargo test` in `native/rux-compiler`.
- `cargo test` in `native/rux-vm`.
- `git diff --check`.
