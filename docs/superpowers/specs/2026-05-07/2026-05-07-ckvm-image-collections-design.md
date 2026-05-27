# CKVM Image Collections Design

## Goal

Add native `CkVmImage` runtime support for CKL mutable collections: `Array<T>`, `List<T>`, and `Map<K, V>`. After this slice, CKL programs that construct collections, use index access/assignment, and call documented collection methods should compile to image bytecode and execute in the Rust native image runner.

This design is part of the CKVM image runtime parity milestone. It keeps the Rust image runtime as the only execution path and does not restore the deleted JVM bytecode VM or the deleted legacy native bytecode ABI.

## Scope

Implement image support for:

- `Instruction.ConstructArray`
- `Instruction.ConstructList`
- `Instruction.ConstructMap`
- `Instruction.IndexGet`
- `Instruction.IndexSet`
- `Instruction.CallCollectionMethod`

Explicitly defer:

- `Instruction.ConstructClass`
- `Instruction.SetField`
- `Instruction.CallMethod`
- `Instruction.CallStaticMethod`
- class/object metadata and method dispatch

Frontend generics and collection type checking already exist. This slice implements the runtime image backend and Rust execution path for the bytecode instructions that the frontend already emits.

## Existing Foundation

The compiler frontend already emits collection instructions:

- `ConstructArray` after compiling `size` then `default` for `Array<T>(size = ..., default = ...)`.
- `ConstructList(elementCount)` after compiling list literal elements in source order.
- `ConstructMap(entryCount)` after compiling each map literal key then value in source order.
- `IndexGet` after compiling receiver then index/key.
- `IndexSet` after compiling receiver, index/key, then value.
- `CallCollectionMethod(methodName, argumentCount)` after compiling receiver then call arguments.

The Rust value model already has `VmValue::ObjectRef(u32)`. This slice uses that existing variant as a handle to VM-owned heap collection objects.

## Image ABI

Append collection opcodes after the current `GET_FIELD = 17` opcode:

- `CONSTRUCT_ARRAY = 18`
- `CONSTRUCT_LIST = 19`
- `CONSTRUCT_MAP = 20`
- `INDEX_GET = 21`
- `INDEX_SET = 22`
- `CALL_COLLECTION_METHOD = 23`

Do not renumber existing opcodes.

### Instruction Encodings

`CONSTRUCT_ARRAY`:

1. opcode byte (`18`)

The operand stack must contain `size` then `default`.

`CONSTRUCT_LIST`:

1. opcode byte (`19`)
2. little-endian `i32 elementCount`

`CONSTRUCT_MAP`:

1. opcode byte (`20`)
2. little-endian `i32 entryCount`

`INDEX_GET`:

1. opcode byte (`21`)

The operand stack must contain `receiver` then `indexOrKey`.

`INDEX_SET`:

1. opcode byte (`22`)

The operand stack must contain `receiver`, `indexOrKey`, then `value`.

`CALL_COLLECTION_METHOD`:

1. opcode byte (`23`)
2. little-endian `i32 methodNameConstantIndex`
3. little-endian `i32 argumentCount`

The method-name metadata index must refer to a `StringConstant` entry in the existing image constant pool.

## Kotlin Lowering

`CkVmImageOpcodes` should add the six collection opcode constants with the values listed above.

`CkVmImageBackend` should lower collection instructions as follows:

- `ConstructArray`: one-byte instruction.
- `ConstructList(elementCount)`: opcode plus `i32 elementCount`.
- `ConstructMap(entryCount)`: opcode plus `i32 entryCount`.
- `IndexGet`: one-byte instruction.
- `IndexSet`: one-byte instruction.
- `CallCollectionMethod(methodName, argumentCount)`: opcode plus method-name string constant index plus `i32 argumentCount`.

The backend should reuse the existing constant-pool helper for method-name metadata. The unsupported-instruction test should move from `ConstructList` to the next real unsupported instruction after this slice, expected to be `ConstructClass`.

## Rust Runtime Model

Add a VM-owned heap to `ImageVmHandle`:

- `next_object_id: u32`
- `objects: HashMap<u32, HeapObject>`

`HeapObject` should include:

