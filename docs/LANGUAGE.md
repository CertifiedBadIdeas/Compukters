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
- Inheritance, interfaces, and generics are not part of v1.

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
- `fillRect(displayId: Int, x: Int, y: Int, width: Int, height: Int, color: Int): Unit`
- `present(displayId: Int): Unit`

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
