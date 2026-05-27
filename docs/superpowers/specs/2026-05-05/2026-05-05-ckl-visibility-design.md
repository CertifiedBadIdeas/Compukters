# CKL Visibility Design

## Summary

CKL will move to explicit public API declarations. Top-level declarations and class members are private by default. The `pub` keyword marks declarations that are exported from a source file or accessible from outside a class.

This is an intentional breaking change. Runnable programs must declare `pub fun main()`. Library files must mark public functions, structs, classes, fields, and methods with `pub`.

## Goals

- Make library API boundaries explicit.
- Prevent accidental imports of helper functions, implementation structs, and implementation classes.
- Allow public declarations to depend on private declarations in the same file.
- Allow class internals to be hidden behind public methods and fields.
- Keep the model simple: `pub` means externally visible, missing `pub` means private.
- Provide clear migration diagnostics for old CKL source.

## Non-goals

- No re-export or facade modules in this feature.
- No package visibility, `internal`, `protected`, friend modules, or module-private groups.
- No inheritance, interfaces, traits, or generic visibility rules.
- No reflection changes.
- No compatibility mode for implicit public declarations.

## Syntax

### Top-level declarations

```ck
pub struct Vec2 { x: Int, y: Int }

pub class Counter(pub var value: Int) {
    pub fun current(): Int {
        return this.value
    }
}

pub fun add(a: Vec2, b: Vec2): Vec2 {
    return Vec2(x = a.x + b.x, y = a.y + b.y)
}

fun helper(): Int {
    return 1
}
```

Rules:

- `pub fun`, `pub struct`, and `pub class` are exported from the file.
- Top-level `fun`, `struct`, and `class` without `pub` are private to their declaring file.
- Private top-level declarations can be referenced by declarations in the same file.
- Private top-level declarations cannot be imported from another file through selective imports or namespace aliases.

### Entry point

Runnable programs must declare:

```ck
pub fun main() {
    terminal::println("hi")
}
```

Rules:

- `fun main()` without `pub` is invalid as a program entry point.
- Missing `main` is reported as a program-entry diagnostic.
- `pub fun main()` can still call private helpers in the same source file.

### Class members

```ck
pub class Counter(pub var value: Int) {
    var cached: Int = 0

    pub fun current(): Int {
        return this.cached
    }

    fun recalculate(): Int {
        return this.value + 1
    }

    pub static fun zero(): Counter {
        return Counter(value = 0)
    }
}
```

Rules:

- Class constructor parameters marked `pub val` or `pub var` become public fields.
- Class constructor parameters marked `val` or `var` become private fields.
- Class body fields marked `pub val` or `pub var` are public.
- Class body fields marked `val` or `var` are private.
- Class methods marked `pub fun` or `pub static fun` are public.
- Class methods marked `fun` or `static fun` are private.
- `init` blocks do not accept visibility modifiers.
- Private members are accessible from methods and init blocks of the declaring class.
- Private members are not accessible through external receivers.

## Semantic behavior

### Module exports

`ModuleExports` must include only public top-level declarations:

- public functions;
- public structs;
- public classes.

Private declarations remain part of semantic analysis and bytecode compilation for their own source file. This allows public APIs to call private helpers without exposing those helpers to importers.

### Selective imports

```ck
import "math.ck" { add, Vec2 }
```

Rules:

- Selected names must resolve to public exports.
- If the declaration exists but is private, the diagnostic should say that the file has no public export with that name.
- Selective imports should not expose private names to completion or cleanup logic.

### Namespace aliases

```ck
import "math.ck" as math
```

Rules:

- `math::name` resolves only public exports.
- Private exported-file declarations are invisible through aliases.
- Diagnostics for private alias members should match missing public members.

### Class access

Rules:

- Public fields and methods can be accessed from any code that can name the class type.
- Private fields and methods can be accessed only from the declaring class body.
- Static method visibility follows the same rules as instance method visibility.
- A private member that exists should produce a privacy diagnostic instead of a generic missing-member diagnostic when possible.

## Diagnostics

Diagnostics should be specific and migration-friendly.

Required messages:

- `Entry point `main` must be declared as `pub fun main()`.`
- `Program must declare `pub fun main()`.`
- `File `math.ck` has no public export `helper`.`
- `Member `value` of class `Counter` is private.`
- `Unexpected `pub` modifier.` for places where `pub` is syntactically invalid.

Exact punctuation may follow existing diagnostic style, but tests should verify the important substrings.

## Formatter and IDE

Formatter behavior:

- Preserve and render `pub` on public top-level declarations.
- Preserve and render `pub` on public class fields and methods.
- Keep `init` blocks without visibility.
- Continue sorting and merging imports as before.

IDE behavior:

- Add `pub` to keyword completions.
- Highlight `pub` as a keyword.
- User-file auto-import completions should list only public top-level declarations.
- Hover and definition behavior for public declarations remains unchanged.
- Private declarations remain visible in local same-file completion where they are in scope.

## Documentation and migration

Update CKL documentation to describe:

- private-by-default top-level declarations;
- `pub fun main()` as the required entry point;
- public and private class fields and methods;
- imports exposing only public declarations.

Update bundled ROM `.ck` programs so each runnable file uses `pub fun main()`.

Update compiler and runtime tests that embed CKL snippets so runnable snippets use `pub fun main()` and importable library declarations use `pub`.

## Testing strategy

Use TDD for each behavior.

Parser tests:

- parse `pub fun`, `pub struct`, and `pub class`;
- parse `pub val`, `pub var`, `pub fun`, and `pub static fun` inside classes;
- reject misplaced `pub`.

Import tests:

- public function, struct, and class imports succeed;
- private top-level function, struct, and class imports fail;
- namespace aliases expose public declarations and hide private declarations;
- public imported functions can call private helpers in their own file.

Entry-point tests:

- `fun main()` fails with the required migration diagnostic;
- `pub fun main()` compiles.

Class-member tests:

- external reads/calls of private fields, instance methods, and static methods fail;
- public fields, instance methods, and static methods remain accessible;
- class methods can access private fields and methods of the same class.

Formatter and IDE tests:

- formatter preserves `pub` in top-level and class declarations;
- auto-import suggests only public user-file declarations;
- cleanup handles public imports normally.

Verification commands:

- Fast loop: `./gradlew :compiler:test`.
- Full validation: `./gradlew test`.

## Implementation notes

Likely files to modify:

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/FunctionDeclaration.kt`
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/IdePresentationSupport.kt`
- compiler frontend tests under `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/`
- runtime tests that embed CKL snippets
- bundled ROM sources under `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/`
- `docs/LANGUAGE.md`

The implementation should keep private declarations in bytecode compilation. Filtering should happen at the file export boundary and external member-access boundary, not by dropping private declarations from semantic analysis.