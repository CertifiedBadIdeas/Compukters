# CKVM Image Collections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add native `CkVmImage` support for `Array`, `List`, `Map`, index get/set, and collection methods.

**Architecture:** Kotlin lowering appends collection opcodes `18..23` and uses the existing string constant pool for collection method names. Rust `ImageVmHandle` owns a VM heap of mutable collection objects and represents live collections as `VmValue::ObjectRef(id)`, preserving aliasing and mutation identity. Host/JNI tests assert primitive/string outputs derived from collection operations rather than serializing heap objects.

**Tech Stack:** Kotlin/JVM compiler tests with Gradle, Rust `ckl-vm` crate tests with Cargo, JNI integration through `NativeImageVmRunner`.

**Working location:** Current branch `dev` per user choice. Do not create a worktree for this plan.

---

## Files and Responsibilities

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
  - Add collection opcode constants after `GET_FIELD`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
  - Lower `ConstructArray`, `ConstructList`, `ConstructMap`, `IndexGet`, `IndexSet`, and `CallCollectionMethod`.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`
  - Add Kotlin RED/GREEN lowering tests and move unsupported diagnostic to `ConstructClass`.
- Modify `native/ckl-vm/src/image_runner.rs`
  - Add collection opcodes, `HeapObject`, object allocation, collection dispatch, index operations, collection methods, and map key equality.
- Modify `native/ckl-vm/tests/image_runner.rs`
  - Add direct Rust RED/GREEN tests for array, list, map, identity, ordering, and deterministic errors.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`
  - Add JNI tests that compile CKL collection programs and assert logged outputs.

---

### Task 1: Kotlin Backend RED Tests

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add collection lowering tests**

Add these tests after `compileImageLowersRecordConstructionAndFieldAccess()`:

```kotlin
    @Test
    fun compileImageLowersCollectionConstructorsAndIndexOps() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val array: Array<Int> = Array<Int>(size = 2, default = 0);
                    array[1] = 7;
                    val arrayValue: Int = array[1];
                    val list: List<Int> = [2, 5];
                    val listValue: Int = list[0] - list[1];
                    val map: Map<String, Int> = {"x": 3};
                    map["y"] = 4;
                    val mapValue: Int? = map["missing"];
                }
                """.trimIndent(),
            ).image,
        )
        val mainFunction = image.functions.single { it.name == "main.ck#main" }

        assertEquals(
            listOf(
                CkVmConstant.IntConstant(2),
                CkVmConstant.IntConstant(0),
                CkVmConstant.IntConstant(1),
                CkVmConstant.IntConstant(7),
                CkVmConstant.IntConstant(5),
                CkVmConstant.StringConstant("x"),
                CkVmConstant.IntConstant(3),
                CkVmConstant.StringConstant("y"),
                CkVmConstant.IntConstant(4),
                CkVmConstant.StringConstant("missing"),
            ),
            image.constants,
        )
        assertEquals(6, mainFunction.frameSize)
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.CONSTRUCT_ARRAY))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.CONSTRUCT_LIST))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.CONSTRUCT_MAP))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.INDEX_GET))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.INDEX_SET))
    }

    @Test
    fun compileImageLowersCollectionMethodsWithStringMetadata() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val list: List<Int> = [2];
                    list.add(5);
                    val size: Int = list.size();
                    val removed: Int = list.removeAt(0);
                    val map: Map<String, Int> = {"x": 1};
                    val exists: Bool = map.containsKey("x");
                    val fallback: Int = map.getOrDefault("missing", 9);
                }
                """.trimIndent(),
            ).image,
        )
        val mainFunction = image.functions.single { it.name == "main.ck#main" }

        assertTrue(image.constants.contains(CkVmConstant.StringConstant("add")))
        assertTrue(image.constants.contains(CkVmConstant.StringConstant("size")))
        assertTrue(image.constants.contains(CkVmConstant.StringConstant("removeAt")))
        assertTrue(image.constants.contains(CkVmConstant.StringConstant("containsKey")))
        assertTrue(image.constants.contains(CkVmConstant.StringConstant("getOrDefault")))
        assertTrue(mainFunction.code.contains(CkVmImageOpcodes.CALL_COLLECTION_METHOD))
    }
```

