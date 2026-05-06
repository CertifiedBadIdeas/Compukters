# Compukter Kraft Language v1

`CKL` is a small statically typed language for in-game computers. It is intentionally constrained so the runtime can stay deterministic, sandboxed, and IDE-friendly.

## Syntax

Top-level declarations:

- `struct Vec2 { x: Int, y: Int }`
- `class Counter(var value: Int) { ... }`
- `import "lib/math.ck" { add, Vec2 }`
- `import "lib/math.ck" as math`
- `pub fun main() { ... }`
- `pub fun add(x: Int, y: Int): Int { return x + y }`
- `pub struct`, `pub class`, `pub val`, `pub var`, and `pub fun` mark library/API surface visible outside the declaring file or class.

Statements:

- `val name = expr`
- `var counter: Int = 0`
- `name = expr` (reassign a `var`; `val` cannot be reassigned)
- `collection[index] = expr` (assigns an `Array`, `List`, or `Map` element)
- `name += expr`, `name -= expr`, `name *= expr`, `name /= expr` (compound, desugars to `name = name <op> expr`)
- `if (condition) { ... } else { ... }`
- `if (condition) { ... } else if (condition) { ... } else { ... }`
- `while condition { ... }`
- `when(subject) { value -> { ... } else -> { ... } }`
- `when { condition -> { ... } else -> { ... } }`
- `return expr`
- expression statements such as `system::log("ok")`

### `when` statement

`when` provides multi-way dispatch. Two forms are supported:

With subject (compared via `==`):

```
when(x) {
    1 -> { system::log("one") }
    2, 3 -> { system::log("two or three") }
    else -> { system::log("other") }
}
```

Without subject (each branch is a `Bool` condition):

```
when {
    x > 10 -> { system::log("big") }
    x > 0 -> { system::log("positive") }
    else -> { system::log("non-positive") }
}
```

## Operators

- `::` resolves a name inside a namespace. This is used for built-in modules and aliased user-file imports.
- `.` accesses fields and methods of values. Use `this.field` inside class instance methods and `init` blocks.

Examples:

```
system::log("hi")
val id: Int = system::computerId()
val name: String = event.name
```

Expressions:

- literals: `42`, `42L`, `"text"`, `true`, `false`, `null`
- arithmetic and logic: `+ - * / == != < <= > >= && || !`
- `+` concatenates strings when either side is `String`; non-string values are converted to text for that expression.
- member access: `event.name`
- namespace calls: `display::present(id)`
- function calls: `main()`, `helper()`
- struct construction: `Vec2(x = 1, y = 2)`
- class construction: `Counter(value = 1)`
- list literals: `[1, 2, 3]`
- map literals: `{"a": 1, "b": 2}`
- indexing: `xs[0]`, `table["key"]`
- array construction: `Array<Int>(size = 4, default = 0)`

## Structs

Structs are value-shaped records with named fields. They are declared with `struct` and constructed with the same named call-style syntax as classes:

```ck
pub struct Vec2 { x: Int, y: Int }

pub fun main() {
    val v: Vec2 = Vec2(x = 1, y = 2)
    system::log("x=" + v.x)
}
```

The old record-literal syntax `Vec2 { x: 1, y: 2 }` is invalid.

## Classes

Classes are reference objects with fields, `init` blocks, instance methods, and static methods. Class members are private by default; mark constructor fields, body fields, instance methods, and static methods with `pub` when code outside the class should access them.

```ck
pub class Counter(pub var value: Int) {
    init {
        this.value = this.value + 1
    }

    pub fun current(): Int {
        return this.value
    }

    pub static fun zero(): Counter {
        return Counter(value = 0)
    }
}

pub fun main() {
    val counter: Counter = Counter.zero()
    system::log("value=" + counter.current())
}
```

Rules:

- The primary constructor is declared in parentheses after the class name.
- Constructor parameters marked with `val` or `var` become fields.
- Additional fields can be declared in the class body with `val` or `var`; use `pub val` or `pub var` to expose them.
- Class body fields may omit an initializer only when they are definitely assigned on every construction path in an `init` block.
- Constructor calls must use named arguments: `Counter(value = 1)`.
- `this` is available in instance methods and `init` blocks, but not in `static fun`.
- `val` fields can only be assigned during construction; `var` fields can be assigned later.
- Instances have reference identity: two variables can refer to the same object and observe shared mutation.
- Inheritance and interfaces are not part of v1.

## Visibility

Top-level declarations and class members are private by default.

- `pub fun`, `pub struct`, and `pub class` are exported from the file and can be imported from other `.ck` files.
- Top-level declarations without `pub` can still be used by other declarations in the same file, but cannot be imported.
- `pub val` and `pub var` expose class fields created from constructor parameters or class-body fields.
- `pub fun` and `pub static fun` expose instance and static methods.
- Private class members can be accessed from methods and `init` blocks of the declaring class, but not from external code.
- `init` blocks do not accept `pub`.
- Runnable programs must declare `pub fun main()`.

