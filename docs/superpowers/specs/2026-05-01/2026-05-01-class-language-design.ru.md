# Дизайн классов в CKL

## Цель

Добавить в CKL полноценную фичу `class`, чтобы можно было объединять данные и поведение, сохраняя язык детерминированным, sandboxed и удобным для анализа. Классы — это нативные runtime objects, а не fallback и не desugar в `struct`.

Первая версия фокусируется на одной практичной object model:

- один primary constructor в начале объявления класса;
- публичные `val` и `var` поля;
- `init` blocks;
- instance methods с `this`;
- static methods, вызываемые через dot syntax;
- reference-object semantics для экземпляров класса;
- без inheritance, interfaces, generics, visibility modifiers, reflection и host interop.

## Не-цели

- В первой реализации нет inheritance и interfaces.
- Нет `private`, `protected` или package visibility.
- Нет secondary constructors и overload constructors.
- Нет default constructor arguments.
- Нет destructors, finalizers или reflection.
- Нет fallback из class objects в существующие `struct` records.

Будущий синтаксис для inheritance и interfaces можно зарезервировать через понятные diagnostics, но этот дизайн его не реализует.

## Синтаксис

Class declarations являются top-level declarations:

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

Создание экземпляра использует Kotlin-like named arguments:

```ck
val counter: Counter = Counter(value = 10);
counter.inc();
val zero: Counter = Counter.zero();
```

Constructor parameters могут быть field parameters с `val` или `var`. Параметр без `val`/`var` доступен только при вычислении field initializers и `init` blocks:

```ck
class Label(text: String) {
    val normalized: String = strings::trim(text);
}
```

Body fields разрешены и должны иметь initializers:

```ck
class Point(val x: Int, val y: Int) {
    val lengthSquared: Int = x * x + y * y;
}
```

## Миграция struct construction

`struct` остаётся частью CKL как value-like record type, но создание экземпляров унифицируется с class construction.

Новая форма:

```ck
struct Vec2 { x: Int, y: Int }
val v: Vec2 = Vec2(x = 1, y = 2);
```

Старая record literal form сразу становится invalid:

```ck
val v = Vec2 { x: 1, y: 2 }; // error
```

Так в языке не будет двух конкурирующих syntaxes для создания объектов, а autocomplete/import behavior останется единым.

## Семантика

Экземпляры class — reference objects. Присваивание или передача class value копирует ссылку, а не объект. Изменение `var` field через одну ссылку видно через другие ссылки на тот же object.

```ck
val a: Counter = Counter(value = 1);
val b: Counter = a;
b.inc();
terminal::println(a.current()); // prints 2
```

Правила fields:

- `val` fields назначаются во время construction и не могут быть reassigned после этого.
- `var` fields можно назначать через `this.field` внутри методов или через `object.field` снаружи класса.
- Все fields и methods публичные в первой версии.
- `this` валиден только внутри instance methods и `init` blocks.

Порядок initialization:

1. Вычислить constructor arguments слева направо.
2. Allocate object.
3. Назначить constructor field parameters.
4. Вычислить body field initializers в source order.
5. Выполнить `init` blocks в source order.
6. Вернуть initialized reference.

Если initializer или `init` block даёт diagnostic при analysis, bytecode generation fails как обычно.

## Method calls и static calls

Instance methods используют dot syntax:

```ck
counter.inc();
val n: Int = counter.current();
```

Static methods тоже используют dot syntax на имени class:

```ck
val zero: Counter = Counter.zero();
```

`::` остаётся для built-in namespaces и import aliases. Static calls у классов специально используют `.`, чтобы class members ощущались единой member system.

## Type checking

Semantic analyzer добавляет `ClassBinding` рядом с текущими record/function/module bindings.

Проверки первой реализации:

- duplicate class, struct, function, import или built-in names дают redeclaration diagnostics;
- constructor calls требуют все primary constructor parameters ровно один раз по имени;
- constructor argument names должны существовать;
- constructor argument values должны быть assignable к parameter types;
- `init` blocks не могут возвращать values;
- instance methods получают implicit `this` типа class;
- static methods не могут обращаться к `this`;
- assignment в `val` field вне construction — error;
- assignment в `var` field требует assignable value;
- method calls проверяют receiver type, наличие member, argument count и argument types.

## Runtime и bytecode

Классы требуют нативную object model в bytecode и runtime.

Рекомендуемая модель:

- добавить bytecode metadata для classes: name, fields с mutability и type, instance methods, static methods, init blocks;
- добавить runtime object references, например `VmValue.ObjectRef(id)` плюс deterministic VM heap, где id указывает на object state;
- добавить instructions для object allocation, field get/set, method call, static call и constructor/init execution;
- сохранить существующий `VmValue.RecordValue` для structs;
- equality для class references пока сделать reference equality.

Heap принадлежит VM execution state и остаётся sandboxed. Порядок allocation детерминирован. Host objects не exposed.

## Imports и IDE

Classes являются top-level declarations как `struct` и `fun`:

- selective imports могут импортировать class name: `import "model.ck" { Counter };`;
- namespace imports expose class construction и static calls: `model::Counter` как type и `model::Counter.zero()`, если implementation plan добавит qualified type/member syntax;
- auto-import suggestions должны включать classes, constructor calls и static methods с `sourceNamespace` справа.

IDE completion должен поддерживать:

- class names в type и expression positions;
- named constructor arguments после `ClassName(`;
- instance fields и methods после `object.`;
- static methods после `ClassName.`;
- `this.` members внутри instance methods и `init` blocks.

## Error handling

Предпочтительны прямые diagnostics с invalid syntax или member name в сообщении:

- `Expected named constructor argument.`
- Unknown constructor parameter `name` for class `Counter`.
- Missing constructor argument `value` for class `Counter`.
- Cannot assign to val field `value`.
- Static method cannot access `this`.
- Old record construction syntax is no longer valid. Use `Vec2(x = 1)` instead.

Никакой compatibility fallback не должен silently accept invalid class или construction syntax.

## Testing plan

Implementation plan должен использовать TDD и покрыть:

- parser tests для class declarations, constructor parameters, `init`, instance methods, static methods и rejection старого struct construction;
- semantic tests для constructor argument checking, member resolution, `this`, static restrictions и field mutability;
- bytecode tests для class metadata и method/static calls;
- runtime tests для object identity, shared mutation через references, initialization order и struct call-style construction;
- IDE tests для class/member completions и auto-import suggestions;
- обновления docs в `docs/LANGUAGE.md`.

## Открытые решения для следующих версий

- visibility modifiers;
- interfaces;
- inheritance;
- default constructor arguments;
- method overloading;
- custom equality;
- destructuring или copy helpers для value-like data.