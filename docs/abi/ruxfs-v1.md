# RuxFS v1

## Status

Status: experimental.

RuxFS v1 is the filesystem intended for the `ROOT` partition in the
partitioned `RUXPT` storage0 layout. It is an extent-based filesystem with a
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
magic       "RUXFS"
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

## Extents

Each inline extent is 8 bytes:

```text
offset  size  name
0x00    4     start_block
0x04    4     block_count
```

RuxFS v1 currently supports up to 4 inline extents per inode. Extents must be
non-empty, inside `total_blocks`, and outside the superblock, bitmap, and inode
table ranges.

## Current Implementation Scope

The current compiler crate provides:

- empty filesystem formatting;
- superblock and inode decoding;
- structural validation for magic, version, block size, metadata ranges, root
  inode state, and root extents.

Directory entries, named files, file writing, and bootloader integration are
next-step work under the same RuxFS v1 contract.
