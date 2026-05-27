# CKL Collections and Generics Design

## Summary

CKL will gain compile-time generics and three mutable built-in collection families: `Array<T>`, `List<T>`, and `Map<K, V>`. The language remains statically typed, deterministic, sandboxed, and VM-friendly. Generic type information is enforced by the frontend and erased at runtime; the VM stores ordinary `VmValue` elements inside native collection objects.

This design intentionally treats collections and user-defined generics as one language feature. Collections need generic type arguments to be useful, and user code needs generic functions, structs, and classes to build reusable APIs around them.

## Goals

- Add generic type syntax for functions, structs, classes, and built-in collection types.
- Add mutable `Array<T>`, `List<T>`, and `Map<K, V>`.
- Add collection literals for lists and maps.
- Add index access and index assignment.
- Keep existing non-generic CKL source valid.
- Preserve deterministic runtime behavior and sandbox memory accounting.
- Keep the first version focused: no variance, upper bounds, overloads, reified generics, or iterator syntax.

## Non-goals

- No `for (x in collection)` loop in the first implementation. Iteration uses `while`, `size()`, `get()`, `keys()`, and `values()`.
- No immutable/read-only collection hierarchy in the first implementation. All collection values are mutable.
- No array literal distinct from list literal.
- No generic constraints or typeclass-like hash/equality interfaces.
- No host interop or reflection.

## Language model

`Array<T>` is a mutable fixed-size indexed storage. Its size is chosen when constructed and cannot change.

`List<T>` is a mutable growable indexed storage.

`Map<K, V>` is a mutable associative storage. Keys must be non-null. The first version permits any non-null type as `K`.

`val` prevents rebinding a variable, but it does not freeze the referenced collection. This matches CKL's current reference-object model: a `val` class reference can still observe mutations performed through methods.

Generic types are compile-time only. The frontend verifies type arguments and substitutions. Runtime values do not carry reified `T`, `K`, or `V` metadata beyond the ordinary `VmValue` shape needed to execute the program.

## Generic syntax

Types:

- `List<Int>`
- `Array<String>`
- `Map<String, Int>`
- `Box<List<Int>>`
- `List<Int>?`

Declarations:

- `fun identity<T>(value: T): T { return value }`
- `struct Pair<A, B> { first: A, second: B }`
- `class Box<T>(pub var value: T) { ... }`

The parser must distinguish generic angle brackets from comparison operators. In type syntax and declaration headers, `<...>` is parsed as type parameters or type arguments. In expressions, `<` and `>` remain comparison operators except where a future explicit generic call syntax is introduced. The first version can infer generic function type arguments from ordinary call arguments and expected return types, so explicit generic function calls are not required.

## Collection expressions

List literals:

- `[1, 2, 3]` has type `List<Int>`.
- `[]` requires an expected type, for example `val xs: List<Int> = []`.
- Mixed element types are rejected unless normal assignability rules produce a single expected element type.

Map literals:

- `{"a": 1, "b": 2}` has type `Map<String, Int>`.
- `{}` requires an expected type, for example `val table: Map<String, Int> = {}`.
- Keys must be non-null and assignable to the inferred or expected key type.
- Values must be assignable to the inferred or expected value type.

Array construction uses a constructor-like built-in form instead of a literal:

- `Array<Int>(size = 10, default = 0)`

Indexing:

- `xs[0]` reads from `Array<T>` or `List<T>` and returns `T`.
- `xs[0] = value` writes to `Array<T>` or `List<T>`.
- `table[key]` reads from `Map<K, V>` and returns `V?`.
- `table[key] = value` inserts or replaces in `Map<K, V>`.

Index assignment should be represented explicitly in the AST and bytecode path instead of pretending it is a field assignment. Compound index assignment can be added later by desugaring through the same path.

## Collection API

`Array<T>`:

- `size(): Int`
- `get(index: Int): T`
- `set(index: Int, value: T): Unit`
- `getOrNull(index: Int): T?`

`List<T>`:

- `size(): Int`
- `isEmpty(): Bool`
- `get(index: Int): T`
- `set(index: Int, value: T): Unit`
- `getOrNull(index: Int): T?`
- `add(value: T): Unit`
- `insert(index: Int, value: T): Unit`
- `removeAt(index: Int): T`
- `clear(): Unit`

`Map<K, V>`:

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

Method calls and index syntax should use the same semantic rules. For example, `xs[i]` and `xs.get(i)` have the same result type and bounds behavior.

## Error behavior

`Array<T>` and `List<T>` indexed reads and writes crash the VM on out-of-bounds access. This is a runtime programming error.

`getOrNull(index)` returns `null` instead of crashing.

`Map<K, V>.get(key)` and `map[key]` return `V?` because the key may be absent.

`Map<K, V>.getOrDefault(key, default)` returns `V`.

`Map<K, V>.set(key, value)` and `map[key] = value` insert or replace an entry.

## Map key equality and ordering

