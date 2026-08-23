# Rust-Owned Persistent VFS Implementation Plan

> Issue: [#521](https://github.com/CertifiedBadIdeas/Compukters/issues/521)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Rust-owned world-scoped computer filesystem with immutable `/rom`, persistent isolated `/home`, capability-scoped access, bounded crash recovery, and lifecycle-only FFM/Minecraft integration.

**Architecture:** Pure Rust VFS code owns virtual paths, trees, capabilities, handles, logical quotas, and byte operations. A world-scoped Rust store persists immutable content objects plus checksummed journals/checkpoints through one bounded owned worker; Java passes only an admitted world root, stable `ComputerId`, ROM image, and lifecycle calls. The current direct-shell VM entry remains intact until #518 consumes extensionless ROM executables.

**Tech Stack:** Rust 2021, `sha2`, Rust standard filesystem/thread/channel primitives, C ABI, JDK 25 FFM, Kotlin 2.4, Minecraft/NeoForge 26.1, Kotlin Test, Rust unit/integration tests, GameTest.

---

## File Structure

Create focused Rust files under `host/compukter-vm/src/filesystem/`:

- `mod.rs` — public filesystem surface and exports.
- `error.rs` — typed guest/store errors and health states.
- `limits.rs` — all independent VFS, persistence, and recovery limits.
- `path.rs` — exact Unicode virtual path parser and ordering.
- `tree.rs` — nodes, immutable ROM, mutable home tree, and atomic namespace operations.
- `capability.rs` — subtree authority and non-widening delegation.
- `handle.rs` — generation-tagged bounded open handles.
- `quota.rs` — logical accounting and pre-mutation reservations.
- `rom.rs` — canonical bounded ROM image codec.
- `persistence/codec.rs` — explicit little-endian bounded record primitives.
- `persistence/journal.rs` — mutation records and checksums.
- `persistence/checkpoint.rs` — canonical home-tree checkpoints.
- `persistence/recovery.rs` — confirmed-prefix recovery and corruption classification.
- `store.rs` — world root admission, per-computer stores, tombstones, and object paths.
- `worker.rs` — bounded owned persistence thread and lifecycle state machine.

Create integration tests under `host/compukter-vm/tests/` so persistence is exercised through the public API. Extend `host/compukter-ffi` only after the Rust boundary is stable. Add Kotlin lifecycle models to `modules/native-runtime`, then thread stable identity and world-store ownership through `v26_1-common` and NeoForge lifecycle events.

## Task 1: Virtual Paths, Errors, and Limits

**Files:**
- Create: `host/compukter-vm/src/filesystem/mod.rs`
- Create: `host/compukter-vm/src/filesystem/error.rs`
- Create: `host/compukter-vm/src/filesystem/limits.rs`
- Create: `host/compukter-vm/src/filesystem/path.rs`
- Modify: `host/compukter-vm/src/lib.rs`
- Test: `host/compukter-vm/src/filesystem/path.rs`

- [ ] **Step 1: Write failing path and limit tests**

Add module tests that prove `/`, `/rom/boot`, and non-ASCII scalar names parse; reject relative paths, empty components, `.`, `..`, NUL, unpaired surrogate input represented as UTF-16, excessive depth, component bytes, and total bytes; and sort exact scalar sequences deterministically.

```rust
#[test]
fn paths_are_absolute_exact_and_bounded() {
    let limits = FileSystemLimits::testing();
    assert_eq!(VirtualPath::parse_utf16(&units("/home/λ.kt"), &limits).unwrap().to_string(), "/home/λ.kt");
    for invalid in ["home/a", "/home//a", "/home/./a", "/home/../a", "/home/a\0b"] {
        assert_eq!(VirtualPath::parse_utf16(&units(invalid), &limits), Err(FileSystemError::InvalidPath));
    }
}

#[test]
fn unpaired_utf16_is_not_a_virtual_name() {
    assert_eq!(
        VirtualPath::parse_utf16(&[b'/' as u16, 0xD800], &FileSystemLimits::testing()),
        Err(FileSystemError::InvalidPath),
    );
}
```

- [ ] **Step 2: Run the focused Rust test and verify RED**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected: compile failure because `filesystem`, `VirtualPath`, and `FileSystemLimits` do not exist.

- [ ] **Step 3: Implement the complete foundational types**

Define `FileSystemError` with the spec codes and a separate `StoreHealth { Active, Draining, Faulted, Closed }`. Define `FileSystemLimits` with positive defaults and a smaller `testing()` constructor. Parse UTF-16 with `char::decode_utf16`, reject every invalid scalar/component before allocation growth, store components as `Box<str>`, and implement `Display`, `Ord`, and subtree-prefix checks over components rather than text prefixes.

```rust
#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct VirtualPath(Box<[Box<str>]>);

impl VirtualPath {
    pub fn parse_utf16(units: &[u16], limits: &FileSystemLimits) -> Result<Self, FileSystemError>;
    pub fn components(&self) -> impl ExactSizeIterator<Item = &str>;
    pub fn is_within(&self, root: &Self) -> bool;
    pub fn parent(&self) -> Option<Self>;
    pub fn file_name(&self) -> Option<&str>;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FileSystemError {
    InvalidPath,
    NotFound,
    AlreadyExists,
    NotDirectory,
    IsDirectory,
    NotEmpty,
    ReadOnly,
    PermissionDenied,
    StaleHandle,
    QuotaExceeded,
    Busy,
    StorageFaulted,
    Closed,
}
```

- [ ] **Step 4: Verify GREEN and public exports**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected: all existing Rust tests plus new path tests pass.

- [ ] **Step 5: Commit**

```bash
git add host/compukter-vm/src/filesystem host/compukter-vm/src/lib.rs
git commit -m "feat(fs): add bounded virtual paths (#521)"
```

## Task 2: In-Memory Mounts, Nodes, Capabilities, and Handles

**Files:**
- Create: `host/compukter-vm/src/filesystem/tree.rs`
- Create: `host/compukter-vm/src/filesystem/capability.rs`
- Create: `host/compukter-vm/src/filesystem/handle.rs`
- Modify: `host/compukter-vm/src/filesystem/mod.rs`
- Test: `host/compukter-vm/src/filesystem/tree.rs`
- Test: `host/compukter-vm/src/filesystem/capability.rs`
- Test: `host/compukter-vm/src/filesystem/handle.rs`

- [ ] **Step 1: Write failing mount and authority tests**

Cover immutable `/rom`, isolated mutable `/home`, deterministic listings, atomic rename/replace, rejection of non-empty directory removal, generation-tagged stale handles, exact subtree checks, and inability to widen delegated authority.

```rust
#[test]
fn delegation_can_only_narrow_authority() {
    let owner = FileCapability::new(path("/home/project"), FileRights::READ | FileRights::WRITE | FileRights::DELETE);
    assert!(owner.delegate(path("/home/project/src"), FileRights::READ).is_ok());
    assert_eq!(owner.delegate(path("/home"), FileRights::READ), Err(FileSystemError::PermissionDenied));
    assert_eq!(owner.delegate(path("/home/project"), FileRights::EXECUTE), Err(FileSystemError::PermissionDenied));
}

#[test]
fn stale_handle_never_targets_a_reused_slot() {
    let mut handles = HandleTable::new(1);
    let first = handles.open(OpenFile::testing()).unwrap();
    handles.close(first).unwrap();
    let second = handles.open(OpenFile::testing()).unwrap();
    assert_ne!(first, second);
    assert_eq!(handles.get(first), Err(FileSystemError::StaleHandle));
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected: compile failure for the new node, capability, and handle types.

- [ ] **Step 3: Implement the in-memory model**

Use `BTreeMap<Box<str>, Node>` for deterministic directories. Keep ROM and home roots as different variants so every ROM mutation returns `ReadOnly` before touching quota state. Implement rights as a validated `u16` bitset without an external crate.

```rust
pub struct ComputerFileSystem {
    rom: Directory,
    home: Directory,
    handles: HandleTable,
    quotas: QuotaLedger,
    generation: u64,
}

pub struct FileCapability {
    root: VirtualPath,
    rights: FileRights,
    logical_byte_limit: u64,
    operation_limit: u64,
    handle_limit: u32,
}

impl FileCapability {
    pub fn permits(&self, path: &VirtualPath, right: FileRight) -> bool;
    pub fn delegate(&self, root: VirtualPath, rights: FileRights) -> Result<Self, FileSystemError>;
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct FileHandle {
    slot: u32,
    generation: u32,
}
```

Implement `stat`, `list`, `create_directory`, `remove`, and `rename` as tree operations taking an explicit capability. `rename` first validates both source and destination and then performs one mutation; on any error the tree and generation remain unchanged.

- [ ] **Step 4: Run focused and complete Rust tests**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected: all tests pass with exact deterministic listing assertions.

- [ ] **Step 5: Commit**

```bash
git add host/compukter-vm/src/filesystem
git commit -m "feat(fs): add capability-scoped namespace (#521)"
```

## Task 3: Byte I/O, Logical Quotas, and Pre-Mutation Reservations

**Files:**
- Create: `host/compukter-vm/src/filesystem/quota.rs`
- Modify: `host/compukter-vm/src/filesystem/tree.rs`
- Modify: `host/compukter-vm/src/filesystem/handle.rs`
- Modify: `host/compukter-vm/src/filesystem/mod.rs`
- Test: `host/compukter-vm/tests/filesystem_memory.rs`

- [ ] **Step 1: Write failing public API tests**

Test create/open/read/write/truncate/close, bounded partial reads, explicit executable metadata, logical byte accounting, no mutation when any quota reservation fails, and logical charging despite two identical files sharing a content hash.

```rust
#[test]
fn failed_reservation_leaves_bytes_generation_and_tree_unchanged() {
    let mut fs = filesystem_with_home_limit(4);
    let owner = owner_capability();
    fs.write_file(&owner, &path("/home/a"), b"1234", false).unwrap();
    let before = fs.snapshot_for_test();
    assert_eq!(fs.write_file(&owner, &path("/home/b"), b"x", false), Err(FileSystemError::QuotaExceeded));
    assert_eq!(fs.snapshot_for_test(), before);
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected: compile failure because byte operations and `QuotaLedger` are absent.

- [ ] **Step 3: Implement reservation-first mutations**

Define a `MutationCost` and an RAII-free explicit reservation that is computed without mutation, checked against all limits, and committed only after the tree update succeeds.

```rust
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
struct MutationCost {
    logical_bytes_added: u64,
    nodes_added: u32,
    journal_bytes: u64,
    queue_records: u32,
}

impl QuotaLedger {
    fn reserve(&self, cost: MutationCost) -> Result<QuotaReservation, FileSystemError>;
    fn commit(&mut self, reservation: QuotaReservation);
}
```

Store file content in immutable `Arc<[u8]>` values addressed by SHA-256 within the in-memory object table. `write` uses checked arithmetic, copy-on-write replacement, and exact accepted-byte results. `open` validates handle and capability ceilings before allocating a slot. `truncate` zero-fills growth and never changes a file on overflow or quota error.

- [ ] **Step 4: Run Rust tests and Clippy**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust clippyCompukterFfiRust --rerun-tasks`

Expected: tests pass and Clippy reports no warnings.

- [ ] **Step 5: Commit**

```bash
git add host/compukter-vm/src/filesystem host/compukter-vm/tests/filesystem_memory.rs
git commit -m "feat(fs): add bounded byte operations (#521)"
```

## Task 4: Canonical ROM Image

**Files:**
- Create: `host/compukter-vm/src/filesystem/rom.rs`
- Modify: `host/compukter-vm/src/filesystem/mod.rs`
- Test: `host/compukter-vm/tests/filesystem_rom.rs`

- [ ] **Step 1: Write failing canonicality and admission tests**

Test valid directory/file entries, executable flags, hash stability, rejection of duplicate/non-canonical paths, non-parent-first entries, invalid flags, oversized images, writable-root attempts, and trailing bytes.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected: compile failure because `RomImage::admit` is absent.

- [ ] **Step 3: Implement the exact ROM wire format**

Use this little-endian layout, with every count/length checked against `FileSystemLimits` before allocation:

```text
magic[8]      = "CPKTROM\0"
major u16     = 1
minor u16     = 0
entryCount u32
for each entry in parent-first scalar-lexicographic path order:
  pathUtf8Length u32
  pathUtf8 bytes
  kind u8       (1 directory, 2 file)
  flags u8      (bit 0 executable; zero for directory)
  reserved u16  (must be zero)
  contentLength u64
  content bytes (file only; zero for directory)
sha256[32] over every preceding byte
```

Expose only an admitted immutable image:

```rust
pub struct RomImage {
    digest: [u8; 32],
    root: Directory,
    encoded_bytes: Arc<[u8]>,
}

impl RomImage {
    pub fn admit(bytes: Arc<[u8]>, limits: &FileSystemLimits) -> Result<Self, RomImageError>;
    pub fn digest(&self) -> [u8; 32];
}
```

- [ ] **Step 4: Verify deterministic round trip**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected: all ROM fixtures admit identically and every malformed fixture is rejected with the asserted code.

- [ ] **Step 5: Commit**

```bash
git add host/compukter-vm/src/filesystem host/compukter-vm/tests/filesystem_rom.rs
git commit -m "feat(fs): admit canonical ROM images (#521)"
```

## Task 5: Journal, Checkpoint, and Deterministic Recovery

**Files:**
- Create: `host/compukter-vm/src/filesystem/persistence/mod.rs`
- Create: `host/compukter-vm/src/filesystem/persistence/codec.rs`
- Create: `host/compukter-vm/src/filesystem/persistence/journal.rs`
- Create: `host/compukter-vm/src/filesystem/persistence/checkpoint.rs`
- Create: `host/compukter-vm/src/filesystem/persistence/recovery.rs`
- Modify: `host/compukter-vm/src/filesystem/mod.rs`
- Test: `host/compukter-vm/tests/filesystem_recovery.rs`

- [ ] **Step 1: Write failing codec and recovery tests**

Use deterministic in-memory byte fixtures to test round trips, torn final records, checksum corruption, generation gaps, identity mismatch, confirmed checkpoint corruption, bounded recovery work, and rejection of trailing bytes.

```rust
#[test]
fn torn_unconfirmed_tail_is_discarded_but_confirmed_corruption_faults() {
    let fixture = RecoveryFixture::with_checkpoint(4).with_record(5).with_torn_record(6);
    assert_eq!(recover(&fixture.bytes(), fixture.limits()).unwrap().generation(), 5);
    let corrupt = fixture.corrupt_confirmed_checkpoint();
    assert_eq!(recover(&corrupt.bytes(), corrupt.limits()), Err(RecoveryError::ConfirmedCorruption));
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected: compile failure because persistence codecs and `recover` do not exist.

- [ ] **Step 3: Implement explicit bounded codecs**

Journal records use:

```text
magic[8] = "CPKTJNL\0"
version u16 = 1
flags u16 = 0
computerId[16]
sequence u64
previousSequence u64
payloadLength u32
payload bytes
sha256[32] over header and payload
```

Checkpoints use:

```text
magic[8] = "CPKTCHK\0"
version u16 = 1
flags u16 = 0
computerId[16]
generation u64
nodeCount u32
canonical node records in parent-first path order
sha256[32] over every preceding byte
```

Decode through a cursor that uses checked addition and refuses an allocation until its count and byte length are within the independent recovery limits. Recovery chooses the newest complete valid checkpoint, applies only a contiguous matching journal suffix, discards only an unconfirmed tail, and returns `ConfirmedCorruption` for any invalid confirmed history.

- [ ] **Step 4: Run recovery tests twice for determinism**

Run twice: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected both times: identical passing test count and no filesystem-order-dependent failures.

- [ ] **Step 5: Commit**

```bash
git add host/compukter-vm/src/filesystem host/compukter-vm/tests/filesystem_recovery.rs
git commit -m "feat(fs): add crash-safe recovery formats (#521)"
```

## Task 6: World Store and Owned Persistence Worker

**Files:**
- Create: `host/compukter-vm/src/filesystem/store.rs`
- Create: `host/compukter-vm/src/filesystem/worker.rs`
- Modify: `host/compukter-vm/src/filesystem/mod.rs`
- Test: `host/compukter-vm/tests/filesystem_store.rs`

- [ ] **Step 1: Write failing real-directory lifecycle tests**

Create unique explicit test roots below `std::env::temp_dir()/compukters-vfs-tests/<process-id>-<atomic-id>`, clean only that validated root in a guard, and test: absolute canonical root admission, symlink root rejection on supported platforms, exclusive lock, async durable generation, bounded queue backpressure, restart recovery, object hash verification, bounded reachability collection that retains referenced objects and removes only unreachable ones, faulted read-only degradation, drain/join, and no writes after close.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust --rerun-tasks`

Expected: compile failure because `WorldFileSystemStore` is absent.

- [ ] **Step 3: Implement root layout and store ownership**

Expose this public boundary:

```rust
pub struct WorldFileSystemStore;

impl WorldFileSystemStore {
    pub fn open(root: &Path, limits: FileSystemLimits) -> Result<Arc<Self>, StoreOpenError>;
    pub fn open_computer(&self, id: ComputerId, rom: Arc<RomImage>) -> Result<ComputerFileSystem, StoreError>;
    pub fn health(&self) -> StoreHealth;
    pub fn durable_generation(&self, id: ComputerId) -> Result<u64, StoreError>;
    pub fn flush(&self, id: ComputerId, generation: u64) -> Result<(), StoreError>;
    pub fn tombstone(&self, id: ComputerId) -> Result<(), StoreError>;
    pub fn recover_tombstone(&self, id: ComputerId) -> Result<(), StoreError>;
    pub fn close(&self) -> Result<(), StoreError>;
}
```

Use `OpenOptions::create_new(true)` for the world lock and retain the lock file descriptor until close. Implement `BoundedCommandQueue` with `Mutex<VecDeque<Command>>`, `Condvar`, an explicit reserved-slot count, and a closed flag; `reserve()` increments the reserved count before a mutation, `publish(reservation, command)` converts exactly one reservation into one queued command without allocation failure, and dropping an unused reservation releases it. The worker owns its `JoinHandle`; commands contain only immutable object bytes, encoded records, checkpoint bytes, flush requests, and bounded collection batches. `close` performs Active → Draining → Closed, synchronizes files/directories, publishes Stop, and joins exactly once. An I/O error stores one bounded fault code and rejects future mutations without an unbounded retry loop.

- [ ] **Step 4: Run Rust tests, format, and Clippy**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust fmtCompukterFfiRust clippyCompukterFfiRust --rerun-tasks`

Expected: all store tests pass, formatting is clean, and Clippy has zero warnings.

- [ ] **Step 5: Commit**

```bash
git add host/compukter-vm/src/filesystem host/compukter-vm/tests/filesystem_store.rs
git commit -m "feat(fs): persist world-scoped computer files (#521)"
```

## Task 7: Lifecycle-Only C ABI and JDK 25 FFM

**Files:**
- Modify: `host/compukter-ffi/src/bridge.rs`
- Modify: `host/compukter-ffi/src/ffi_api.rs`
- Modify: `host/compukter-ffi/src/lib.rs`
- Modify: `host/compukter-ffi/src/wire.rs`
- Test: `host/compukter-ffi/src/ffi_api.rs`
- Create: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/fs/ComputerId.kt`
- Create: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/fs/WorldFileSystemStore.kt`
- Create: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/fs/FileSystemModels.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/LowLevelVmBridge.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/FfmBridge.kt`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/fs/WorldFileSystemStoreTest.kt`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/integration/FfmFileSystemIntegrationTest.kt`

- [ ] **Step 1: Write failing Rust ABI and Kotlin fake-bridge tests**

Test invalid UTF-8 roots, relative roots, zero/unknown handles, duplicate close, output buffer sizing, computer ID byte order, health decoding, flush generation, tombstone/recovery, and store close while a computer lease remains active.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew-sandbox-dev-parallel testCompukterFfiRust :native-runtime:test --rerun-tasks`

Expected: missing ABI symbols and Kotlin bridge methods.

- [ ] **Step 3: Add explicit lifecycle ABI v2**

Increment `compukter_abi_version()` to `2` and add:

```c
compukter_store_open(root_utf8, root_len, limits_wire, limits_len, output, capacity, written)
compukter_store_health(store_handle, output, capacity, written)
compukter_store_durable_generation(store_handle, id[16], output, capacity, written)
compukter_store_flush(store_handle, id[16], generation)
compukter_store_tombstone(store_handle, id[16])
compukter_store_recover(store_handle, id[16])
compukter_store_close(store_handle)
```

Use a dedicated `HandleTable<Arc<WorldFileSystemStore>>`; never cast a store handle to a VM handle. Copy root bytes once, decode strict UTF-8, convert to `PathBuf`, and let Rust root admission decide. Wire results contain only versioned status codes, handles, health, and generations; no host paths or raw OS error text crosses FFI.

Kotlin owns the store handle through `AtomicLong`, validates absolute normalized roots before calling native code, copies every input defensively, and guarantees idempotent `close`.

```kotlin
data class ComputerId private constructor(
    val highBits: Long,
    val lowBits: Long,
) {
    fun toByteArray(): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putLong(highBits).putLong(lowBits).array()
    companion object {
        fun of(bytes: ByteArray): ComputerId {
            require(bytes.size == 16)
            require(bytes.any { it.toInt() != 0 })
            val buffer = ByteBuffer.wrap(bytes.copyOf()).order(ByteOrder.BIG_ENDIAN)
            return ComputerId(buffer.long, buffer.long)
        }
        fun fromLongs(highBits: Long, lowBits: Long): ComputerId =
            ComputerId(highBits, lowBits).also { require(highBits != 0L || lowBits != 0L) }
    }
}

class WorldFileSystemStore private constructor(
    handle: Long,
    private val bridge: LowLevelVmBridge,
) : AutoCloseable {
    fun health(): FileSystemStoreHealth
    fun durableGeneration(id: ComputerId): Long
    fun flush(id: ComputerId, generation: Long)
    fun tombstone(id: ComputerId)
    fun recover(id: ComputerId)
    override fun close()
}
```

- [ ] **Step 4: Run Rust, JVM, and real FFM tests**

Run: `./gradlew-sandbox-dev-parallel testCompukterFfiRust :native-runtime:test :native-runtime:nativeIntegrationTest --rerun-tasks`

Expected: all ABI, fake bridge, and native integration tests pass under JDK 25 with native access enabled.

- [ ] **Step 5: Commit**

```bash
git add host/compukter-ffi modules/native-runtime
git commit -m "feat(fs): expose world store lifecycle over FFM (#521)"
```

## Task 8: Attach the Filesystem to `ComputerMachine`

**Files:**
- Modify: `host/compukter-vm/src/computer.rs`
- Modify: `host/compukter-vm/src/execution/fixtures.rs`
- Test: `host/compukter-vm/tests/computer_filesystem.rs`
- Modify: `host/compukter-ffi/src/bridge.rs`
- Modify: `host/compukter-ffi/src/ffi_api.rs`
- Modify: `host/compukter-ffi/src/lib.rs`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/LowLevelVmBridge.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/FfmBridge.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/VmSession.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/program/ProgramVmSession.kt`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/integration/FfmFileSystemIntegrationTest.kt`

- [ ] **Step 1: Write failing machine and FFM integration tests**

Build a Rust conformance artifact that uses an internal `compukter/filesystem` ABI to create `/home/project`, write UTF-16 source text, read it back, list the directory, and halt with a deterministic status. Start it in a real world store, close, reopen the same `ComputerId`, and prove the text remains. Start another ID and prove the file is absent. Kotlin FFM integration repeats the first round trip through the native library.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust testCompukterFfiRust :native-runtime:nativeIntegrationTest --rerun-tasks`

Expected: missing `ComputerMachine::start_in_filesystem` and `compukter_create_in_store` boundaries.

- [ ] **Step 3: Give `ComputerMachine` an owned VFS and internal text adapter**

Preserve `ComputerMachine::start` by constructing an explicitly ephemeral in-memory filesystem for low-level VM tests. Add the production constructor:

```rust
pub fn start_in_filesystem(
    artifact: VerifiedArtifact,
    profile: ExecutionProfile,
    addon_bindings: &[CapabilityBinding<'_>],
    arguments: &[EntryValue],
    filesystem: ComputerFileSystem,
    initial_capability: FileCapability,
) -> Result<Self, ComputerStartError>;
```

Bind `compukter/filesystem` ABI major 1 inside `ComputerMachine`, just as the terminal ABI is internal. The first trusted adapter is deliberately text-oriented because current host values support bounded Kotlin `String`; it delegates to the byte VFS without weakening it:

```text
operation 0: stat(path: String) -> Int
operation 1: list(path: String) -> String       NUL-delimited exact names
operation 2: readText(path: String) -> String   strict UTF-8, bounded
operation 3: writeText(path: String, value: String) -> Int
operation 4: createDirectory(path: String) -> Int
operation 5: remove(path: String) -> Int
operation 6: rename(source: String, destination: String) -> Int
```

Return zero for success and stable negative `FileSystemError` codes. `readText` returns a capability failure for invalid UTF-8 rather than replacing bytes. This adapter is not the binary artifact transport: #518 and #522 access artifact bytes through internal Rust APIs. Every operation uses the machine's initial capability and remains synchronous in Rust.

Add `compukter_create_in_store(store_handle, computer_id[16], rom_bytes, rom_len, artifact, artifact_len, output, capacity, written)`. It admits ROM and artifact, opens the computer filesystem, and creates one VM handle atomically; failure releases the filesystem lease. Kotlin adds `VmSession.openInStore(artifact, store, id, romImage)` and the core `ProgramVmSessionFactory` receives an immutable filesystem launch context.

- [ ] **Step 4: Run Rust and real-FFM persistence tests**

Run: `./gradlew-sandbox-dev-parallel testCompukterVmRust testCompukterFfiRust :native-runtime:nativeIntegrationTest --rerun-tasks`

Expected: the same-ID source text survives native close/reopen, the second ID is isolated, and all pre-filesystem VM tests still pass through the explicit ephemeral constructor.

- [ ] **Step 5: Commit**

```bash
git add host/compukter-vm host/compukter-ffi modules/native-runtime modules/core
git commit -m "feat(fs): attach persistent VFS to computer machines (#521)"
```

## Task 9: Stable Minecraft Computer Identity and World Store Lifecycle

**Files:**
- Create: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerIdentityStorage.kt`
- Create: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerFileSystemContext.kt`
- Create: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/SystemRomImage.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/InstalledProgramStorage.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerBlockEntity.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerCarrier.kt`
- Test: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerIdentityStorageTest.kt`
- Test: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerBlockEntityTest.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/fs/NeoForgeWorldFileSystemStores.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/CompuktersMod.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/fs/NeoForgeWorldFileSystemStoresTest.kt`

- [ ] **Step 1: Write failing identity and lifecycle tests**

Prove that a new entity gets one non-zero random ID, NBT round-trip preserves it, reload closes only the active carrier and not world storage, two entities differ, chunk removal retains identity/storage, server stop flushes active IDs then closes/join stores once, and the root is exactly `<world>/compukters/filesystems`.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew-sandbox-dev-parallel :v26_1-common:test :v26_1-neoforge:test --rerun-tasks`

Expected: compile failures for identity storage and NeoForge store registry.

- [ ] **Step 3: Implement stable identity and one store per Minecraft server/world**

Refactor `InstalledProgramStorage` so `ComputerBlockEntity` creates the existing `compukters` child exactly once and passes that payload to both artifact compatibility storage and identity storage. Persist ID as two longs in that payload without storing guest data:

```kotlin
internal class ComputerIdentityStorage(
    private var id: ComputerId = randomComputerId(),
) {
    fun id(): ComputerId = id
    fun save(output: ValueOutput) {
        output.putLong("computer_id_high", id.highBits)
        output.putLong("computer_id_low", id.lowBits)
    }
    fun load(input: ValueInput) {
        val high = input.getLong("computer_id_high").orElse(null)
        val low = input.getLong("computer_id_low").orElse(null)
        id = if (high == null || low == null || (high == 0L && low == 0L)) randomComputerId() else ComputerId.fromLongs(high, low)
    }
}
```

`NeoForgeWorldFileSystemStores` keys stores by canonical world root, creates and opens `<world>/compukters/filesystems` once, tracks active computer IDs for flush, and subscribes to `LevelEvent.Save` plus `ServerStoppingEvent` on `NeoForge.EVENT_BUS`. A level save flushes active IDs through their visible generations without closing the store. Server stop drains carriers first, flushes their latest generations, then closes all world stores. It never recursively deletes a root. `ComputerBlockEntity` receives a `ComputerFileSystemContext` when a server level is available and keeps identity stable across carrier recreation.

`SystemRomImage` deterministically encodes the current packaged shell artifact as executable `/rom/shell` using the canonical ROM format from Task 4. This is transitional content only: #518 adds `/rom/boot` and changes the machine entrypoint, while the VFS format and mount remain unchanged. `ProgramComputer` opens its VM with the world store, stable ID, and ROM image rather than an ephemeral filesystem.

- [ ] **Step 4: Run module tests and GameTests**

Run: `./gradlew-sandbox-dev-parallel :v26_1-common:test :v26_1-neoforge:test :v26_1-neoforge:runGameTestServer --rerun-tasks`

Expected: unit tests pass and GameTest reports isolated identities and persistence across entity unload/reload.

- [ ] **Step 5: Commit**

```bash
git add modules/v26_1/v26_1-common modules/v26_1/v26_1-neoforge
git commit -m "feat(fs): bind computer storage to world lifecycle (#521)"
```

## Task 10: Tombstones, Recovery, Packaging Assertions, and Documentation

**Files:**
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerBlock.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/fs/NeoForgeWorldFileSystemStores.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerBlockGameTest.kt`
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `README.md`

- [ ] **Step 1: Write failing tombstone and packaged-native assertions**

Add a GameTest whose filesystem conformance artifact writes a `/home` marker through the internal Rust adapter, then explicitly destroys the computer through the intended block lifecycle hook, verifies the ID becomes tombstoned rather than deleted, restores it through the test-only recovery boundary, and confirms the marker returns after reopening the same ID. Add a packaged native integration assertion that ABI v2 store lifecycle symbols are present and callable.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew-sandbox-dev-parallel :v26_1-neoforge:test :v26_1-neoforge:runGameTestServer :native-runtime:packagedNativeIntegrationTest --rerun-tasks`

Expected: tombstone lifecycle and ABI v2 assertions fail before wiring.

- [ ] **Step 3: Wire recoverable destruction and update architecture docs**

The block removal hook calls `tombstone(id)` only for confirmed destructive removal, never for chunk unload or `setRemoved` alone. Recovery remains an internal/admin boundary until a user-facing recovery mechanic is designed. Update architecture documentation to state that Rust owns synchronous VFS and persistence, Minecraft stores only `ComputerId`, `/rom` is immutable, `/home` is world-scoped, and #518/#522 remain follow-ups.

- [ ] **Step 4: Run full verification from a clean task graph**

Run:

```bash
./gradlew-sandbox-dev-parallel formatKotlin verifyLocalFast :v26_1-neoforge:verifyPackagedCompukterFfi --rerun-tasks
./gradlew-sandbox-dev-parallel verifyLocalFull --rerun-tasks
git diff --check
git status --short
```

Expected: both Gradle commands exit 0, all Rust/JVM/GameTests pass, diff check is empty, and only intended tracked changes remain before commit.

- [ ] **Step 5: Commit**

```bash
git add modules/v26_1/v26_1-common modules/v26_1/v26_1-neoforge modules/native-runtime docs/ARCHITECTURE.md README.md
git commit -m "test(fs): verify persistent computer storage (#521)"
```

## Task 11: Roadmap Completion Gate

**Files:**
- Modify only if verification exposed a factual mismatch: `docs/superpowers/specs/2026-08-23-issue-521-rust-owned-persistent-vfs-design.md`
- Modify only if task tracking needs correction: `docs/superpowers/plans/2026-08-23-issue-521-rust-owned-persistent-vfs.md`

- [ ] **Step 1: Perform manual dedicated-server lifecycle smoke check**

Run a development server, create two computers, write distinct `/home` fixtures through the test/admin boundary, unload/reload chunks, save/stop/restart the server, and confirm identities and bytes remain isolated. Confirm the Rust worker reaches `Closed` on orderly stop and no storage writes occur afterward.

- [ ] **Step 2: Re-run the exact full verification commands after any smoke-fix**

Run:

```bash
./gradlew-sandbox-dev-parallel verifyLocalFull --rerun-tasks
git diff --check
```

Expected: exit 0 with no failures.

- [ ] **Step 3: Update #521 with commits and evidence**

Comment with the exact commits, Rust/JVM/GameTest counts, storage root tested, durable generation observed, and manual checks. If every acceptance criterion is verified, close #521 as completed and set Roadmap status `Done`; otherwise leave it `Now` with the exact remaining manual check.

- [ ] **Step 4: Move #518 to Now only after #521 is complete**

Resolve its project item and set `Now`; keep #522 in `Next`. Do not begin foreground execution while VFS persistence or lifecycle remains unverified.
