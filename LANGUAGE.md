# Compukter Kraft Language v1

`CKL` is a small statically typed language for in-game computers. It is intentionally constrained so the runtime can stay deterministic, sandboxed, and IDE-friendly.

## Syntax

Top-level declarations:

- `import terminal;`
- `import system;`
- `record Vec2 { x: Int, y: Int }`
- `fun main() { ... }`
- `fun add(x: Int, y: Int): Int { return x + y; }`

Statements:

- `let name = expr;`
- `var counter: Int = 0;`
- `if condition { ... } else { ... }`
- `while condition { ... }`
- `return expr;`
- expression statements such as `terminal.printLine("ok");`

Expressions:

- literals: `42`, `42L`, `"text"`, `true`, `false`, `null`
- arithmetic and logic: `+ - * / == != < <= > >= && || !`
- member access: `event.name`
- function calls: `main()`, `terminal.write("hi")`
- record construction: `Vec2 { x: 1, y: 2 }`

## Types

Builtin types:

- `Unit`
- `Bool`
- `Int`
- `Long`
- `String`
- `Event`

User-defined record types are declared with `record`.

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

## Constraints

- No generics
- No inheritance
- No reflection
- No arbitrary host interop
- All host access goes through builtin modules
