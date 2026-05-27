# CKL Class Language Design

## Goal

Add a first-class `class` feature to CKL for grouping data and behavior while keeping the language deterministic, sandboxed, and easy to analyze. Classes are native runtime objects, not a fallback or desugaring to `struct`.

The first version focuses on one practical object model:

- one primary constructor at the start of the class declaration;
- public `val` and `var` fields;
- `init` blocks;
- instance methods with `this`;
- static methods called through dot syntax;
- reference-object semantics for class instances;
- no inheritance, interfaces, generics, visibility modifiers, reflection, or host interop.

## Non-goals

- No inheritance or interfaces in the first implementation.
- No `private`, `protected`, or package visibility.
- No secondary constructors or constructor overloads.
- No default constructor arguments.
- No destructors, finalizers, or reflection.
- No fallback from class objects to existing `struct` records.

Future syntax for inheritance and interfaces may be reserved with clear diagnostics, but it is not implemented by this design.

## Syntax

Class declarations are top-level declarations:

```ck
class Counter(var value: Int) {
    init {
        if (this.value < 0) {
            this.value = 0;
        }
    }

    fun inc(): Unit {
        this.value = this.value + 1;
    }

    fun current(): Int {
        return this.value;
    }

    static fun zero(): Counter {
        return Counter(value = 0);
    }
}
```

Construction uses Kotlin-like named arguments:

```ck
val counter: Counter = Counter(value = 10);
counter.inc();
val zero: Counter = Counter.zero();
```

Constructor parameters may be `val` or `var` field parameters. A parameter without `val`/`var` is only available while evaluating field initializers and `init` blocks:

```ck
class Label(text: String) {
    val normalized: String = strings::trim(text);
}
```

Body fields are allowed and must have initializers:

```ck
class Point(val x: Int, val y: Int) {
    val lengthSquared: Int = x * x + y * y;
}
```

## Struct construction migration

`struct` remains part of CKL as a value-like record type, but instance construction is unified with class construction.

New form:

```ck
struct Vec2 { x: Int, y: Int }
val v: Vec2 = Vec2(x = 1, y = 2);
```

The old record literal form is invalid immediately:

```ck
val v = Vec2 { x: 1, y: 2 }; // error
```

This avoids two competing construction syntaxes and keeps autocomplete/import behavior consistent.

## Semantics

Class instances are reference objects. Assigning or passing a class value copies the reference, not the object. Mutating a `var` field through one reference is visible through other references to the same object.

```ck
val a: Counter = Counter(value = 1);
val b: Counter = a;
b.inc();
terminal::println(a.current()); // prints 2
```

Field rules:

- `val` fields are assigned during construction and cannot be reassigned afterward.
- `var` fields can be assigned through `this.field` inside methods or through `object.field` from outside the class.
- All fields and methods are public in the first version.
- `this` is only valid inside instance methods and `init` blocks.

Initialization order:

1. Evaluate constructor arguments left-to-right.
2. Allocate the object.
3. Assign constructor field parameters.
4. Evaluate body field initializers in source order.
5. Execute `init` blocks in source order.
6. Return the initialized reference.

If an initializer or `init` block produces a diagnostic during analysis, bytecode generation fails as usual.

## Method calls and static calls

Instance methods use dot syntax:

```ck
counter.inc();
val n: Int = counter.current();
```

Static methods also use dot syntax on the class name:

```ck
val zero: Counter = Counter.zero();
```

`::` remains for built-in namespaces and import aliases. Class static calls intentionally use `.` so class members feel like one coherent member system.

## Type checking

The semantic analyzer adds a `ClassBinding` beside the existing record/function/module bindings.

Checks required for the first implementation:

- duplicate class, struct, function, import, or built-in names produce redeclaration diagnostics;
- constructor calls require all primary constructor parameters exactly once by name;
- constructor argument names must exist;
- constructor argument values must be assignable to parameter types;
- `init` blocks cannot return values;
- instance methods receive an implicit `this` of the class type;
- static methods cannot access `this`;
- `val` field assignment outside construction is an error;
- `var` field assignment requires an assignable value;
- method calls validate receiver type, member existence, argument count, and argument types.

## Runtime and bytecode

Classes require a native object model in bytecode and runtime.

Recommended model:

- add bytecode metadata for classes: name, fields with mutability and type, instance methods, static methods, init blocks;
- add runtime object references, for example `VmValue.ObjectRef(id)` plus a deterministic VM heap mapping ids to object state;
- add instructions for object allocation, field get/set, method call, static call, and constructor/init execution;
- keep existing `VmValue.RecordValue` for structs;
- implement equality for class references as reference equality for now.

The heap is owned by the VM execution state and remains sandboxed. Object allocation order is deterministic. No host objects are exposed.

## Imports and IDE

Classes are top-level declarations like `struct` and `fun`:

- selective imports may import a class name: `import "model.ck" { Counter };`;
- namespace imports expose class construction and static calls: `model::Counter` as a type and `model::Counter.zero()` if qualified type/member syntax is supported by the implementation plan;
- auto-import suggestions should include classes, constructor calls, and static methods with `sourceNamespace` shown on the right.

IDE completion should support:

- class names in type and expression positions;
- named constructor arguments after `ClassName(`;
- instance fields and methods after `object.`;
- static methods after `ClassName.`;
- `this.` members inside instance methods and `init` blocks.

## Error handling

Prefer direct diagnostics with the invalid syntax or member name in the message:

- `Expected named constructor argument.`
- Unknown constructor parameter `name` for class `Counter`.
- Missing constructor argument `value` for class `Counter`.
- Cannot assign to val field `value`.
- Static method cannot access `this`.
- Old record construction syntax is no longer valid. Use `Vec2(x = 1)` instead.

No compatibility fallback should silently accept invalid class or construction syntax.

## Testing plan

The implementation plan should use TDD and cover:

- parser tests for class declarations, constructor parameters, `init`, instance methods, static methods, and old struct construction rejection;
- semantic tests for constructor argument checking, member resolution, `this`, static restrictions, and field mutability;
- bytecode tests for class metadata and method/static calls;
- runtime tests for object identity, shared mutation through references, initialization order, and struct call-style construction;
- IDE tests for class/member completions and auto-import suggestions;
- docs updates in `docs/LANGUAGE.md`.

## Open decisions for later versions

- visibility modifiers;
- interfaces;
- inheritance;
- default constructor arguments;
- method overloading;
- custom equality;
- destructuring or copy helpers for value-like data.