- [ ] **Step 2: Move unsupported diagnostic test to `ConstructClass`**

Replace the current `unsupportedInstructionReportsClearError()` body with:

```kotlin
    @Test
    fun unsupportedInstructionReportsClearError() {
        val base = assertNotNull(LanguageFrontend().compile("main.ck", "pub fun main() { }").module)
        val function = base.functions.single().copy(instructions = listOf(Instruction.ConstructClass("Box", emptyList()), Instruction.Return))

        val error = assertFailsWith<UnsupportedOperationException> {
            CkVmImageCompiler.compile(base.copy(functions = listOf(function)))
        }

        assertTrue(error.message.orEmpty().contains("CkVmImage backend does not support ConstructClass"))
    }
```

- [ ] **Step 3: Run Kotlin backend tests and verify RED**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageBackendTest'
```

Expected: FAIL with unresolved `CkVmImageOpcodes.CONSTRUCT_ARRAY` / collection opcode constants, or unsupported `ConstructArray` / `ConstructList` during lowering.

---

### Task 2: Kotlin Backend Implementation

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add Kotlin opcode constants**

In `CkVmImageOpcodes`, append:

```kotlin
    const val CONSTRUCT_RECORD = 16
    const val GET_FIELD = 17
    const val CONSTRUCT_ARRAY = 18
    const val CONSTRUCT_LIST = 19
    const val CONSTRUCT_MAP = 20
    const val INDEX_GET = 21
    const val INDEX_SET = 22
    const val CALL_COLLECTION_METHOD = 23
```

- [ ] **Step 2: Add instruction lengths**

In `instructionLength`, make these branches explicit:

```kotlin
                Instruction.PushUnit,
                Instruction.PushNull,
                Instruction.Return,
                Instruction.Pop,
                Instruction.ConstructArray,
                Instruction.IndexGet,
                Instruction.IndexSet,
                -> 1
                is Instruction.PushBool -> 2
                is Instruction.PushString,
                is Instruction.PushInt,
                is Instruction.PushLong,
                is Instruction.LoadLocal,
                is Instruction.StoreLocal,
                is Instruction.Jump,
                is Instruction.JumpIfFalse,
                is Instruction.JumpIfTrue,
                is Instruction.GetField,
                is Instruction.ConstructList,
                is Instruction.ConstructMap,
                -> 5
                is Instruction.ConstructRecord -> 9 + 4 * instruction.fieldNames.size
                is Instruction.Binary,
                is Instruction.Unary,
                -> 2
                is Instruction.CallFunction,
                is Instruction.CallBuiltin,
                is Instruction.CallCollectionMethod,
                -> 9
```

- [ ] **Step 3: Lower collection instructions**

In `lowerInstruction`, add branches before `Instruction.CallFunction`:

```kotlin
                Instruction.ConstructArray -> listOf(CkVmImageOpcodes.CONSTRUCT_ARRAY)
                is Instruction.ConstructList -> listOf(CkVmImageOpcodes.CONSTRUCT_LIST) + i32(instruction.elementCount)
                is Instruction.ConstructMap -> listOf(CkVmImageOpcodes.CONSTRUCT_MAP) + i32(instruction.entryCount)
                Instruction.IndexGet -> listOf(CkVmImageOpcodes.INDEX_GET)
                Instruction.IndexSet -> listOf(CkVmImageOpcodes.INDEX_SET)
                is Instruction.CallCollectionMethod ->
                    listOf(CkVmImageOpcodes.CALL_COLLECTION_METHOD) +
                        stringConstantIndexBytes(instruction.methodName) +
                        i32(instruction.argumentCount)
