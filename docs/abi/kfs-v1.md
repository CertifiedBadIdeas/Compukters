# KFS v1

## Status

Status: experimental.

KFS v1 is the filesystem used for the `ROOT` partition in partitioned `K16PT`
storage media. KraftOS uses one writable instance for storage0 and may mount a
second read-only instance from storage1 at `/sdk`. KFS is an extent-based
filesystem with a fixed 512-byte block size, one superblock, one allocation
bitmap, and one inode table.

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
magic       "KFS\0\0"
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

KFS v1 currently supports up to 4 inline extents per inode. Extents must be
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
- directory listing;
- host-side directory and file creation through `k16 fs kfs`, using the same
  bounded inline extent growth model as guest-side mutation;
- guest-side create/truncate/write for regular files through the kernel fd ABI,
  plus append and bounded seek through the same fd ABI;
- guest-side regular-file unlink through the kernel fd ABI, using deleted
  inode and directory-entry states and freeing the file's data blocks;
- guest-side directory creation and empty-directory removal through the kernel
  syscall ABI, using deleted inode and directory-entry states and freeing the
  removed directory's data blocks;
- guest-side directory growth through the kernel syscall ABI, reusing
  free/deleted entries first and then growing within the bounded inline extent
  model;
- guest-side regular-file growth through the kernel fd ABI, extending the last
  inline extent when possible or adding another bounded inline extent when the
  adjacent blocks are already used.

The host-side read path used to model future bootloader behavior is:

```text
K16VOL -> K16PT ROOT partition -> KFS superblock -> /boot/kernel.kx
```

This reader composes byte-level volume partition extraction with KFS absolute
path lookup. It is not a fallback path and does not make `k16 volume`
filesystem-aware.

The VM runtime also has a storage image reader/writer for the guest-visible
media payload:

```text
storage0 media bytes -> K16PT ROOT partition -> KFS superblock -> absolute path
```

This runtime path starts at LBA0 of the storage media payload. It does not
accept a host `.kv` path and does not strip the 16-byte `K16VOL` host file
header. The storage backend performs that file-to-media translation before the
guest-visible storage path sees any bytes.

The current guest boot chain uses the same KFS structure:

```text
BIOS       -> storage0 BOOT/KFS /boot/loader.kb -> bootloader
bootloader -> storage0 ROOT/KFS /boot/kernel.kx -> kernel
kernel     -> storage0 ROOT/KFS /bin/init.kx    -> init launcher
init       -> storage0 ROOT/KFS /bin/shell.kx   -> shell
shell      -> storage0 ROOT/KFS /bin/*.kx       -> foreground utility
```

After the kernel takes control, its VFS owns independent KFS volume instances:

```text
/       -> storage0 ROOT/KFS, writable
/sdk    -> optional storage1 ROOT/KFS, read-only
```

Each instance owns its storage-device descriptor, mounted partition and
superblock, selected-inode state, path cache, and block cache. Open file
descriptors retain their volume identity, so root and SDK files may remain open
and be read or sought concurrently. VFS path routing never switches one global
active disk.

When storage1 is absent, no SDK volume is mounted. When its hardware entry is
present, an invalid storage profile, K16PT, ROOT partition, or KFS superblock is
a boot failure rather than a root-only fallback. Mutating operations whose
source or destination routes to `/sdk` fail with `ERROR_READ_ONLY` before block
I/O; the storage1 controller independently rejects write commands.

Guest loaders treat missing paths, malformed metadata, or wrong executable ABI
kinds as hard load failures. Shell-launched foreground utilities use the same
K16 `RUN` kernel boundary as init-launched shell startup: the shell resolves the
command to a `/bin/*.kx` path, the kernel opens that file from ROOT/KFS on
`storage0`, validates the dynamic `K16E` program image, and starts the child
process. `RUN` returns a non-negative child exit status after a successful
launch, or a negative K16 error when launch/fault handling fails before normal
child completion. The bundled shell prints non-zero child statuses as
`ERR EXIT <status>` and keeps the latest command status in memory for the
`status` builtin. `status` prints `STATUS <decimal>` for non-negative child or
builtin statuses and `STATUS <error-name>` for known negative K16 errors such
as `NOENT`, `BUSY`, and `FAULT`. There is no bundled-program fallback when the
file is missing.

An explicit executable path matching `/sdk/bin/*.kx` passes through the same
`RUN` loader, K16E validation, shared-object resolution, and child-process
creation path. It is executed from storage1 without copying the executable into
storage0. Imported shared objects continue to resolve from storage0 `/lib`.

For bundled filesystem utilities, the shell resolves relative path arguments
against its current working directory before calling `RUN_ARGV`. This includes
`ls`, `cat`, `cp`, `mv`, `stat`, `write`, `rm`, `mkdir`, and `rmdir`. `write`
resolves only the path argument: `write <path> <payload>` resolves `<path>`,
and `write --append <path> <payload>` preserves `--append` and `<payload>`
verbatim while resolving `<path>`.

Bundled multi-path filesystem utilities use aggregate command status: each path
argument is processed independently where the utility can continue, per-path
success or error output is printed, and the child exits with status `1` if any
path failed. `cat`, `stat`, `ls`, `rm`, `mkdir`, and `rmdir` follow this policy.
Single-operation utilities such as `cp` and `mv` still stop after their one
source/destination operation.

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
standard system files into the active KFS-backed volume layout.

Sparse files, unbounded extents, and extent trees remain next-step work under
the same KFS v1 contract.
