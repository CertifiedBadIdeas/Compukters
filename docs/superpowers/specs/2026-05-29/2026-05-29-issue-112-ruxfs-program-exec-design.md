# RuxFS Program Exec Design
> Issue: [#112](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/112)

## Context

RUXE now has a `program` ABI kind and `rux compile` emits user-space program artifacts by default. RuxFS and RUXPT already exist on the compiler/tooling side, but the VM runtime does not yet have a reusable reader for the storage layout that the future kernel exec path will need.

The active storage0 device exposes logical media bytes to the guest VM. When storage is backed by a `.ruxvol` file, the runtime storage media strips the host-side `RUXVOL` header and exposes the payload, so LBA0 is the `RUXPT` block. Runtime-side storage readers must therefore operate on storage media bytes, not host file paths.

## Design

Add a read-only runtime storage reader in `native/rux-vm` that composes:

```text
storage0 media bytes -> RUXPT ROOT partition -> RUXFS absolute path -> file bytes
```

The reader is an internal VM/runtime building block, not a host API that accepts guest filesystem paths. It returns explicit errors for missing partitions, malformed RUXPT/RuxFS data, and missing paths. It does not search alternate partitions or paths.

Add a small program exec validator next to the reader that accepts already-read bytes and validates that they decode as RUXE ABI kind `program`. The runtime transfer helper then copies the executable payload into guest RAM at `load_addr` and starts Rux16 execution at `entry_pc`.

The final #112 slice adds the guest-side kernel policy: the kernel reads
`/bin/init.ruxe` from `ROOT`/RuxFS, validates ABI kind `program`, loads the
payload, and transfers execution to the program entry point.

## Scope

- Port the read-only `RUXPT -> ROOT -> RUXFS -> absolute path` reader into `native/rux-vm`.
- Add runtime RUXE decoding sufficient to identify the `program` ABI kind.
- Add a runtime transfer helper for already-read program RUXE bytes.
- Test that `/bin/init.ruxe` can be read from ROOT and accepted as a program executable.
- Test that a program RUXE payload executes from `entry_pc`.
- Test that kernel or malformed RUXE bytes are rejected as program executables.
- Add a guest kernel loader for `/bin/init.ruxe`.
- Test the full `BIOS -> bootloader -> kernel -> /bin/init.ruxe` chain.

## Out of Scope

- Full process creation or scheduling.
- Guest-visible syscalls.
- Host APIs that accept guest filesystem paths.

## Verification

- `cargo test` in `native/rux-vm`.
- `cargo fmt -- --check` in `native/rux-vm`.