```

- [ ] **Step 4: Run Kotlin backend tests and verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageBackendTest'
```

Expected: PASS.

- [ ] **Step 5: Commit Kotlin lowering**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "feat: lower ckvm image collections"
```

---

### Task 3: Rust Collection Heap RED Tests

**Files:**
- Modify: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add test opcode constants**

After `const OP_GET_FIELD: u8 = 17;`, add:

```rust
const OP_CONSTRUCT_ARRAY: u8 = 18;
const OP_CONSTRUCT_LIST: u8 = 19;
const OP_CONSTRUCT_MAP: u8 = 20;
const OP_INDEX_GET: u8 = 21;
const OP_INDEX_SET: u8 = 22;
const OP_CALL_COLLECTION_METHOD: u8 = 23;
```

- [ ] **Step 2: Add focused collection behavior tests**

Add these tests before `calls_function_and_returns_value_to_entry_frame()`:

```rust
#[test]
fn executes_array_index_set_and_get() {
    let code = vec![
        OP_PUSH_CONSTANT, 0, 0, 0, 0,
        OP_PUSH_CONSTANT, 1, 0, 0, 0,
        OP_CONSTRUCT_ARRAY,
        OP_STORE_LOCAL, 0, 0, 0, 0,
        OP_LOAD_LOCAL, 0, 0, 0, 0,
        OP_PUSH_CONSTANT, 2, 0, 0, 0,
        OP_PUSH_CONSTANT, 3, 0, 0, 0,
        OP_INDEX_SET,
        OP_POP,
        OP_LOAD_LOCAL, 0, 0, 0, 0,
        OP_PUSH_CONSTANT, 2, 0, 0, 0,
        OP_INDEX_GET,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![ConstantFixture::Int(2), ConstantFixture::Int(0), ConstantFixture::Int(1), ConstantFixture::Int(7)],
            1,
            code,
        ),
        64,
    ).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 7, 0, 0, 0]);
}

#[test]
fn executes_list_methods_and_preserves_alias_identity() {
    let code = vec![
        OP_PUSH_CONSTANT, 0, 0, 0, 0,
        OP_CONSTRUCT_LIST, 1, 0, 0, 0,
        OP_STORE_LOCAL, 0, 0, 0, 0,
        OP_LOAD_LOCAL, 0, 0, 0, 0,
        OP_STORE_LOCAL, 1, 0, 0, 0,
        OP_LOAD_LOCAL, 1, 0, 0, 0,
        OP_PUSH_CONSTANT, 1, 0, 0, 0,
        OP_CALL_COLLECTION_METHOD, 2, 0, 0, 0, 1, 0, 0, 0,
        OP_POP,
        OP_LOAD_LOCAL, 0, 0, 0, 0,
        OP_PUSH_CONSTANT, 3, 0, 0, 0,
        OP_INDEX_GET,
        OP_LOAD_LOCAL, 0, 0, 0, 0,
        OP_PUSH_CONSTANT, 4, 0, 0, 0,
        OP_INDEX_GET,
        OP_BINARY, 1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::String("add".to_string()),
                ConstantFixture::Int(0),
                ConstantFixture::Int(1),
            ],
            2,
            code,
        ),
        64,
    ).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 253, 255, 255, 255]);
}

