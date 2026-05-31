# K16FS v1

## Status

Status: experimental.

K16FS v1 is the filesystem intended for the `ROOT` partition in the
partitioned `K16PT` storage0 layout. It is an extent-based filesystem with a
fixed 512-byte block size, one superblock, one allocation bitmap, and one inode
table.

All multi-byte fields are little-endian. Implementations must reject invalid
metadata explicitly rather than probing alternate layouts.

## Block Layout

An empty formatted filesystem currently uses this layout:

```text
block 0        superblock
block 1..N     allocation bitmap
block N+1..M   inode table
block M+1      root directory data block
```

The current formatter uses 64 inodes by default. For a 128-block filesystem
this yields:

```text
block 0      superblock
block 1      allocation bitmap
block 2..9   inode table
block 10     root directory data block
```

## Superblock

The superblock occupies block 0.

```text
offset  size  name
0x00    5     magic
0x05    1     version
0x06    2     reserved
0x08    4     block_size
0x0C    4     total_blocks
0x10    4     bitmap_start_block
0x14    4     bitmap_block_count
0x18    4     inode_table_start_block
0x1C    4     inode_table_block_count
0x20    4     root_inode_id
0x24    4     flags
```

Field values for v1:

```text
magic       "K16FS"
version     1
reserved    0
block_size  512
```

## Allocation Bitmap

The allocation bitmap starts at `bitmap_start_block` and covers
`bitmap_block_count` blocks. Bit `0` of byte `0` corresponds to filesystem
block `0`; bit `1` corresponds to block `1`, and so on.

Allocated metadata blocks and the root directory block must be marked used.

## Inodes

Each inode is 64 bytes.

```text
offset  size  name
0x00    1     state
0x01    3     reserved
0x04    4     flags
0x08    8     size_bytes
0x10    1     extent_count
0x11    15    reserved
0x20    32    inline_extents
```

Accepted inode states:

```text
0  free
1  file
2  directory
3  deleted
```

The root inode id is stored in the superblock. The current formatter writes
root inode `1` as a directory with size `0` and one extent pointing at the root
directory data block.

Directory inode `size_bytes` is the number of directory-entry bytes currently
used by the directory. It must be a multiple of 64.

## Extents

Each inline extent is 8 bytes:

```text
offset  size  name
0x00    4     start_block
0x04    4     block_count
```

K16FS v1 currently supports up to 4 inline extents per inode. Extents must be
non-empty, inside `total_blocks`, and outside the superblock, bitmap, and inode
table ranges.

## Directories

Directory data is a sequence of fixed-size 64-byte entries:

```text
offset  size  name
0x00    1     state
0x01    1     name_len
0x02    2     reserved
0x04    4     inode_id
0x08    56    name_utf8
```

Accepted directory entry states:

```text
0  free
1  live
2  deleted
```

Names are UTF-8, must be 1..56 bytes, and are not NUL-terminated. Live
entries in one directory must not contain duplicate names. A live entry must
point to an active file or directory inode.

## Current Implementation Scope

The current compiler crate provides:

- empty filesystem formatting;
- superblock and inode decoding;
- structural validation for magic, version, block size, metadata ranges, root
  inode state, inode extents, directory entries, and duplicate directory names;
- absolute-path directory creation;
- absolute-path file creation and full-file reads;
- absolute-path file deletion;
- directory listing.

The host-side read path used to model future bootloader behavior is:

```text
K16VOL -> K16PT ROOT partition -> K16FS superblock -> /boot/kernel.kx
```

This reader composes byte-level volume partition extraction with K16FS absolute
path lookup. It is not a fallback path and does not make `k16 volume`
filesystem-aware.

The VM runtime also has a read-only storage image reader for the guest-visible
media payload:

```text
storage0 media bytes -> K16PT ROOT partition -> K16FS superblock -> absolute path
```

This runtime reader starts at LBA0 of the storage media payload. It does not
accept a host `.kv` path and does not strip the 16-byte `K16VOL` host file
header. The storage backend performs that file-to-media translation before the
guest-visible storage path sees any bytes.

The current guest boot chain uses the same K16FS structure:

```text
BIOS       -> storage0 BOOT/K16FS /boot/loader.kb -> bootloader
bootloader -> storage0 ROOT/K16FS /boot/kernel.kx -> kernel
kernel     -> storage0 ROOT/K16FS /bin/init.kx    -> program
```

Guest loaders treat missing paths, malformed metadata, or wrong executable ABI
kinds as hard load failures.

The public CLI namespace is filesystem-specific:

```text
k16 fs kfs format <image.kfs> --blocks <blocks>
k16 fs kfs mkdir <image.kfs> <path>
k16 fs kfs put <image.kfs> <path> <host-input>
k16 fs kfs get <image.kfs> <path> <host-output>
k16 fs kfs rm <image.kfs> <path>
k16 fs kfs ls <image.kfs> <path>
```

`k16 volume` remains storage-container tooling. General filesystem operations
must stay in `k16 fs <filesystem>` subcommands. The current `put-boot` and
`put-kernel` commands are boot-chain installation helpers that write the
standard system files into the active K16FS-backed volume layout.

Overwrite remains next-step work under the same K16FS v1 contract.
