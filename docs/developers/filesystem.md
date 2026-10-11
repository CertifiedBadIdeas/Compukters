---
layout: default
title: Guest filesystem
description: Portable paths, binary I/O, UTF-8 text and atomic file replacement.
section: developers
permalink: /FILESYSTEM/
---

# Guest filesystem

Guest programs pass `compukter.filesystem.Path` values to `compukter.filesystem.FileSystem`. Path is an
immutable lexical value: constructing it, comparing it, or resolving a child never accesses storage or
submits a native request. FileSystem owns access to the current computer's virtual storage. Rust owns the files,
permissions and persistence. `/home` is private persistent storage and `/rom` is immutable packaged content.
The API uses its own types and has no JVM filesystem classes, host paths, working directory, symlinks or
platform-dependent separators.

## Paths and ordinary operations

```kotlin
import compukter.filesystem.Path
import compukter.filesystem.FileSystem

fun main() {
    val directory = Path("/home/notes")
    FileSystem.createDirectories(directory)
    val note = directory.resolve("hello.txt")
    FileSystem.writeText(note, "Hello, 世界!\n")
    println(FileSystem.readText(note))
    println(FileSystem.stat(note).sizeBytes)
    for (entry in FileSystem.list(directory)) println(entry.name)
}
```

`String.toPath()` is available with an explicit `import compukter.filesystem.toPath`:

```kotlin
val note = "/home/notes/hello.txt".toPath()
```

It delegates to `Path(this)`, uses the same syntax validation and never accesses storage.

Paths are exact and absolute. Empty components, trailing slashes except `/`, `.` and `..`, NUL, malformed
Unicode are rejected with `IllegalArgumentException` by pure Guest syntax validation. `resolve` accepts
a relative name against an explicit base; it does not normalize dot traversal. `name` and `parent` are null
for `/`. Path values do not carry storage identity, permissions, existence or size limits. FileSystem checks a
4096 UTF-16-unit transport bound before submitting path arguments. Its native parser additionally enforces
configured limits: 4096 UTF-8 path bytes, 255-byte components and depth 64 by default. A syntactically valid
Path exceeding those limits can still be constructed; a storage operation on it throws typed `INVALID_PATH`.

`FileSystem.stat(path)` returns `FileInfo(kind, sizeBytes, revision, isExecutable)`. `FileKind` distinguishes files and
directories; the revision is an opaque token for comparison. `FileSystem.statOrNull(path)` returns null only for a missing
entry. `FileSystem.createDirectory(path)` creates one directory; `FileSystem.createDirectories(path)` creates missing ancestors through
separate atomic operations and succeeds for an existing directory. `FileSystem.delete(path)` removes a file or an empty
directory; `FileSystem.deleteIfExists(path)` returns false only for absence. Neither recursively deletes children.
`FileSystem.move(source, destination)` fails if the destination exists; `FileSystem.move(source, destination, true)` requests replacement.
Native mount and permission restrictions still apply. Source and destination paths are transmitted separately.

## Optional Path extensions

Separately imported functions in `compukter.io.path` delegate to FileSystem. They do not change Path's
members or attach a filesystem to its value. For example:

```kotlin
import compukter.filesystem.Path
import compukter.io.path.readText
import compukter.io.path.writeText

fun main() {
    val note = Path("/home/note.txt")
    note.writeText("Explicitly imported I/O convenience")
    println(note.readText())
}
```

The extension package also exposes metadata, directory and resource operations. Its failure, quota,
snapshot and atomicity contracts are exactly those of the corresponding FileSystem operations.

## Binary and text convenience functions

`FileSystem.readBytes(path, maximumBytes = 65536)` reads an entire immutable snapshot into a Guest `ByteArray`.
`FileSystem.writeBytes(path, bytes)` replaces the entire destination atomically, creating it if absent. `FileSystem.readText` and
`FileSystem.writeText` use strict UTF-8: malformed bytes, overlong encodings, surrogate scalars and unpaired source
surrogates produce `FileSystemException` with `INVALID_UTF8`. No charset registry, platform encoding or
newline conversion is involved. U+0000 is valid in file contents.

The read bound is an encoded byte count, independent of UTF-16 text length and Guest heap capacity. Larger
whole-file reads need an explicit bound. These conveniences allocate the complete result and may exhaust
the Guest heap; use a reader for larger files. Ordinary VM memory, execution and cancellation limits remain
in force. The default filesystem allows files up to 8 MiB and 64 MiB of persistent logical data.

## Sequential reads and atomic writes

