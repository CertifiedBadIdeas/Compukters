# Compukter Kraft Language v1

`CKL` is a small statically typed language for in-game computers. It is intentionally constrained so the runtime can stay deterministic, sandboxed, and IDE-friendly.

## Syntax

Top-level declarations:

- `struct Vec2 { x: Int, y: Int }`
- `class Counter(var value: Int) { ... }`
- `import "lib/math.ck" { add, Vec2 };`
- `import "lib/math.ck" as math;`
- `fun main() { ... }`
- `fun add(x: Int, y: Int): Int { return x + y; }`

Statements:

- `val name = expr;`
- `var counter: Int = 0;`
- `name = expr;` (reassign a `var`; `val` cannot be reassigned)
- `name += expr;`, `name -= expr;`, `name *= expr;`, `name /= expr;` (compound, desugars to `name = name <op> expr`)
- `if (condition) { ... } else { ... }`
- `if (condition) { ... } else if (condition) { ... } else { ... }`
- `while condition { ... }`
- `when(subject) { value -> { ... } else -> { ... } }`
- `when { condition -> { ... } else -> { ... } }`
- `return expr;`
- expression statements such as `terminal::println("ok");`

### `when` statement

`when` provides multi-way dispatch. Two forms are supported:

With subject (compared via `==`):

```
when(x) {
    1 -> { terminal::println("one"); }
    2, 3 -> { terminal::println("two or three"); }
    else -> { terminal::println("other"); }
}
```

Without subject (each branch is a `Bool` condition):

```
when {
    x > 10 -> { terminal::println("big"); }
    x > 0 -> { terminal::println("positive"); }
    else -> { terminal::println("non-positive"); }
}
```

## Operators

- `::` resolves a name inside a namespace. This is used for built-in modules and aliased user-file imports.
- `.` accesses fields and methods of values. Use `this.field` inside class instance methods and `init` blocks.

Examples:

```
terminal::println("hi");
val id: Int = system::computerId();
val name: String = event.name;
```

Expressions:

- literals: `42`, `42L`, `"text"`, `true`, `false`, `null`
- arithmetic and logic: `+ - * / == != < <= > >= && || !`
- member access: `event.name`
- namespace calls: `terminal::write("hi")`
- function calls: `main()`, `helper()`
- struct construction: `Vec2(x = 1, y = 2)`
- class construction: `Counter(value = 1)`

## Structs

Structs are value-shaped records with named fields. They are declared with `struct` and constructed with the same named call-style syntax as classes:

```ck
struct Vec2 { x: Int, y: Int }

fun main() {
    val v: Vec2 = Vec2(x = 1, y = 2);
    terminal::println("x=" + v.x);
}
```

The old record-literal syntax `Vec2 { x: 1, y: 2 }` is invalid.

## Classes

Classes are reference objects with public fields, `init` blocks, instance methods, and static methods.

```ck
class Counter(var value: Int) {
    init {
        this.value = this.value + 1;
    }

    fun current(): Int {
        return this.value;
    }

    static fun zero(): Counter {
        return Counter(value = 0);
    }
}

fun main() {
    val counter: Counter = Counter.zero();
    terminal::println("value=" + counter.current());
}
```

Rules:

- The primary constructor is declared in parentheses after the class name.
- Constructor parameters marked with `val` or `var` become fields.
- Additional public fields can be declared in the class body with `val` or `var`.
- Constructor calls must use named arguments: `Counter(value = 1)`.
- `this` is available in instance methods and `init` blocks, but not in `static fun`.
- `val` fields can only be assigned during construction; `var` fields can be assigned later.
- Instances have reference identity: two variables can refer to the same object and observe shared mutation.
- Inheritance, interfaces, generics, and private members are not part of v1.

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

Built-in modules (`terminal`, `system`, `filesystem`, `events`, `process`, `strings`, `stdout`) are always available — there is no `import` needed. Access their members with `::`:

```
terminal::println("hi");
val id: Int = system::deviceId();
```

The old builtin-import / dot-call style is no longer valid. Built-ins are ambient; use user-file imports only for `.ck` source files.

## Imports

CKL programs may import selected names from other `.ck` files. The path is interpreted relative to the importing file and must end with `.ck`.

```ck
import "lib/math.ck" { add, Vec2 };  // selected names visible directly
import "lib/math.ck" as m;           // namespace access via `m::name`
import terminal { println };         // selected built-in member visible directly
```

`import "lib/math.ck";` is invalid. Use a selective import list or a namespace alias.

Rules:

- Each top-level `fun`, `struct`, and `class` of an imported file is public.
- Imports are not transitive: importing `a.ck` does not import `a.ck`'s imports.
- The same file is parsed and analysed at most once per compilation, so import cycles are safe.
- Importing the same path twice in one file is a `Duplicate import` error.
- Conflicts between selectively imported names, aliases, local declarations, and built-in module names produce `Redeclaration` diagnostics.

### Selective imports

Selected names become visible directly in the importing file:

```ck
import terminal { println };
import "math.ck" { add, Vec2, Counter };

fun main() {
    val v: Vec2 = Vec2(x = 1, y = 2);
    val counter: Counter = Counter(value = 1);
    println("x=" + add(v, v).x);
}
```

### Aliases as namespaces

An alias behaves like a built-in module and uses `::`:

```
import "math.ck" as m;
val v: m::Vec2 = m::Vec2(x = 1, y = 2);
val w: m::Vec2 = m::add(v, v);
```

## Formatting and cleanup

The CKL IDE API exposes parser-based document formatting and cleanup.

Format Document:

- renders source in the canonical CKL style with four-space indentation;
- keeps line and block comments;
- sorts imports by source and merges duplicate selective import groups;
- sorts names inside selective import groups;
- does not remove unused imports.

Cleanup Document runs the same formatter and additionally removes unused names from selective import groups when the source parses and analyses without errors. If syntax or semantic errors are present, cleanup returns no edits.

Both actions return no edits for invalid or incomplete source instead of attempting a partial rewrite.

In the Workbench editor, Format can be triggered from the toolbar or with `Ctrl+Alt+F`; Cleanup can be triggered from the toolbar or with `Ctrl+Alt+L`.

`terminal`

- `write(text: String): Unit`
- `println(text: String): Unit`
- `clear(): Unit`
- `setCursor(x: Int, y: Int): Unit`

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

`process`

- `argument(): String`
- `currentDirectory(): String`
- `changeDirectory(path: String): Bool`
- `run(path: String): Int`
- `run(path: String, argument: String): Int`

`strings`

- `trim(text: String): String`
- `isBlank(text: String): Bool`
- `beforeSpace(text: String): String`
- `afterSpace(text: String): String`

`stdout`

- `write(text: String): Unit`

Global intrinsics:

- `yield(): Unit`
- `sleep(ticks: Long): Unit`

## Entry Point

Programs start from `fun main()`.

## Files

Source files use the `.ck` extension. The default bundled boot program is `bios.ck`.

## Constraints

- No generics
- No inheritance
- No reflection
- No arbitrary host interop
- All host access goes through builtin modules
