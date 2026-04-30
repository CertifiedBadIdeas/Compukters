# Compukter Kraft Language v1

`CKL` is a small statically typed language for in-game computers. It is intentionally constrained so the runtime can stay deterministic, sandboxed, and IDE-friendly.

## Syntax

Top-level declarations:

- `import terminal;`
- `import system;`
- `struct Vec2 { x: Int, y: Int }`
- `fun main() { ... }`
- `fun add(x: Int, y: Int): Int { return x + y; }`

Statements:

- `val name = expr;`
- `var counter: Int = 0;`
- `name = expr;` (reassign a `var`; `val` cannot be reassigned)
- `if (condition) { ... } else { ... }`
- `if (condition) { ... } else if (condition) { ... } else { ... }`
- `while condition { ... }`
- `when(subject) { value -> { ... } else -> { ... } }`
- `when { condition -> { ... } else -> { ... } }`
- `return expr;`
- expression statements such as `terminal.printLine("ok");`

### `when` statement

`when` provides multi-way dispatch. Two forms are supported:

With subject (compared via `==`):

```
when(x) {
    1 -> { terminal.printLine("one"); }
    2, 3 -> { terminal.printLine("two or three"); }
    else -> { terminal.printLine("other"); }
}
```

Without subject (each branch is a `Bool` condition):

```
when {
    x > 10 -> { terminal.printLine("big"); }
    x > 0 -> { terminal.printLine("positive"); }
    else -> { terminal.printLine("non-positive"); }
}
```

Expressions:

- literals: `42`, `42L`, `"text"`, `true`, `false`, `null`
- arithmetic and logic: `+ - * / == != < <= > >= && || !`
- member access: `event.name`
- function calls: `main()`, `terminal.write("hi")`
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

`terminal`

- `write(text: String): Unit`
- `printLine(text: String): Unit`
- `clear(): Unit`
- `setCursor(x: Int, y: Int): Unit`

`filesystem`

- `exists(path: String): Bool`
- `readText(path: String): String`
- `writeText(path: String, text: String): Unit`

`system`

- `computerId(): Int`
- `currentTick(): Long`
- `label(): String`
- `profileName(): String`
- `log(message: String): Unit`
- `shutdown(): Unit`
- `reboot(): Unit`

`events`

- `pull(): Event`
- `pull(filter: String): Event`

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
