# Extent-Based RuxFS v1 Design

> Issue: [#55](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/55)

## Context

Rux volumes need a real filesystem so firmware, bootloaders, kernels, and user programs can work with named files instead of fixed LBAs. The filesystem should be the same format in `BOOT` and `ROOT` partitions from #54. BIOS may implement only the read-only subset it needs, but that subset must read normal RuxFS v1 structures, not a separate boot-only micro filesystem.

The design should be closer to the VM than to PC compatibility. FAT32 compatibility is not a goal. RuxFS should keep the useful parts of simple disk filesystems while avoiding historical FAT details that do not help this VM.

## Accepted Direction

RuxFS v1 is an extent-based filesystem.

```text
partition:
  block 0      RUXFS superblock
  blocks N     allocation bitmap
  blocks M     inode table
  blocks K     directory and file data extents
```

Files and directories are represented by inodes. File data is stored in one or more extents:

```text
extent:
  start_block
  block_count
```

Directories are ordinary files whose data contains directory entries mapping UTF-8 names to inode ids. The root directory is referenced from the superblock.

This gives boot code a simple read path:

```text
RUXPT -> partition -> RUXFS superblock -> root inode -> path lookup -> file extents
```

## Superblock

The superblock starts at partition-relative block 0.

Required fields:

```text
magic
version
block_size
total_blocks
bitmap_start_block
bitmap_block_count
inode_table_start_block
inode_table_block_count
root_inode_id
flags
```

Initial values:

```text
magic       "RUXFS"
version     1
block_size  512
```

All multi-byte fields are little-endian.

## Inodes

An inode describes one filesystem object.

Required fields:

```text
state          free | file | directory | deleted
flags
size_bytes
extent_count
extents
```

RuxFS v1 should support enough inline extents for normal small files and directories. If an object needs more extents than v1 supports, tooling should return an explicit error rather than silently choosing another layout.

## Directories

A directory is a file containing directory entries.

Directory entry fields:

```text
state
inode_id
name_len
name_utf8
```

Path rules:

- paths are absolute when used by firmware/tooling, for example `/boot/loader.ruxe`;
- separator is `/`;
- names are UTF-8;
- empty path components are invalid except the leading `/`;
- `.` and `..` are not special in v1;
- duplicate live names in the same directory are invalid.

## Allocation

RuxFS v1 uses a block allocation bitmap, not a FAT chain.

The allocator should prefer contiguous extents. Fragmentation is allowed, but allocation remains explicit through extent lists. This keeps boot reads simple and makes `.ruxe` artifacts efficient to load.

Reserved metadata blocks must be marked allocated:

- superblock;
- allocation bitmap;
- inode table;
- root directory extents;
- any other fixed metadata extents introduced by v1.

## Required Operations

Tooling and host-side tests should support:

- format filesystem;
- create directory;
- list directory;
- create file;
- read file;
- write file;
- delete file;

Firmware/boot readers initially need only:

- validate superblock;
- open an absolute path;
- read the complete file from extents.

This is not a separate format. It is a read-only subset of the same RuxFS v1.

## BOOT And ROOT Use

`BOOT` and `ROOT` partitions both use RuxFS v1.

Expected initial layout:

```text
BOOT:
  /boot/loader.ruxe
  /boot/config

ROOT:
  /boot/kernel.ruxe
  /bin/init.ruxe
```

There is no required `KERN` partition. The kernel is a regular file in `ROOT`.

## Explicit Non-Goals

- FAT32 compatibility.
- A separate boot micro filesystem.
- POSIX permissions.
- Symlinks or hard links.
- Journaling.
- Memory mapping.
- Multi-process locking.
- Host directory passthrough.

## Validation Rules

Readers and tooling must reject malformed structures deterministically:

- bad magic or unsupported version;
- unsupported block size;
- metadata ranges outside the partition;
- metadata ranges overlapping incorrectly;
- root inode missing or not a directory;
- inode extent outside the partition;
- inode extent overlapping metadata;
- file size larger than its extents;
- directory entry pointing to a missing/free inode;
- duplicate live names in one directory;
- invalid UTF-8 names;
- unsupported extent count.

No fallback probing should be added. If a structure is missing or malformed, report the specific error.

## Implementation Slices

1. Implement RuxFS encode/decode/validation over an in-memory partition image.
2. Add formatter tests for deterministic metadata layout.
3. Add directory and file operation tests.
4. Add CLI tooling to format `BOOT` and `ROOT` partitions created by #54.
5. Add CLI tooling to write `/boot/loader.ruxe`, `/boot/kernel.ruxe`, and `/bin/init.ruxe`.
6. Add read-only guest/firmware reader support for path lookup and full-file reads.