Map keys may be any non-null CKL value.

Equality rules:

- `Bool`, `Int`, `Long`, and `String` compare by value.
- Struct values compare by structural value equality.
- Class instances compare by object identity.
- Collection values compare by identity.

Maps preserve insertion order for deterministic `keys()` and `values()`. Replacing an existing key keeps its order. Removing a key and inserting it again moves it to the end.

## Frontend architecture

`TypeSyntax` should become a tree:

- optional qualifier,
- base name,
- type arguments,
- nullable flag,
- source range.

`TypeRef` should become structural and include type arguments. This makes `List<Int>` and `List<String>` distinct compile-time types.

Declarations need type parameter lists:

- `FunctionDeclaration.typeParameters`
- `StructDeclaration.typeParameters`
- `ClassDeclaration.typeParameters`

The analyzer needs generic substitution for:

- function calls,
- struct constructors,
- class constructors,
- instance and static methods,
- fields,
- member access,
- index access,
- index assignment,
- collection literals.

Type inference remains intentionally small. Generic function type arguments are inferred from actual argument types and the expected result type when available. Ambiguous generic calls should produce diagnostics instead of falling back to `Unit` silently.

## Bytecode and VM architecture

Mutable collections should be represented as VM-managed heap objects rather than inline immutable `VmValue` records. This preserves identity, sharing, and mutation semantics.

The VM needs native collection storage for:

- fixed-size arrays,
- growable lists,
- insertion-ordered maps.

Bytecode can support collections through either dedicated instructions or a dedicated native method dispatch path. The design prefers explicit collection instructions for index operations and constructors, while ordinary collection methods can be represented as typed built-in method calls if that keeps the compiler simpler.

`BytecodeVmSnapshot` should include heap state. The current snapshot model only records frames, stack, locals, halted state, and last result. Collections make heap snapshotting more important because mutable collection identity can be stored in locals and object fields.

Memory accounting must include:

- collection containers,
- list/array slots,
- map entries,
- key and value storage,
- nested values,
- object and collection references.

Double-counting shared heap objects should be avoided where practical.

## Parser ambiguity notes

CKL already has blocks, legacy record construction diagnostics, and `while` parsing ambiguity around bare identifier RHS comparisons. Map literals must not make this worse.

Rules:

- Map literals are expressions and are only parsed where an expression is expected.
- Empty `{}` map literals require an expected collection type.
- Legacy `Type { field: value }` remains invalid and should keep its current diagnostic.
- Type arguments are parsed only in type/declaration contexts for the first version; expression `<` and `>` remain comparisons.

## IDE and documentation

The IDE should display generic types in hover, diagnostics, completions, and cleanup/formatting output.

Completions should suggest:

- built-in generic collection types,
- methods for `Array<T>`, `List<T>`, and `Map<K, V>`,
- type parameters in scope,
- user-defined generic structs/classes/functions.

[docs/LANGUAGE.md](../../LANGUAGE.md) should document generics, collections, literals, index access, index assignment, nullability behavior, Map equality, and insertion order.

## Testing strategy

Parser and lexer tests:

- generic type syntax,
- generic declaration syntax,
- nested generic types,
- list and map literals,
- index access,
- index assignment,
- block/record/map ambiguity cases.

Analyzer tests:

- `List<Int>` is not assignable to `List<String>`,
- generic function substitution,
- generic class and struct fields,
- collection method result types,
- `Map<K, V>` key/value checks,
- nullable result of map reads,
- out-of-scope type parameters.

Runtime tests:

- list add/set/remove/index behavior,
- array fixed-size behavior,
- map insert/replace/remove behavior,
- map key equality for primitives, strings, structs, class identity, and collection identity,
- deterministic map ordering,
- VM memory limit accounting,
- heap snapshot round-trip when snapshots are supported.

IDE tests:

- completion of collection methods,
- hover display for generic types,
- diagnostics with full generic type names,
- formatting of generic declarations and literals.

## Staged rollout

1. Generic type model: parse, represent, and display generic type syntax and declarations; add type-parameter scopes and minimal validation, but no runtime collections yet.
2. Native collection runtime: collection values, methods, memory accounting, and runtime tests.
3. Literals and indexing: list/map literals, index access, index assignment, parser and bytecode support.
4. Full user-defined generic substitution for functions, structs, classes, methods, and imports.
5. IDE and documentation polish.

Each stage should keep the compiler tests passing and update [docs/LANGUAGE.md](../../LANGUAGE.md) when user-visible syntax lands.

## Acceptance criteria

- CKL programs can typecheck and run mutable `Array<T>`, `List<T>`, and `Map<K, V>`.
- User-defined generic functions, structs, and classes work with collection and non-collection type arguments.
- Collection misuse produces frontend diagnostics when statically knowable.
- Runtime invalid access is deterministic and reported as a VM crash only for true runtime errors such as out-of-bounds indexed access.
- Map ordering and equality rules are documented and covered by tests.