- `Array(Vec<VmValue>)`
- `List(Vec<VmValue>)`
- `Map(Vec<(VmValue, VmValue)>)`

`VmValue::ObjectRef(id)` is the runtime handle stored in locals, stack values, records, collection slots, and map entries. Copying a `VmValue::ObjectRef` copies only the handle, not the collection object. This preserves shared mutation semantics.

Heap object ids should be allocated monotonically. Runtime errors should be deterministic for missing or dangling ids. The design does not require heap garbage collection in this slice.

## Collection Semantics

### Array Construction

`CONSTRUCT_ARRAY` should:

1. pop `default`;
2. pop `size`;
3. require `size` to be `Int`;
4. reject negative sizes;
5. allocate an `Array` heap object with `size` clones of `default`;
6. push `ObjectRef(id)`.

Arrays are fixed-size. Methods can mutate existing slots but cannot grow or shrink the array.

### List Construction

`CONSTRUCT_LIST` should:

1. reject negative `elementCount`;
2. pop `elementCount` values order-preserving;
3. allocate a `List` heap object with those values;
4. push `ObjectRef(id)`.

### Map Construction

`CONSTRUCT_MAP` should:

1. reject negative `entryCount`;
2. pop `entryCount * 2` values order-preserving;
3. interpret them as `(key, value)` pairs;
4. reject `Null` keys;
5. allocate a `Map` heap object;
6. insert entries in source order;
7. push `ObjectRef(id)`.

If a duplicate key appears during construction, the later value replaces the earlier value while preserving the original insertion position.

### Index Access

`INDEX_GET` should:

1. pop `indexOrKey`;
2. pop `receiver`;
3. require `receiver` to be an `ObjectRef` pointing to a collection heap object.

For arrays and lists:

- `indexOrKey` must be `Int`.
- Out-of-bounds access is a deterministic runtime error.
- The result is a clone of the stored value.

For maps:

- `indexOrKey` must be non-null.
- Present keys return a clone of the stored value.
- Missing keys return `Null`.

### Index Assignment

`INDEX_SET` should:

1. pop `value`;
2. pop `indexOrKey`;
3. pop `receiver`;
4. require `receiver` to be an `ObjectRef` pointing to a collection heap object;
5. mutate the target collection;
6. push `Unit`.

For arrays and lists:

- `indexOrKey` must be `Int`.
- Out-of-bounds assignment is a deterministic runtime error.

For maps:

- `indexOrKey` must be non-null.
- Setting an existing key replaces its value and preserves insertion order.
- Setting a new key appends a new entry.

## Collection Methods

`CALL_COLLECTION_METHOD` should pop arguments order-preserving, then pop the receiver. The receiver must be an `ObjectRef` pointing to a collection heap object. Method names and argument counts must match the receiver kind.

### Array Methods

- `size(): Int`
- `get(index: Int): T`
- `set(index: Int, value: T): Unit`
- `getOrNull(index: Int): T?`

`get` and `set` use the same bounds behavior as index get/set. `getOrNull` returns `Null` for out-of-bounds indexes.

### List Methods

- `size(): Int`
- `isEmpty(): Bool`
- `get(index: Int): T`
- `set(index: Int, value: T): Unit`
- `getOrNull(index: Int): T?`
- `add(value: T): Unit`
- `insert(index: Int, value: T): Unit`
- `removeAt(index: Int): T`
- `clear(): Unit`

`insert` accepts indexes in `0..size`. `removeAt` returns the removed value. `clear` removes all values.

### Map Methods

- `size(): Int`
- `isEmpty(): Bool`
- `containsKey(key: K): Bool`
- `get(key: K): V?`
- `getOrDefault(key: K, default: V): V`
- `set(key: K, value: V): Unit`
- `remove(key: K): V?`
- `clear(): Unit`
- `keys(): List<K>`
- `values(): List<V>`

`keys()` and `values()` allocate new `List` heap objects that preserve map insertion order and return `ObjectRef` handles to those lists.

## Equality and Map Keys

Map keys may be any non-null CKL value.

Key equality should follow CKL value equality:

- `Bool`, `Int`, `Long`, and `String` compare by value.
- `Int` and `Long` compare numerically across the two numeric widths.
- Struct records compare structurally.
- Collection and object references compare by `ObjectRef` identity.
- `Null` keys are rejected.

