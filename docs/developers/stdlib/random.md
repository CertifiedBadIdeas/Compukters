---
layout: default
title: Reproducible random numbers
section: developers
permalink: /STDLIB-SUPPORT/random/
---

# Reproducible random numbers

[← Guest standard library support]({{ '/STDLIB-SUPPORT/' | relative_url }})

**Status:** Supported in the current checkout, in `stdlib:core` 1.16.0. Import `kotlin.random.*`.
The API follows [Kotlin Random](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.random/-random/)
for the explicitly listed signatures. Collection `random`, `shuffle` and `shuffled` are not part of this addition.

```kotlin
import kotlin.random.*

fun main() {
    val seeded = Random(42)
    val die = seeded.nextInt(1, 7)
    val unit = seeded.nextDouble()
    val bytes = seeded.nextBytes(16)
    val inclusive = Random.nextUInt(1u..6u)
}
```

## Executable surface

`Random` is an abstract class with `nextBits(bitCount: Int): Int`. Guest subclasses can implement it
and inherit or override the open signed, floating and byte methods. `Random(seed: Int)` and
`Random(seed: Long)` create independent sources; Int seeds sign-extend to Long.

| API | Bounds and result |
| --- | --- |
| `nextBits(Int)` | Low 0..32 bits filled, upper bits zero; 32 bits may form a negative Int |
| `nextInt()`, `nextLong()` | Full signed domain |
| `nextInt(until)`, `nextLong(until)` | `[0, until)` |
| `nextInt(from, until)`, `nextLong(from, until)` | `[from, until)` including widths that overflow signed subtraction |
| `nextInt(IntRange)`, `nextLong(LongRange)` | Inclusive, including the full signed domain |
| `nextBoolean()` | One random bit |
| `nextFloat()`, `nextDouble()` | `[0, 1)` with 24 and 53 random fractional bits |
| `nextDouble(until)`, `nextDouble(from, until)` | Finite increasing bounds, exclusive upper endpoint |
| `nextBytes(ByteArray)`, `nextBytes(size: Int)` | Fill the supplied array or allocate and fill a new one |
| `nextBytes(ByteArray, fromIndex: Int, toIndex: Int)` | Fill exactly `[fromIndex, toIndex)` and return the same array |
| `nextUInt`, `nextULong` extensions | Full unsigned domain, `until`, `from/until`, and inclusive range overloads |
| `nextUBytes` extensions | UByteArray, size, and explicit three-argument subrange overloads |

Byte subrange overloads require both index arguments; arbitrary omitted-index defaults are not exposed.
Integer bounds use rejection sampling before reduction, avoiding modulo bias. Rejection loops consume
instruction quota as ordinary Guest loops; there is no fixed instruction cost per bounded draw.
Floating results round down from the upper endpoint when necessary, including adjacent representable
bounds and finite ranges whose width overflows Double. Invalid bounds, empty inclusive ranges, invalid
bit counts, and byte index ranges throw `IllegalArgumentException` before consuming state or modifying
an array. Negative allocation sizes use the existing `NegativeArraySizeException` behavior.

## State and reproducibility

`Random.Default` is the companion object, also accessible as `Random`. It owns one source seeded with
`0L` per program/session. Aliases and cooperative tasks share it; draw order determines which task receives
which value. Starting a fresh program restarts this fixed sequence. There is no clock or host-entropy input.
Use independent seeded sources when task scheduling must not influence a particular sequence.

The seeded generator uses SplitMix64: increment a wrapping Long state by `0x9e3779b97f4a7c15`, mix with
unsigned shifts 30/27/31 and multipliers `0xbf58476d1ce4e5b9` / `0x94d049bb133111eb`, and take the requested
high bits into the low result bits. `nextBits(0)` does not advance state. A full Int consumes one word;
a Long concatenates two 32-bit draws. Float consumes one 24-bit draw; Double concatenates 26 and 27 bits.
Each filled byte consumes one 8-bit draw. Bounded draws may reject and consume more words. Empty byte
fills consume none. These consumption rules make Compukters sequences reproducible; they do not match
Kotlin/JVM Random. Future algorithm changes require an explicit library contract/version decision.

Generator objects and Default state are managed Guest heap data. They retain state across quota slices,
GC and computer checkpoints without a host capability or new persistence encoding. Generator/array
allocations and arithmetic use existing VM heap and instruction quotas. This is a pseudorandom source
for simulations and gameplay, not cryptography.

## Evidence and compatibility

`MinimalScriptLoweringTest`, test `random compiles reproducible generators ranges and stateful dispatch`,
compiles the resource `modules/common/compiler-k2/src/test/resources/kotlin/random/main.kt` twice and
checks deterministic artifact bytes. `testKotlinRandomVmConformance` executes independent known-answer
vectors/hashes, scripted rejection sources, signed/unsigned extreme ranges, floating endpoint correction,
byte mutation/identity/errors, factory independence, Default aliases, subclass dispatch and method references
at budgets 128 and 512. Its Rust `random.rs` harness also restores a mid-execution checkpoint for both budgets
and compares continued execution with the original computer.

`DiagnosticQueryTest`, test `random generators and extensions resolve with source and metadata inputs`,
checks IDE diagnostics with and without attached platform sources. `PlatformMetadataCompilerTest`, test
`abstract platform methods and accessors retain linkable dispatch slots`, protects abstract library members.

This additive library version changes module content identities and dependent generated metadata. Recompile
dependent platform modules/programs. Platform bundle/module formats, worker protocols, Runtime ABI and native
transport/persistence schemas are unchanged; random code uses existing arithmetic, managed classes and math
instructions. [ABI reference]({{ '/ARCHITECTURE/abi/' | relative_url }}) owns those boundaries.
