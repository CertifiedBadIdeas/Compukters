---
layout: default
title: Guest filesystem
description: Portable paths, binary I/O, UTF-8 text and atomic file replacement.
section: developers
permalink: /FILESYSTEM/
---

# Guest filesystem

Guest programs use `compukter.filesystem.Path` in the computer's virtual filesystem. Rust owns the files,
permissions and persistence. `/home` is private persistent storage and `/rom` is immutable packaged content.
The API uses its own types and has no JVM filesystem classes, host paths, working directory, symlinks or
platform-dependent separators.

## Paths and ordinary operations

```kotlin
import compukter.filesystem.Path

fun main() {
    val directory = Path("/home/notes")
    directory.createDirectories()
    val note = directory.resolve("hello.txt")
    note.writeText("Hello, 世界!\n")
    println(note.readText())
    println(note.stat().sizeBytes)
    for (entry in directory.list()) println(entry.name)
}
```

Paths are exact and absolute. Empty components, trailing slashes except `/`, `.` and `..`, NUL, malformed
Unicode and native path-limit violations are rejected. `resolve` accepts a relative name against an explicit
base; it does not normalize dot traversal. `name` and `parent` are null for `/`. Path construction validates
with the authoritative native parser; a local 4096 UTF-16-unit preflight keeps oversized paths out of the
host transport. Native limits additionally bound UTF-8 path bytes to 4096, components to 255 bytes and depth
to 64 in the default filesystem.

`stat()` returns `FileInfo(kind, sizeBytes, revision, isExecutable)`. `FileKind` distinguishes files and
directories; the revision is an opaque token for comparison. `statOrNull()` returns null only for a missing
entry. `createDirectory()` creates one directory; `createDirectories()` creates missing ancestors through
separate atomic operations and succeeds for an existing directory. `delete()` removes a file or an empty
directory; `deleteIfExists()` returns false only for absence. Neither recursively deletes children.
`moveTo(destination)` fails if the destination exists; `moveTo(destination, true)` requests replacement.
Native mount and permission restrictions still apply. Source and destination paths are transmitted separately.

## Binary and text convenience functions

`readBytes(maximumBytes = 65536)` reads an entire immutable snapshot into a Guest `ByteArray`.
`writeBytes(bytes)` replaces the entire destination atomically, creating it if absent. `readText` and
`writeText` use strict UTF-8: malformed bytes, overlong encodings, surrogate scalars and unpaired source
surrogates produce `FileSystemException` with `INVALID_UTF8`. No charset registry, platform encoding or
newline conversion is involved. U+0000 is valid in file contents.

The read bound is an encoded byte count, independent of UTF-16 text length and Guest heap capacity. Larger
whole-file reads need an explicit bound. These conveniences allocate the complete result and may exhaust
the Guest heap; use a reader for larger files. Ordinary VM memory, execution and cancellation limits remain
in force. The default filesystem allows files up to 8 MiB and 64 MiB of persistent logical data.

## Sequential reads and atomic writes

```kotlin
import compukter.filesystem.Path
import compukter.io.use

fun main() {
    Path("/home/source.bin").openRead().use { reader ->
        Path("/home/copy.bin").openWrite().use { writer ->
            while (true) {
                val bytes = reader.read()
                if (bytes.size == 0) break
                writer.write(bytes)
            }
            writer.commit()
        }
    }
}
```

`FileReader` pins the content observed at open time. Replacement, rename, deletion or path reuse never
redirect it to another file. Its `sizeBytes` describes that snapshot. `read(maximumBytes = 4095)` advances
sequentially and accepts 1..4095 bytes; an empty array means EOF. Calls remain bounded even when the file
is larger than a host message. There is no implicit seek or live view.

`AtomicFileWriter` owns an append-only private draft. `write` divides supplied arrays into bounded transfers;
`commit` performs one guarded native replacement and consumes the writer. Opening, writing, closing,
hibernating and exiting a program do not publish a draft. If the destination changes after open, commit
fails with `BUSY`; reopening starts with the current revision. Any failed native append poisons the draft,
so catching that failure and calling commit cannot publish a prefix. Close it and open a new writer to retry.
Writes create ordinary non-executable files; executable installation stays behind compiler/deployment APIs.

Readers, writers and directory readers implement `compukter.io.Closeable`. `close()` is idempotent; other
operations on a closed resource throw `CLOSED`. `use` closes after success or failure. If both the body and
cleanup throw, the body exception wins; Guest `Throwable` does not currently expose suppressed exceptions.
A successful writer commit counts as close, so the surrounding `use` does not publish or commit twice.

## Directory snapshots and lifetime

`openDirectory()` captures sorted child names. `DirectoryReader.read()` returns a child `Path` or null at
the end; later mutations do not alter that snapshot. The returned paths can independently become absent.
`list(maximumEntries = 256)` collects a bounded list from this same contract. Use `openDirectory().use`
when visiting a larger directory without collecting every Path in the Guest heap.

Resources belong to the foreground program and can be shared by its cooperative tasks. A suspended parent
keeps its resources; child completion releases only the child's resources. Root completion, traps and
shutdown also release resources, aborting unpublished drafts. Resource IDs cannot alias closed resources.
The default native bounds allow at most 256 transfer resources across all live programs and 64 MiB of
retained snapshot/draft storage, independently of the persistent logical quota. Transfer capacity is included
in resource observations. Smaller filesystem configuration limits still apply.

Hibernation preserves snapshot content, read/directory positions, private draft bytes, poisoned state and
revision guards. Restoration validates the resources before executing Guest instructions. Cold boot and
explicit reboot release them and retain only published files. This contract uses filesystem capability ABI 2,
`compukter:core` 3.0.0, native ABI 23 and logical checkpoint schema 3; older checkpoints cold boot retaining `/home`.

## Errors and migration from the old facade

`FileSystemException` extends `compukter.io.IOException` and exposes `reason`, `path`, and `operation`.
`FileSystemError` distinguishes invalid paths, absence, collisions, directory errors, permissions/read-only
storage, stale or closed resources, quota exhaustion, busy/conflicting entries, storage faults and invalid UTF-8.
Invalid API arguments such as negative collection bounds or an out-of-range read chunk throw
`IllegalArgumentException`. VM quota exhaustion and cancellation are not filesystem-domain errors.

The 0.6.0 source API deliberately removes `FileSystem.stat/list/readText/writeText(String, ...)` without
compatibility wrappers. Replace string paths with `Path`, inspect `FileInfo` instead of integer kind/status
codes, and iterate child Paths instead of splitting a NUL-delimited string. Writes return Unit and throw typed
errors. Existing native filesystem ABI 1 remains admitted for historical executable artifacts; new Guest
source and libraries emit ABI 2. Dependent platform/addon inputs must rebuild against the new module identity.

Executable support and automated evidence are recorded in
[Guest platform support]({{ '/GUEST-PLATFORM-SUPPORT/#filesystem-facade' | relative_url }}).