#[test]
fn executes_map_set_get_or_default_and_contains_key() {
    let code = vec![
        OP_PUSH_CONSTANT, 0, 0, 0, 0,
        OP_PUSH_CONSTANT, 1, 0, 0, 0,
        OP_CONSTRUCT_MAP, 1, 0, 0, 0,
        OP_STORE_LOCAL, 0, 0, 0, 0,
        OP_LOAD_LOCAL, 0, 0, 0, 0,
        OP_PUSH_CONSTANT, 2, 0, 0, 0,
        OP_PUSH_CONSTANT, 3, 0, 0, 0,
        OP_INDEX_SET,
        OP_POP,
        OP_LOAD_LOCAL, 0, 0, 0, 0,
        OP_PUSH_CONSTANT, 0, 0, 0, 0,
        OP_CALL_COLLECTION_METHOD, 4, 0, 0, 0, 1, 0, 0, 0,
        OP_JUMP_IF_FALSE, 90, 0, 0, 0,
        OP_LOAD_LOCAL, 0, 0, 0, 0,
        OP_PUSH_CONSTANT, 5, 0, 0, 0,
        OP_PUSH_CONSTANT, 6, 0, 0, 0,
        OP_CALL_COLLECTION_METHOD, 7, 0, 0, 0, 2, 0, 0, 0,
        OP_RETURN,
        OP_PUSH_CONSTANT, 8, 0, 0, 0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::String("x".to_string()),
                ConstantFixture::Int(3),
                ConstantFixture::String("y".to_string()),
                ConstantFixture::Int(4),
                ConstantFixture::String("containsKey".to_string()),
                ConstantFixture::String("missing".to_string()),
                ConstantFixture::Int(9),
                ConstantFixture::String("getOrDefault".to_string()),
                ConstantFixture::Int(-1),
            ],
            1,
            code,
        ),
        128,
    ).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 9, 0, 0, 0]);
}
```

- [ ] **Step 3: Add deterministic error tests**

Add these tests after the behavior tests:

```rust
#[test]
fn rejects_array_negative_size() {
    let code = vec![OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_PUSH_UNIT, OP_CONSTRUCT_ARRAY];
    let mut vm = ImageVmHandle::create(&image_with_constants_and_code(vec![ConstantFixture::Int(-1)], 0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("negative CkVmImage array size -1"));
}

#[test]
fn rejects_index_get_on_non_collection_receiver() {
    let code = vec![OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_PUSH_CONSTANT, 1, 0, 0, 0, OP_INDEX_GET];
    let mut vm = ImageVmHandle::create(&image_with_constants_and_code(vec![ConstantFixture::Int(1), ConstantFixture::Int(0)], 0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("requires collection ObjectRef receiver"));
}

#[test]
fn rejects_null_map_key() {
    let code = vec![OP_PUSH_NULL, OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_CONSTRUCT_MAP, 1, 0, 0, 0];
    let mut vm = ImageVmHandle::create(&image_with_constants_and_code(vec![ConstantFixture::Int(1)], 0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("Map keys cannot be null"));
}
```

- [ ] **Step 4: Run Rust tests and verify RED**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner
```

Expected: FAIL because opcodes `18..23` are unknown.

---

### Task 4: Rust Collection Heap Implementation

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`
- Test: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add imports, opcodes, and heap fields**

At the top of `image_runner.rs`, add:

```rust
use std::collections::HashMap;
```

After `OP_GET_FIELD`, add the six collection opcode constants. Add this enum near `CallFrame`:

```rust
#[derive(Debug, Clone)]
enum HeapObject {
    Array(Vec<VmValue>),
    List(Vec<VmValue>),
    Map(Vec<(VmValue, VmValue)>),
}
```

Add fields to `ImageVmHandle`:

```rust
    objects: HashMap<u32, HeapObject>,
    next_object_id: u32,
```

Initialize them in `create`:

```rust
            objects: HashMap::new(),
            next_object_id: 1,
```

- [ ] **Step 2: Dispatch opcodes**

In `run_until_signal_inner`, add:

```rust
                OP_CONSTRUCT_ARRAY => self.construct_array()?,
                OP_CONSTRUCT_LIST => self.construct_list()?,
                OP_CONSTRUCT_MAP => self.construct_map()?,
                OP_INDEX_GET => self.index_get()?,
                OP_INDEX_SET => self.index_set()?,
                OP_CALL_COLLECTION_METHOD => {
                    let method_name_index = self.read_i32()?;
                    let method_name = self.constant_string_metadata(method_name_index, "collection method name")?;
                    let argument_count = self.read_i32()?;
                    self.call_collection_method(method_name, argument_count)?;
                }
```

- [ ] **Step 3: Add object allocation and map helpers**

Inside `impl ImageVmHandle`, add:

```rust
    fn allocate_object(&mut self, object: HeapObject) -> Result<VmValue, String> {
        let id = self.next_object_id;
        self.next_object_id = self.next_object_id.checked_add(1).ok_or_else(|| "CkVmImage object id overflow".to_string())?;
        self.objects.insert(id, object);
        Ok(VmValue::ObjectRef(id))
    }

    fn collection_object(&self, receiver: VmValue, operation: &str) -> Result<(u32, &HeapObject), String> {
        match receiver {
            VmValue::ObjectRef(id) => self.objects.get(&id).map(|object| (id, object)).ok_or_else(|| format!("CkVmImage {operation} object id {id} does not exist")),
            other => Err(format!("CkVmImage {operation} requires collection ObjectRef receiver but found {other:?}")),
        }
    }

    fn collection_object_mut(&mut self, receiver: VmValue, operation: &str) -> Result<(u32, &mut HeapObject), String> {
        match receiver {
            VmValue::ObjectRef(id) => self.objects.get_mut(&id).map(|object| (id, object)).ok_or_else(|| format!("CkVmImage {operation} object id {id} does not exist")),
            other => Err(format!("CkVmImage {operation} requires collection ObjectRef receiver but found {other:?}")),
        }
    }

    fn require_int(value: VmValue, operation: &str) -> Result<i32, String> {
        match value {
            VmValue::Int(value) => Ok(value),
            other => Err(format!("CkVmImage {operation} requires Int but found {other:?}")),
        }
    }

    fn require_non_null_key(key: VmValue) -> Result<VmValue, String> {
        if key == VmValue::Null {
            Err("CkVmImage Map keys cannot be null".to_string())
        } else {
            Ok(key)
        }
    }

    fn map_find_index(entries: &[(VmValue, VmValue)], key: &VmValue) -> Option<usize> {
        entries.iter().position(|(entry_key, _)| value_equals(entry_key, key))
    }

    fn map_set(entries: &mut Vec<(VmValue, VmValue)>, key: VmValue, value: VmValue) {
        if let Some(index) = Self::map_find_index(entries, &key) {
            entries[index].1 = value;
        } else {
            entries.push((key, value));
        }
    }
```

- [ ] **Step 4: Add constructors and index ops**

Inside `impl ImageVmHandle`, add methods `construct_array`, `construct_list`, `construct_map`, `index_get`, and `index_set` with these exact stack orders:

```text
construct_array: pop default, pop size Int, reject negative size, allocate HeapObject::Array(vec![default; size]), push ObjectRef.
construct_list: read elementCount operand, reject negative count, pop_many(elementCount), allocate HeapObject::List(values), push ObjectRef.
construct_map: read entryCount operand, reject negative count, pop_many(entryCount * 2), chunk as key/value pairs, reject Null keys, insert with map_set, push ObjectRef.
index_get: pop indexOrKey, pop receiver, read collection by ObjectRef; Array/List require Int index and clone slot; Map rejects Null key and returns cloned value or Null.
index_set: pop value, pop indexOrKey, pop receiver, mutate collection by ObjectRef; Array/List require Int index and in-bounds slot; Map rejects Null key and uses map_set; push Unit.
```

Use `pop_many` for order-preserving list/map construction. Use `usize::try_from` or explicit non-negative checks before converting indexes. Return deterministic `Err(String)` messages containing the operation name for every rejected type, bounds, or heap lookup.

- [ ] **Step 5: Add collection method dispatch**

Inside `impl ImageVmHandle`, add `call_collection_method`, `call_array_method`, `call_list_method`, and `call_map_method`. Implement exactly these method sets:

```text
Array: size, get, set, getOrNull
List: size, isEmpty, get, set, getOrNull, add, insert, removeAt, clear
Map: size, isEmpty, containsKey, get, getOrDefault, set, remove, clear, keys, values
```

Every method must validate exact argument count and argument types. `keys` and `values` allocate `HeapObject::List` and return `VmValue::ObjectRef`.

- [ ] **Step 6: Run Rust tests and verify GREEN**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner
```

Expected: PASS.

- [ ] **Step 7: Commit Rust heap execution**

Run:

```bash
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: execute ckvm image collections"
```

---

### Task 5: JNI Collection Integration Tests

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Add JNI tests**

Add these tests after `imageRunnerExecutesRecordConstructionAndFieldAccessThroughJniWhenLibraryIsConfigured()`:

```kotlin
    @Test
    fun imageRunnerExecutesArrayCollectionsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val values: Array<Int> = Array<Int>(size = 2, default = 0);
                    values[0] = 9;
                    values[1] = 4;
                    system::log("value=" + (values[0] - values[1]));
                }
                """.trimIndent(),
            ).image,
        )
        val runtime = RecordingRuntime()

        runBlocking { NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime) }

        assertEquals(listOf("value=5"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesListCollectionsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val values: List<Int> = [2];
                    values.add(5);
                    val removed: Int = values.removeAt(0);
                    system::log("value=" + (removed - values[0]));
                }
                """.trimIndent(),
            ).image,
        )
        val runtime = RecordingRuntime()

        runBlocking { NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime) }

        assertEquals(listOf("value=-3"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesMapCollectionsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val values: Map<String, Int> = {"x": 3};
                    values["y"] = 4;
                    if (values.containsKey("x")) {
                        system::log("value=" + values.getOrDefault("missing", 9));
                    }
                }
                """.trimIndent(),
            ).image,
        )
        val runtime = RecordingRuntime()

        runBlocking { NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime) }

        assertEquals(listOf("value=9"), runtime.lines)
    }
```

- [ ] **Step 2: Build native library**

Run:

```bash
cargo build --manifest-path native/ckl-vm/Cargo.toml
```

Expected: PASS and creates `native/ckl-vm/target/debug/libckl_vm.so` on Linux.

- [ ] **Step 3: Run JNI tests and verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageVmRunnerJniTest' -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: PASS.

- [ ] **Step 4: Commit JNI coverage**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt
git commit -m "test: run ckvm image collections through jni"
```

---

### Task 6: Final Verification and Cleanup

**Files:**
- Inspect all modified files

- [ ] **Step 1: Run focused Kotlin backend tests**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageBackendTest'
```

Expected: PASS.

- [ ] **Step 2: Run Rust crate tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 3: Run focused JNI tests with native library configured**

Run:

```bash
cargo build --manifest-path native/ckl-vm/Cargo.toml && ./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageVmRunnerJniTest' -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: PASS.

- [ ] **Step 4: Check stale unsupported references**

Run:

```bash
grep -R "does not support ConstructList\|unknown CkVmImage opcode 18\|unknown CkVmImage opcode 19\|unknown CkVmImage opcode 20\|unknown CkVmImage opcode 21\|unknown CkVmImage opcode 22\|unknown CkVmImage opcode 23" -n modules native || true
```

Expected: no stale production/test expectation references. Mentions in docs are acceptable.

- [ ] **Step 5: Inspect diff and status**

Run:

```bash
git status --short --untracked-files=all && git --no-pager diff --stat && git --no-pager diff --check
```

Expected: clean status after planned commits and `git diff --check` exits with status `0`.

---

## Implementation Notes

- Keep opcodes synchronized between Kotlin constants, Rust constants, and tests.
- Use `pop_many` for list/map construction and method arguments to preserve source order.
- `INDEX_SET` must push `Unit` because frontend emits `Instruction.Pop` after index assignment statements.
- Collection methods must mutate heap objects in place, not cloned vectors.
- Do not add class/object support or `SetField` in this plan.
- If Rust borrow-checking conflicts with helper layout, split immutable reads and mutable writes into small helper functions that borrow `self.objects` for the shortest possible scope.
