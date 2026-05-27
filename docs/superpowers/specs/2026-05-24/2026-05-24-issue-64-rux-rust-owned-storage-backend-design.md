# Rux Rust-Owned Storage Backend Design

> Issue: [#64](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/64)

## Status

Implemented.

## Context

Issue #53 added a world-backed `storage0.ruxvol` file and wires it into the Rux
runtime by copying the full payload into native memory at startup, then copying
the full native snapshot back to the file on runtime close.

That is enough for the first persistence slice, but it has the wrong storage
semantics for a VM block device:

- guest `STORAGE_COMMAND_FLUSH` does not currently flush the host file;
- recent writes can be lost if the server process exits before normal runtime
  close;
- every runtime start/close copies the entire volume across JNI.

## Decision

Move normal `storage0` media I/O into Rust using a host-provided `.ruxvol` path.

Kotlin remains responsible for Minecraft world policy:

- resolving the world save root;
- validating the computer id and storage slot;
- creating the `.ruxvol` file if missing;
- deciding which volume belongs to which machine port.

Rust becomes responsible for storage device behavior:

- opening the concrete file path provided by Kotlin;
- validating the `RUXVOL` header;
- exposing only payload bytes to the MMIO storage device;
- reading and writing payload bytes for block commands;
- syncing the file on guest `STORAGE_COMMAND_FLUSH`;
- closing the file when the runtime closes.

This keeps the VM independent from Minecraft path layout while allowing the
storage implementation to be a real Rust-owned block backend.

## Architecture

Rust `storage0` should use a storage media abstraction:

```text
StorageMedia
  len()
  read_at(offset, dst)
  write_at(offset, src)
  flush()
  snapshot_bytes()
```

Implementations:

- `InMemoryStorageMedia` for existing tests, CLI flows, and optional snapshot
  behavior;
- `RuxVolumeFileStorageMedia` for Minecraft runtime storage paths.

The existing MMIO storage register contract remains unchanged. The storage MMIO
device should depend on `StorageMedia`, not on a raw `Vec<u8>`.

## File Format

The file format remains the issue #53 format:

```text
offset  size  name
0x00    6     magic = "RUXVOL"
0x06    2     version = 1, little-endian u16
0x08    8     logical_size, little-endian u64
0x10    N     payload bytes
```

Rust must validate:

- magic equals `RUXVOL`;
- version equals `1`;
- logical size is positive;
- file length is exactly header size plus logical size;
- payload offsets never exceed logical size.

Kotlin still owns creation and resize policy in this slice. Rust opens an
already-created file and treats invalid files as storage backend errors.

## JNI Boundary

Add a native creation path that accepts `storage0Path`.

The normal Minecraft runtime path should pass the concrete `storage0.ruxvol`
path instead of a full payload byte array. Existing in-memory creation can stay
available for tests and non-Minecraft entrypoints.

## Error Handling

Invalid or unavailable storage files should fail deterministically at runtime
creation. They should not silently create alternate files or invent paths inside
Rust.

Storage command failures after creation should map to the existing storage MMIO
error/status behavior.

## Compatibility

No guest-visible ABI change:

- no `RUXI` image format change;
- no instruction encoding change;
- no storage MMIO register layout change;
- no machine profile address change.

## Commit Strategy

This work should be split into small commits:

1. docs/spec/plan only;
2. Rust storage media abstraction and file backend;
3. JNI/Kotlin wiring;
4. follow-up fixes only if verification exposes them.
