# Mixed library implementations

This example is exercised by `K2CompilerAdapterTest`, in the test named
`source library generic functions and classes specialize in consumer`. The test adds these declarations to the
`test:generic` platform module alongside its other generic fixtures:

```kotlin
package sample

private fun label(size: Int): String {
    require(size >= 0)
    return "length=" + size
}

fun <T> describe(value: T, measure: (T) -> Int): String = label(measure(value))

fun describeSize(size: Int): String = label(size)
```

The consuming program checks both generic specializations and the ordinary public function:

```kotlin
import sample.describe
import sample.describeSize

fun main() {
    require(describe("hello") { it.length } == "length=5")
    require(describe(42) { it } == "length=42")
    require(describeSize(7) == "length=7")
}
```

## Current representation

Platform ABI 3 marks `describe` as requiring source compilation while retaining a precompiled fragment containing
`describeSize` and the private `label` helper. The consumer specializes `describe` for `String` and `Int`, and both
specializations call the compiled helper. The test checks that the helper and ordinary public function are each
emitted once, and rejects direct access to both private and internal helpers from the consumer module.
The same bundle retains the precompiled `stdlib:core` fragment, which supplies `require`. Both the helper and the
consumer call `require`; the test checks that this shared dependency also appears only once in the final artifact.

Run the existing end-to-end scenario from the repository root:

```sh
./gradlew-sandbox-dev-parallel-summary testKotlinGenericLibraryVmConformance
```

The scenario builds the bundle, compiles the consumer, and verifies and executes its artifact in the pinned Rust VM.
The example has no independent `compukter.toml`: its custom platform module is assembled by the test fixture.

## Ownership

The module retains its complete canonical sources for Kotlin resolution, but source declaration identities decide
which implementations belong to consumer compilation. Ordinary implementations bind to compiled library exports.
Private visibility is checked by Kotlin rather than inferred from the executable fragment's linking exports.
Library dependencies use symbolic identities during compilation; final linking restores container-relative indexes
and removes unreachable code.