```kotlin
import compukter.filesystem.Path
import compukter.filesystem.FileSystem
import compukter.io.use

fun main() {
    FileSystem.openRead(Path("/home/source.bin")).use { reader ->
        FileSystem.openWrite(Path("/home/copy.bin")).use { writer ->
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
fails with `BUSY`; reopening starts with the current revision. This guard covers open-to-commit; it does not
automatically compare against a stat or read performed before opening the writer. Any failed native append poisons the draft,
so catching that failure and calling commit cannot publish a prefix. Close it and open a new writer to retry.
Writes create ordinary non-executable files; executable installation stays behind compiler/deployment APIs.
Atomicity describes publication in the virtual filesystem. Disk durability follows the world store
[flush/save lifecycle]({{ '/ARCHITECTURE/persistence/' | relative_url }}); commit is not a Guest fsync control.

Readers, writers and directory readers implement `compukter.io.Closeable`. `close()` is idempotent; other
operations on a closed resource throw `CLOSED`. `use` closes after success or failure. If both the body and
cleanup throw, the body exception wins; Guest `Throwable` does not currently expose suppressed exceptions.
A successful writer commit counts as close, so the surrounding `use` does not publish or commit twice.

## Directory snapshots and lifetime

`FileSystem.openDirectory(path)` captures sorted child names. `DirectoryReader.read()` returns a child `Path` or null at
the end; later mutations do not alter that snapshot. The returned paths can independently become absent.
`FileSystem.list(path, maximumEntries = 256)` collects a bounded list from this same contract. Use `FileSystem.openDirectory(path).use`
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
`compukter:core` 4.0.0, native ABI 23 and logical checkpoint schema 3. Checkpoints from native ABI 22 or
earlier cold boot retaining `/home`.

## Errors and migration from the old facade

`FileSystemException` extends `compukter.io.IOException` and exposes `reason`, `path`, and `operation`.
`FileSystemError` distinguishes invalid paths, absence, collisions, directory errors, permissions/read-only
storage, stale or closed resources, quota exhaustion, busy/conflicting entries, storage faults and invalid UTF-8.
Invalid API arguments such as negative collection bounds or an out-of-range read chunk throw
`IllegalArgumentException`. VM quota exhaustion and cancellation are not filesystem-domain errors.

The 0.6.0 source API deliberately removes `FileSystem.stat/list/readText/writeText(String, ...)` without
compatibility wrappers. Pass Path values to FileSystem, inspect FileInfo instead of integer kind/status
codes, and iterate child Paths instead of splitting a NUL-delimited string. Writes return Unit and throw typed
errors. Path has no storage methods; optional separately imported extensions delegate to FileSystem.
Existing native filesystem ABI 1 remains admitted for historical executable artifacts; new Guest
source and libraries emit ABI 2. Dependent platform/addon inputs must rebuild against the new module identity.

Executable support and automated evidence are recorded in
[Guest platform support]({{ '/GUEST-PLATFORM-SUPPORT/#filesystem-facade' | relative_url }}).

## Design lessons from File and NIO.2

The [JSR-203 rationale](https://jcp.org/en/jsr/detail?id=203) and
[Oracle's legacy File guide](https://docs.oracle.com/javase/tutorial/essential/io/legacy.html) identify
ambiguous errors, inconsistent path/rename behavior, inefficient metadata access and directory scalability
as reasons for a new filesystem API. This API applies those lessons to bounded Guest storage:

| Design concern | Guest contract |
| --- | --- |
| Path versus storage | Pure Path values; explicit FileSystem operations; separately imported conveniences. |
| Ambiguous failure values | Typed exceptions; statOrNull returns null only for NOT_FOUND, and deleteIfExists returns false only for absence. |
| Multiple metadata requests | One typed FileInfo response; metadata describes an observation, not a future guarantee. |
| Large files and directories | Bounded whole-file/collection convenience, closeable readers and directory cursors; native directory snapshots still allocate bounded storage. |
| Check-then-act races | Native operations check permissions and limits at action time; snapshots pin content and write commit checks its revision guard. |
| Partial destructive writes | Private atomic drafts, explicit commit, close aborts; createDirectories is explicitly a sequence of separate atomic operations. |
| Platform-specific behavior | One documented separator and strict UTF-8; no implicit host working directory or OS-dependent path/charset rules. |

NIO.2 remains a reference rather than a complete template. Its
[Path](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Path.html) is associated
with a filesystem provider and has some storage operations. Its
[Files.exists](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#exists(java.nio.file.Path,java.nio.file.LinkOption...))
can return false for indeterminate access, and prechecks can become stale immediately. Its
[DirectoryStream](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/DirectoryStream.html)
is unordered and weakly consistent; Guest directory cursors instead enumerate a sorted name snapshot.
No provider SPI, symbolic links, watchers or asynchronous I/O are implied by this API.