## Types

Builtin types:

- `Unit`
- `Bool`
- `Int`
- `Long`
- `String`
- `Event`

User-defined struct types are declared with `struct`. User-defined reference object types are declared with `class`.

## Generics

Functions, structs, and classes can declare type parameters. Generic type arguments are checked at compile time and erased at runtime.

```ck
pub struct Pair<A, B> { first: A, second: B }

pub class Box<T>(pub var value: T) {
    pub fun current(): T {
        return this.value
    }
}

pub fun identity<T>(value: T): T {
    return value
}
```

Generic types use angle brackets and may be nested:

```ck
val xs: List<Int> = [1, 2, 3]
val groups: Map<String, List<Int>> = {"even": [2, 4]}
```

## Collections

CKL has three mutable native collection types:

- `Array<T>`: fixed-size mutable indexed storage.
- `List<T>`: growable mutable indexed storage.
- `Map<K, V>`: mutable insertion-ordered key/value storage.

Collection type arguments are enforced by the compiler. Runtime collection values are native reference objects.

### `Array<T>`

Create arrays with an explicit generic constructor:

```ck
val pixels: Array<Int> = Array<Int>(size = 16, default = 0)
pixels[0] = 255
val first: Int = pixels[0]
```

Methods:

- `size(): Int`
- `get(index: Int): T`
- `set(index: Int, value: T): Unit`
- `getOrNull(index: Int): T?`

### `List<T>`

List literals use square brackets. Empty list literals require an expected `List<T>` type.

```ck
val xs: List<Int> = [1, 2]
xs.add(3)
xs[0] = 42
```

Methods:

- `size(): Int`
- `isEmpty(): Bool`
- `get(index: Int): T`
- `set(index: Int, value: T): Unit`
- `getOrNull(index: Int): T?`
- `add(value: T): Unit`
- `insert(index: Int, value: T): Unit`
- `removeAt(index: Int): T`
- `clear(): Unit`

### `Map<K, V>`

Map literals use `{key: value}` entries. Empty map literals require an expected `Map<K, V>` type.

```ck
val ports: Map<String, Int> = {"http": 80, "https": 443}
ports["ssh"] = 22
val maybePort: Int? = ports["http"]
```

Methods:

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

Map keys may be any non-null CKL value/reference type. Primitive values and strings compare by value, structs compare structurally, and class/collection objects compare by reference identity. `Map` preserves insertion order for `keys()` and `values()`.

### Indexing

`Array<T>` and `List<T>` indexed reads return `T`; invalid indexes crash the running program. `getOrNull(index)` returns `null` instead.

`Map<K, V>` indexed reads return `V?` because a key can be absent:

```ck
val value: Int? = ports["missing"]
```

Indexed assignment mutates the receiver:

```ck
xs[1] = 99
ports["debug"] = 5005
```

## Builtin Modules

### Built-in Modules Are Ambient

Built-in modules (`display`, `system`, `filesystem`, `events`, `process`, `ipc`, `strings`) are always available — there is no `import` needed. Access their members with `::`:

```
system::log("hi")
val id: Int = system::deviceId()
```

The old builtin-import / dot-call style is no longer valid. Built-ins are ambient; use user-file imports only for `.ck` source files.

## Imports

CKL programs may import selected names from other `.ck` files. The path is interpreted relative to the importing file and must end with `.ck`.

```ck
import "lib/math.ck" { add, Vec2 }  // selected names visible directly
import "lib/math.ck" as m           // namespace access via `m::name`
```

`import "lib/math.ck"` is invalid. Use a selective import list or a namespace alias.

Rules:

- Only top-level declarations marked `pub` in an imported file are importable.
- Imports are not transitive: importing `a.ck` does not import `a.ck`'s imports.
- The same file is parsed and analysed at most once per compilation, so import cycles are safe.
- Importing the same path twice in one file is a `Duplicate import` error.
- Conflicts between selectively imported names, aliases, local declarations, and built-in module names produce `Redeclaration` diagnostics.

### Selective imports

Selected names become visible directly in the importing file:

```ck
import "math.ck" { add, Vec2, Counter }

pub fun main() {
    val v: Vec2 = Vec2(x = 1, y = 2)
    val counter: Counter = Counter(value = 1)
    system::log("x=" + add(v, v).x)
}
```

### Aliases as namespaces

An alias behaves like a built-in module and uses `::`:

```
import "math.ck" as m
val v: m::Vec2 = m::Vec2(x = 1, y = 2)
val w: m::Vec2 = m::add(v, v)
```

## Formatting and cleanup

The CKL IDE API exposes parser-based document formatting and cleanup.

Format Document:

