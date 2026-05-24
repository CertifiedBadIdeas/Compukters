# Rux World-Backed Volume Blobs Implementation Plan

> Issue: [#53](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/53)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tested file-backed Rux volume blob layer for the first internal computer-owned `storage0` volume.

**Architecture:** Implement the reusable raw storage layer in `modules/native-runtime`, independent from Minecraft classes and old Kotlin VM fallback paths. The store takes a world-save root `Path`, validates a typed volume identity, creates `RUXVOL` version `1` containers lazily, and exposes bounded raw payload reads/writes.

**Tech Stack:** Kotlin/JVM 17, `java.nio.file`, `java.io.RandomAccessFile`, `kotlin.test`, Gradle sandbox wrapper.

---

## File Structure

- Create `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/storage/RuxVolumeStore.kt`
  - Owns `RuxVolumeIdentity`, constants, config, errors, `RuxVolumeBlob`, and file-backed implementation.
- Create `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/storage/RuxVolumeStoreTest.kt`
  - Covers create/open/read/write/reopen/resize/corruption behavior.
- Modify `docs/superpowers/specs/2026-05-24-issue-53-rux-world-backed-volume-blobs-design.md`
  - Mark implementation status after code lands.

## Task 1: Add Failing Volume Store Tests

**Files:**
- Create: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/storage/RuxVolumeStoreTest.kt`
- Later create: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/storage/RuxVolumeStore.kt`

- [x] **Step 1: Write the failing tests**

Create `RuxVolumeStoreTest.kt` with tests for:

```kotlin
@Test
fun `open or create creates one mebibyte storage0 volume`() {
    val root = tempDir()
    FileRuxVolumeStore(root).openOrCreateComputerVolume(42, "storage0").use { blob ->
        assertEquals(1024L * 1024L, blob.size)
        assertTrue(root.resolve("compukterkraft/computers/42/volumes/storage0.ruxvol").exists())
    }
}
```

```kotlin
@Test
fun `written bytes survive reopen`() {
    val root = tempDir()
    FileRuxVolumeStore(root).openOrCreateComputerVolume(42, "storage0").use { blob ->
        blob.write(8, byteArrayOf(1, 2, 3, 4))
        blob.flush()
    }
    FileRuxVolumeStore(root).openOrCreateComputerVolume(42, "storage0").use { blob ->
        assertContentEquals(byteArrayOf(1, 2, 3, 4), blob.read(8, 4))
    }
}
```

```kotlin
@Test
fun `invalid magic fails deterministically`() {
    val path = createCorruptVolume(magic = "BADVOL".encodeToByteArray())
    val failure = assertFailsWith<RuxVolumeException> {
        FileRuxVolumeStore(path.root).openOrCreateComputerVolume(42, "storage0")
    }
    assertEquals(RuxVolumeError.InvalidMagic, failure.error)
}
```

Also cover unsupported version, truncated header, truncated payload, out-of-bounds read/write, resize growth zero-fill, resize shrink.

- [x] **Step 2: Run tests and verify red**

Run:

```bash
./gradlew-sandbox :native-runtime:test --tests '*RuxVolumeStoreTest*'
```

Expected: compile failure because `FileRuxVolumeStore`, `RuxVolumeBlob`, `RuxVolumeException`, and `RuxVolumeError` do not exist.

## Task 2: Implement File-Backed Rux Volume Store

**Files:**
- Create: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/storage/RuxVolumeStore.kt`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/storage/RuxVolumeStoreTest.kt`

- [x] **Step 1: Add minimal production API**

Implement:

```kotlin
sealed interface RuxVolumeIdentity {
    data class ComputerOwned(val computerId: Int, val slot: String) : RuxVolumeIdentity
}

enum class RuxVolumeError {
    InvalidIdentity,
    InvalidMagic,
    UnsupportedVersion,
    TruncatedHeader,
    TruncatedPayload,
    InvalidLogicalSize,
    OutOfBounds,
    Closed,
    IoFailure,
}

class RuxVolumeException(
    val error: RuxVolumeError,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface RuxVolumeBlob : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    fun write(offset: Long, bytes: ByteArray)
    fun resize(newSize: Long)
    fun flush()
}
```

- [x] **Step 2: Implement file layout**

Implement constants:

```kotlin
const val RUX_VOLUME_MAGIC = "RUXVOL"
const val RUX_VOLUME_VERSION: UShort = 1u
const val DEFAULT_STORAGE0_SIZE: Long = 1024L * 1024L
private const val HEADER_SIZE = 16L
```

Implement file header:

```text
0x00  6  RUXVOL
0x06  2  version little-endian u16
0x08  8  logical_size little-endian u64
0x10  N  payload
```

- [x] **Step 3: Implement `openOrCreateComputerVolume`**

For `ComputerOwned(42, "storage0")`, resolve:

```text
<root>/compukterkraft/computers/42/volumes/storage0.ruxvol
```

Reject non-positive computer ids and slots other than `[A-Za-z0-9_-]+`.

- [x] **Step 4: Run focused tests**

Run:

```bash
./gradlew-sandbox :native-runtime:test --tests '*RuxVolumeStoreTest*'
```

Expected: PASS.

## Task 3: Add Runtime Integration Seam

**Files:**
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntime.kt`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntimeFactoryTest.kt`

- [x] **Step 1: Add a storage media parameter that defaults to absent**

Add an optional `storage0Media: ByteArray? = null` parameter to `RuxComputerRuntimeFactory.create(...)`.

The implementation passes the media into the native `ComputerMachine` profile and exposes `storage0MediaSnapshot` for persistence on runtime close.

- [x] **Step 2: Add test for backward compatibility**

Verify existing calls still compile and create through the current fake bindings/factory tests.

- [x] **Step 3: Run focused tests**

Run:

```bash
./gradlew-sandbox :native-runtime:test
```

Expected: PASS.

## Task 4: Update Status Docs

**Files:**
- Modify: `docs/superpowers/specs/2026-05-24-issue-53-rux-world-backed-volume-blobs-design.md`

- [x] **Step 1: Update status**

Change:

```markdown
Draft for review.
```

to:

```markdown
Implemented first slice.
```

- [x] **Step 2: Run final focused verification**

Run:

```bash
./gradlew-sandbox :native-runtime:test
```

Expected: PASS.

## Self-Review Notes

- Spec coverage: host-side identity, path layout, `RUXVOL` v1 header, `1 MiB` default, corruption handling, and raw blob API are covered.
- Explicitly deferred: native `storage0` attachment, boot discovery, filesystem, partitions, removable items.
- No guest ABI changes are planned.
