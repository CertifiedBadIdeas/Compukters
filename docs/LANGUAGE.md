# Compukter Kraft Language v1

`CKL` is a small statically typed language for in-game computers. It is intentionally constrained so the runtime can stay deterministic, sandboxed, and IDE-friendly.

## Syntax

Top-level declarations:

- `struct Vec2 { x: Int, y: Int }`
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

- `::` resolves a name inside a namespace. Today this is used for built-in modules; user-file import aliases will use the same operator in a future version.
- `.` accesses fields of struct values.

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
- struct construction: `Vec2 { x: 1, y: 2 }`

## Types

Builtin types:

- `Unit`
- `Bool`
- `Int`
- `Long`
- `String`
- `Event`

User-defined struct types are declared with `struct`.

## Builtin Modules

### Built-in Modules Are Ambient

Built-in modules (`terminal`, `system`, `filesystem`, `events`, `process`, `strings`, `stdout`) are always available — there is no `import` needed. Access their members with `::`:

```
terminal::println("hi");
val id: Int = system::deviceId();
```

The old `import terminal;` / `terminal.println(...)` style is no longer valid. User-file imports are coming in a future version.

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
