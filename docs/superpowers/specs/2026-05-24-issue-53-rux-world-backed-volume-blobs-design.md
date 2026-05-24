# Rux World-Backed Volume Blobs Design

> Issue: [#53](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/53)

## Status

Implemented first slice.

## Context

Rux computer profile v1 already defines a `storage0` MMIO block storage port.
That contract is guest-visible and remains unchanged. The missing layer is the
host-side persistence behind that port: a durable raw byte blob owned by the
Minecraft world save.

The long-term direction is a computer built from parts: different bases expose
different port layouts, and removable items may later provide disk or flash
media. That is not the first implementation slice. The first slice should be a
single internal volume attached to the computer itself, created lazily on first
runtime use.

## Goals

- Add a world-backed raw volume blob abstraction for Rux storage media.
- Create a default internal `storage0` volume for each computer on first VM use.
- Persist volume bytes across block entity unload, chunk unload, server restart,
  and world reload.
- Keep the existing `RUXI` image ABI and `storage0` MMIO register contract
  unchanged.
- Keep the implementation independent from old Kotlin VM fallback paths.
- Detect invalid/corrupted host files deterministically without crashing the
  server.

## Non-Goals

- No filesystem, VFS, directories, paths, partitions, or bootloader format.
- No boot device discovery or boot menu.
- No removable disk item, USB storage item, hotplug, or storage UI.
- No migration of storage ownership from computer-owned to item-owned media.
- No guest-visible ABI change.

## Volume Identity

The first supported identity is computer-owned:

```text
ComputerOwnedVolume(
  computer_id = <stable computer id>,
  slot = "storage0"
)
```

`computer_id` is the existing stable Minecraft-side computer id saved on the
block entity/item. `slot` is a stable string owned by the host integration, not
guest firmware.

This is intentionally extensible. Future removable media can use a separate
identity such as:

```text
RemovableMediaVolume(
  media_id = <stable item media id>
)
```

The blob API should not care which identity kind owns the bytes.

## World Save Layout

The initial computer-owned storage layout should be:

```text
<world-save>/
  compukterkraft/
    computers/
      <computer-id>/
        volumes/
          storage0.ruxvol
```

The implementation must construct paths only from validated identity fields.
The volume id must not allow path separators, `..`, absolute paths, or platform
path tricks. For the initial `ComputerOwnedVolume`, `<computer-id>` is numeric
and `storage0` is a fixed constant, so path traversal should be impossible by
construction.

## Default Volume

The default `storage0` volume:

- is created lazily when the computer runtime first requests it;
- has logical size `1 MiB`;
- uses block size `512` when attached to the existing storage MMIO device;
- is writable;
- belongs to the computer, not to a removable item.

Creating the blob on first runtime use avoids generating save files for placed
computers that have never been powered on.

## File Format

The host file is a small container around raw payload bytes:

```text
offset  size  name
0x00    6     magic = "RUXVOL"
0x06    2     version = 1, little-endian u16
0x08    8     logical_size, little-endian u64
0x10    N     payload bytes
```

`payload` is the byte range exposed as storage media. The guest never sees the
header.

The first implementation should require:

- `magic == "RUXVOL"`;
- `version == 1`;
- `logical_size > 0`;
- `logical_size <= configured_max_volume_size`;
- file length is exactly `0x10 + logical_size`, or shorter files fail as
  truncated/corrupt.

Growing or shrinking through `resize` must update `logical_size` and adjust the
payload length. New bytes introduced by growth are zero-filled.

## Host API

The storage layer should expose a small raw blob interface:

```text
RuxVolumeBlob
  size(): Long
  read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int)
  write(offset: Long, src: ByteArray, srcOffset: Int, length: Int)
  resize(newSize: Long)
  flush()
  close()
```

Expected behavior:

- reads and writes validate `offset + length <= size`;
- integer overflow is checked before file access;
- `flush` persists buffered changes to the underlying file;
- `close` is idempotent;
- after `close`, further operations fail deterministically.

The first implementation can be synchronous and file-backed. It does not need a
page cache, journal, async I/O, or crash recovery.

## Runtime Integration

The Minecraft-side runtime host should resolve the computer-owned `storage0`
blob when a Rux computer runtime is created.

The guest-visible machine remains:

```text
ComputerMachine + storage0 MMIO port
```

The host-side media source changes from in-memory or absent media to:

```text
storage0 MMIO port -> RuxVolumeBlob payload
```

The first implementation attaches the blob by loading the complete payload into
native `storage0` at runtime start and writing the native storage snapshot back
to the blob at runtime close. This is intentionally simple and matches the
current `1 MiB` internal storage target.

Crash-safe incremental flushing remains future work. The current guarantee is
stable persistence across normal runtime shutdown, block entity release, and
server context close.

## Error Handling

Host-side failures should become deterministic Kotlin/Rust domain errors, not
server crashes.

Required error cases:

- invalid magic;
- unsupported version;
- truncated header;
- truncated payload;
- invalid logical size;
- read/write outside logical size;
- invalid path/identity;
- underlying I/O failure.

When such a blob is attached to the VM later, unusable media should map to the
existing storage MMIO `media_status = ERROR` and I/O error behavior.

## Future Compatibility

This design intentionally keeps ownership and storage bytes separate:

- current owner: computer-owned internal `storage0`;
- future owner: removable media item;
- current consumer: Rux computer runtime;
- future consumers: BIOS/bootloader/filesystem layers.

Moving from internal storage to removable media should not require changing the
blob format or the guest `storage0` MMIO contract. It should only change which
volume identity is attached to a hardware port.

## Testing

Focused host-side tests should cover:

- create default `storage0` volume with `1 MiB` payload;
- write/read within one opened blob;
- close/reopen preserves data;
- resize growth zero-fills new bytes;
- resize shrink rejects reads beyond the new size;
- invalid magic fails deterministically;
- unsupported version fails deterministically;
- truncated header fails deterministically;
- truncated payload fails deterministically;
- out-of-bounds read/write fails deterministically.

Integration tests should cover:

- computer runtime can obtain the same `storage0` blob across repeated opens;
- implementation path is independent from old Kotlin VM fallback runtime.
