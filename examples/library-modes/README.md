# Mixed library implementations

This example is exercised by `K2CompilerAdapterTest`, in the test named
`source library generic functions and classes specialize in consumer`. The test adds these declarations to the
`test:generic` platform module alongside its other generic fixtures:

```kotlin
package sample

private fun label(size: Int): String = "length=" + size

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

The generic implementation makes the entire `test:generic` module source-only. The test checks that the module has
no precompiled library fragment; its ordinary functions therefore also enter consumer compilation as source.
The same bundle retains the precompiled `stdlib:core` fragment, which supplies `require`.
Both paths coexist in one consumer artifact.

Run the existing end-to-end scenario from the repository root:

```sh
./gradlew-sandbox-dev-parallel-summary testKotlinGenericLibraryVmConformance
```

The scenario builds the bundle, compiles the consumer, and verifies and executes its artifact in the pinned Rust VM.
The example has no independent `compukter.toml`: its custom platform module is assembled by the test fixture.

## Proposed mixed representation

A future mixed-module implementation could retain a precompiled implementation of `describeSize` while supplying
the body of `describe` for specialization. It would also need to preserve access to the private `label` helper,
including its identity and visibility, without generating conflicting definitions from source and compiled code.
The current scenario demonstrates source-only behavior; it does not establish support for that mixed representation.