- renders source in the canonical CKL style with four-space indentation and without semicolons.
- keeps line and block comments.
- sorts imports by source and merges duplicate selective import groups.
- sorts names inside selective import groups.
- does not remove unused imports.

Cleanup Document runs the same formatter and additionally removes unused names from selective import groups when the source parses and analyses without errors. If syntax or semantic errors are present, cleanup returns no edits.

Both actions return no edits for invalid or incomplete source instead of attempting a partial rewrite.

In the Workbench editor, Format can be triggered from the toolbar or with `Ctrl+Alt+F`. Cleanup can be triggered from the toolbar or with `Ctrl+Alt+L`.

`display`

- `primary(): Int`
- `width(displayId: Int): Int`
- `height(displayId: Int): Int`
- `clear(displayId: Int, color: Int): Unit`
- `setPixel(displayId: Int, x: Int, y: Int, color: Int): Unit`
- `fillRect(displayId: Int, x: Int, y: Int, width: Int, height: Int, color: Int): Unit`
- `copyRect(displayId: Int, srcX: Int, srcY: Int, width: Int, height: Int, dstX: Int, dstY: Int): Unit`
- `blitMono(displayId: Int, x: Int, y: Int, width: Int, height: Int, mask: String, foreground: Int, background: Int): Unit`
- `blitMono5x7(displayId: Int, x: Int, y: Int, row0: Int, row1: Int, row2: Int, row3: Int, row4: Int, row5: Int, row6: Int, foreground: Int, background: Int): Unit`
- `present(displayId: Int): Unit`

`copyRect` copies pixels inside the display back buffer and is useful for scrolling or moving rectangular regions. `blitMono` draws a row-major `0`/`1` monochrome mask; `1` writes the foreground color and `0` writes the background color, or remains transparent when `background < 0`. `blitMono5x7` draws a fixed 5x7 monochrome bitmap from seven numeric row masks; each row uses the low five bits from left to right (`14` is `01110`, `17` is `10001`, `31` is `11111`).

`filesystem`

- `exists(path: String): Bool`
- `readText(path: String): String`
- `writeText(path: String, text: String): Unit`

`system`

- `deviceId(): Int`
- `currentTick(): Long`
- `label(): String`
- `profileName(): String`
- `log(message: String): Unit`
- `shutdown(): Unit`
- `reboot(): Unit`

`events`

- `pull(): Event`
- `pull(filter: String): Event`
- `tryPull(): Event`
- `tryPull(filter: String): Event`
- `argCount(event: Event): Int`
- `argInt(event: Event, index: Int): Int`
- `argBool(event: Event, index: Int): Bool`
- `argString(event: Event, index: Int): String`

`Event` fields:

- `name: String`
- `id: Int`
- `argCount: Int`

`process`

- `argument(): String`
- `currentDirectory(): String`
- `changeDirectory(path: String): Bool`
- `spawn(path: String): Int`
- `spawn(path: String, argument: String): Int`
- `wait(pid: Int): Int`
- `run(path: String): Int`
- `run(path: String, argument: String): Int`

`process::run(path, argument)` is a compatibility helper equivalent to `process::wait(process::spawn(path, argument))`.

`ipc`

- `open(): Int`
- `write(channelId: Int, text: String): Unit`
- `read(channelId: Int): String`
- `tryRead(channelId: Int): String`
- `close(channelId: Int): Unit`

IPC is a low-level VM-local text channel primitive. The runtime does not attach stdin/stdout/stderr meaning to channels.

`strings`

- `trim(text: String): String`
- `isBlank(text: String): Bool`
- `beforeSpace(text: String): String`
- `afterSpace(text: String): String`
- `toInt(text: String): Int`
- `length(text: String): Int`
- `charAt(text: String, index: Int): String`

Global intrinsics:

- `yield(): Unit`
- `sleep(ticks: Long): Unit`

## ROM stdio convention

Bundled ROM programs that need input/output import `stdio.ck` and receive channel ids through `process::argument()` using this tagged format:

```text
stdio-v1 <stdin-channel-id> <stdout-channel-id> <stderr-channel-id> <command-argument>
```

`terminal.ck` is a ROM display program: it opens these channels, spawns `shell.ck` with a `stdio-v1` descriptor, translates key/paste events into line input, and renders shell output to the framebuffer with `display::*`. `shell.ck` and command programs use the `stdio.ck` helpers instead of built-in terminal APIs.

The tagged stdio format is a ROM/process convention only. Runtime APIs remain generic `ipc`, `events`, and `process` primitives. The VM does not provide `terminal` or `stdout` built-ins.

## Entry Point

Programs start from `pub fun main()`.

## Files

Source files use the `.ck` extension. The default bundled boot program is `bios.ck`.

## Constraints

- No generics
- No inheritance
- No reflection
- No arbitrary host interop
- All host access goes through builtin modules