Map storage remains insertion-ordered. Replacing an existing key keeps its original order. Removing a key and adding it again appends it to the end.

## Host and JNI Boundary

Live collection heap objects are VM-internal. This slice does not add native signal value tags for arrays, lists, maps, or object references.

Collection references should normally not cross host/JNI boundaries because CKL host APIs are typed around primitives, strings, records, and event data. If a collection `ObjectRef` reaches a halt or host-call signal, the existing diagnostic `ObjectRef` encoding remains acceptable for debugging, but it is not a round-trip collection serialization format.

JNI acceptance tests should produce primitive/string host-call outputs derived from collection operations rather than asserting serialized collection values.

## Error Handling

Runtime errors must be deterministic for:

- unknown collection opcodes or method tags;
- invalid method-name metadata indexes;
- non-string method-name metadata constants;
- negative list element counts;
- negative map entry counts;
- map entry count overflow when converting to value count;
- array size values that are not `Int`;
- negative array sizes;
- stack underflow;
- receivers that are not `ObjectRef`;
- object ids that do not exist;
- object ids that point to the wrong heap kind for a method;
- wrong method names;
- wrong method argument counts;
- wrong index/key/value argument types;
- out-of-bounds array/list access or mutation;
- null map keys.

## Non-Goals

- Do not implement class construction or object methods.
- Do not implement `SetField`.
- Do not add heap garbage collection.
- Do not change CKL generic type erasure.
- Do not add host/JNI collection serialization.
- Do not change CKL source syntax or frontend type rules.

## Testing Plan

### Kotlin Backend Tests

Add focused tests that assert exact lowering for:

- `ConstructArray`
- `ConstructList`
- `ConstructMap`
- `IndexGet`
- `IndexSet`
- `CallCollectionMethod`

Add compiled CKL samples for:

- array construction, index assignment, index access;
- list literal, `add`, `removeAt`, index access;
- map literal or map assignment, `containsKey`, `getOrDefault`, `keys`, `values` where frontend syntax permits it.

The unsupported-instruction diagnostic should move from `ConstructList` to `ConstructClass`.

### Rust Image Runner Tests

Add direct native runner tests for:

- array construction with cloned defaults;
- array `get`, `set`, `getOrNull`, and bounds errors;
- list construction preserving element order;
- list shared identity after storing/loading copied `ObjectRef` values;
- list `add`, `insert`, `removeAt`, `clear`, and `isEmpty`;
- map construction preserving insertion order;
- map duplicate replacement preserving order;
- map `get`, `getOrDefault`, `containsKey`, `set`, `remove`, `clear`;
- map `keys()` and `values()` returning ordered list heap objects;
- map key equality for primitive numeric widening, strings, records, and `ObjectRef` identity;
- deterministic error cases listed above.

### JNI / End-to-End Tests

Add JNI tests that compile and run CKL source through `NativeImageVmRunner` and assert logged strings:

- array `set/get` produces an order-sensitive result;
- list literal plus mutation produces an order-sensitive result;
- map `set/getOrDefault/containsKey` produces an order-sensitive result.

The tests should not depend on serializing collection refs across JNI.

## Acceptance Criteria

This slice is complete when:

- `CkVmImageBackend` supports all six collection instructions.
- Rust image execution supports opcodes `18` through `23` with deterministic errors.
- CKL `Array`, `List`, and `Map` programs using indexing, index assignment, and documented methods run through native `CkVmImage`.
- Focused Kotlin backend tests pass.
- Focused Rust image runner tests pass.
- Focused JNI/native integration tests pass.
- Stale unsupported diagnostics no longer name `ConstructList`.
- Class/object instructions remain explicitly deferred as the next unsupported family.

## Risks

- The heap model introduces shared mutable state; tests must cover aliasing so collection values are not accidentally copied by value.
- Map key equality must match CKL value equality closely enough for future programs. Numeric widening, record structural equality, and `ObjectRef` identity need focused tests.
- `keys()` and `values()` allocate new lists, so tests should avoid assuming they are views into the map.
- Without garbage collection, long-running collection-heavy programs can leak heap objects. This is acceptable for the first image runtime parity slice but should be revisited with memory accounting and snapshots.
