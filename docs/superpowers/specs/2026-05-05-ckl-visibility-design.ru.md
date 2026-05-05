# Дизайн CKL visibility

## Кратко

CKL переходит к явному описанию публичного API. Top-level declarations и class members становятся private по умолчанию. Keyword `pub` помечает declarations, которые экспортируются из source file или доступны снаружи class.

Это намеренный breaking change. Runnable programs должны объявлять `pub fun main()`. Library files должны помечать публичные functions, structs, classes, fields и methods через `pub`.

## Цели

- Сделать границы библиотечного API явными.
- Предотвратить случайный import helper-функций, implementation structs и implementation classes.
- Разрешить public declarations зависеть от private declarations в том же файле.
- Позволить class internals скрываться за public methods и fields.
- Сохранить простую модель: `pub` означает externally visible, отсутствие `pub` означает private.
- Дать понятные migration diagnostics для старого CKL source.

## Не цели

- Не реализовывать re-export или facade modules в этой фиче.
- Не реализовывать package visibility, `internal`, `protected`, friend modules или module-private groups.
- Не реализовывать inheritance, interfaces, traits или generic visibility rules.
- Не менять reflection.
- Не добавлять compatibility mode для implicit public declarations.

## Синтаксис

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

Правила:

- `pub fun`, `pub struct` и `pub class` экспортируются из файла.
- Top-level `fun`, `struct` и `class` без `pub` private для declaring file.
- Private top-level declarations можно использовать из declarations в том же файле.
- Private top-level declarations нельзя импортировать из другого файла через selective imports или namespace aliases.

### Entry point

Runnable programs должны объявлять:

```ck
pub fun main() {
    terminal::println("hi")
}
```

Правила:

- `fun main()` без `pub` невалиден как program entry point.
- Отсутствующий `main` сообщается как program-entry diagnostic.
- `pub fun main()` может вызывать private helpers в том же source file.

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

Правила:

- Class constructor parameters с `pub val` или `pub var` становятся public fields.
- Class constructor parameters с `val` или `var` становятся private fields.
- Class body fields с `pub val` или `pub var` public.
- Class body fields с `val` или `var` private.
- Class methods с `pub fun` или `pub static fun` public.
- Class methods с `fun` или `static fun` private.
- `init` blocks не принимают visibility modifiers.
- Private members доступны из methods и init blocks declaring class.
- Private members недоступны через external receivers.

## Semantic behavior

### Module exports

`ModuleExports` должен включать только public top-level declarations:

- public functions;
- public structs;
- public classes.

Private declarations остаются частью semantic analysis и bytecode compilation для своего source file. Это позволяет public API вызывать private helpers, не экспортируя helpers импортёрам.

### Selective imports

```ck
import "math.ck" { add, Vec2 }
```

Правила:

- Selected names должны резолвиться в public exports.
- Если declaration существует, но private, diagnostic должен сказать, что у файла нет public export с этим именем.
- Selective imports не должны показывать private names в completion или cleanup logic.

### Namespace aliases

```ck
import "math.ck" as math
```

Правила:

- `math::name` резолвится только в public exports.
- Private declarations импортируемого файла невидимы через aliases.
- Diagnostics для private alias members должны совпадать с missing public members.

### Class access

Правила:

- Public fields и methods доступны из любого кода, который может назвать class type.
- Private fields и methods доступны только из declaring class body.
- Static method visibility работает по тем же правилам, что и instance method visibility.
- Private member, который существует, по возможности должен давать privacy diagnostic вместо generic missing-member diagnostic.

## Diagnostics

Diagnostics должны быть конкретными и удобными для migration.

Обязательные сообщения:

- `Entry point `main` must be declared as `pub fun main()`.`
- `Program must declare `pub fun main()`.`
- `File `math.ck` has no public export `helper`.`
- `Member `value` of class `Counter` is private.`
- `Unexpected `pub` modifier.` для мест, где `pub` синтаксически невалиден.

Точная пунктуация может следовать существующему стилю diagnostics, но tests должны проверять важные substrings.

## Formatter и IDE

Formatter behavior:

- Сохранять и печатать `pub` у public top-level declarations.
- Сохранять и печатать `pub` у public class fields и methods.
- Оставлять `init` blocks без visibility.
- Продолжать сортировать и merge imports как раньше.

IDE behavior:

- Добавить `pub` в keyword completions.
- Highlight `pub` как keyword.
- User-file auto-import completions должны показывать только public top-level declarations.
- Hover и definition behavior для public declarations не меняются.
- Private declarations остаются видимыми в local same-file completion там, где они in scope.

## Documentation и migration

Обновить CKL documentation, чтобы описать:

- private-by-default top-level declarations;
- `pub fun main()` как обязательный entry point;
- public и private class fields/methods;
- imports, которые раскрывают только public declarations.

Обновить bundled ROM `.ck` programs так, чтобы каждый runnable file использовал `pub fun main()`.

Обновить compiler и runtime tests со встроенными CKL snippets так, чтобы runnable snippets использовали `pub fun main()`, а импортируемые library declarations использовали `pub`.

## Testing strategy

Использовать TDD для каждого behavior.

Parser tests:

- parse `pub fun`, `pub struct` и `pub class`;
- parse `pub val`, `pub var`, `pub fun` и `pub static fun` inside classes;
- reject misplaced `pub`.

Import tests:

- public function, struct и class imports succeed;
- private top-level function, struct и class imports fail;
- namespace aliases expose public declarations and hide private declarations;
- public imported functions can call private helpers in their own file.

Entry-point tests:

- `fun main()` fails with the required migration diagnostic;
- `pub fun main()` compiles.

Class-member tests:

- external reads/calls of private fields, instance methods и static methods fail;
- public fields, instance methods и static methods remain accessible;
- class methods can access private fields and methods of the same class.

Formatter и IDE tests:

- formatter preserves `pub` in top-level and class declarations;
- auto-import suggests only public user-file declarations;
- cleanup handles public imports normally.

Verification commands:

- Fast loop: `./gradlew :compiler:test`.
- Full validation: `./gradlew test`.

## Implementation notes

Вероятные files to modify:

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

Implementation должен сохранять private declarations в bytecode compilation. Filtering должен происходить на file export boundary и external member-access boundary, а не через удаление private declarations из semantic analysis.