---
layout: default
title: Types and numeric semantics
section: developers
permalink: /KOTLIN-SUPPORT/primitives/
---

# Types and numeric semantics

[← Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})

* On this page
{:toc}

This page describes the current checkout, including unreleased work. [Status and evidence policy]({{ '/KOTLIN-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry.

## `Int`, `Long`, `Float`, `Double`, `Boolean`, and `Char` scalar values

**Status:** Supported.

These source types lower to distinct verified VM scalar types with Kotlin-compatible control and comparison
behavior. Direct `Char.compareTo` returns the UTF-16 code-unit difference. Direct `Boolean.compareTo` returns
`-1`, `0`, or `1` with `false < true`; Boolean ordering operators follow the same order.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `bounded when forms compile for admitted scalar types` and `primitive char array lowers
deterministically for exact utf16 materialization`, plus `Long arithmetic conversions comparisons and text
lower for vm conformance` and `Float arithmetic conversions comparisons and text lower for vm conformance`,
and `Char and Boolean compareTo preserve scalar ordering for vm conformance` executed by
`testKotlinScalarCompareVmConformance`; also
[`tests.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/tests.rs), test
`scalar_vectors_match_kotlin_jvm_semantics`.

## Nullable signatures and nominal boxes for all twelve primitive types

**Status:** Supported.

Signed and unsigned numeric primitives, `Boolean?` and `Char?` preserve their source type when passed through
`Any?` and cast back. The compiler uses one primitive descriptor for register, box and array identity.

**Evidence:**
[`GuestInlineIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2-engine/src/test/kotlin/ru/lazyhat/compukters/compiler/k2/engine/GuestInlineIntegrationTest.kt),
tests `primitive nullable signatures preserve nominal boxes for every scalar register kind` and `all primitive
scalar types and nullable signatures compile through the canonical platform` in `MinimalScriptLoweringTest`.

**Related work:** [#700](https://github.com/CertifiedBadIdeas/Compukters/issues/700).

## `Unit` and `Nothing`

**Status:** Partial.

`Unit` function results and non-returning intrinsics and the explicit [exception forms](nullability.md) are
admitted. A Unit value can pass through `Any` as a managed singleton and convert to `kotlin.Unit`; broader
Nothing inference remains outside this support claim.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `ordinary zero argument Unit main lowers deterministically` and `typed process v2 facade lowers without
public capability masks or suspend calls`.

**Related work:** not scheduled

## `Byte` and `Short` scalar values

**Status:** Supported.

Literals, signed widening arithmetic and comparisons, conversions, unary plus/minus and increment/decrement
use normalized 8-bit and 16-bit values. `kotlin.experimental.and/or/xor/inv` preserve the signed source width.
Overflow wraps to the source width. Nominal boxes retain the distinction from `Int` through nullable
signatures, generic functions and `Any` casts.

**Evidence:** `all primitive operators preserve narrow signed unsigned and nominal semantics` in
[`GuestInlineIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2-engine/src/test/kotlin/ru/lazyhat/compukters/compiler/k2/engine/GuestInlineIntegrationTest.kt),
executed by `testKotlinPrimitivesVmConformance`, and `all primitive scalar types and nullable signatures
compile through the canonical platform` in `MinimalScriptLoweringTest`.

**Related work:** [#700](https://github.com/CertifiedBadIdeas/Compukters/issues/700).

## `Double` scalar values

**Status:** Supported.

Unboxed F64 literals, top-level scalar constants, arithmetic (`+`, `-`, `*`, `/`, `%`, unary minus), IEEE
equality and ordering, mixed operations with Int/Long/Float, and conversions in both directions are admitted.
Double operands promote mixed operations to F64. `compareTo` orders NaN above numbers and distinguishes signed
zero; primitive `==` keeps IEEE semantics. Companion limits, infinities and NaN are available. Boxing through
Any, nullable Double with Elvis, generic functions and supported collections/arrays preserve the value. Boxed
and generated data-class equality treat NaN as equal and distinguish zero signs without confusing hash
collisions with equality. Data-class Double comparison stays unboxed. Text conversion uses bounded
shortest-round-trip decimal notation, including signed zero, NaN, Infinity and three-digit exponents. Nullable
assertions are described in [nullability and exceptions]({{ "/KOTLIN-SUPPORT/nullability/" | relative_url }}).

**Evidence:** `Double arithmetic conversions comparisons boxing and text lower for vm conformance` executed by
`testKotlinDoubleVmConformance`, artifact test `Double string and hash forms require ABI 1 12 and F64
operands`, native `double_decimal_round_trips_finite_values_in_a_fixed_buffer`, and IDE tests `Guest Double
arithmetic conversions and console API resolve without errors` and `qualified completion exposes Double
conversion members on a parameter`. F64 text and hashing require Runtime ABI 1.12; numeric-only F64 reuses ABI
1.0. Canonical `kotlin:builtins` is 1.8.0 and `compukter:core` is 2.0.0.

**Related work:** [#688](https://github.com/CertifiedBadIdeas/Compukters/issues/688)

## Unsigned scalar types

**Status:** Supported.

`UByte`, `UShort`, `UInt` and `ULong` support literals, widening arithmetic, unsigned
comparisons/division/remainder, applicable bit operations, increment/decrement, conversions and unsigned
decimal text. Narrow values normalize to 8 or 16 bits; `UInt`/`ULong` retain all 32/64 bits. Unsigned values
have distinct nominal boxes. Unsigned operation forms and signedness-aware conversion require Runtime ABI
1.14.

**Evidence:** `all primitive operators preserve narrow signed unsigned and nominal semantics`, executed by
`testKotlinPrimitivesVmConformance`; canonical-platform scalar signature and IDE test `qualified completion
exposes narrow and unsigned primitive conversions`. Native evidence:
`unsigned_arithmetic_ordering_and_conversion_preserve_full_bit_ranges` and
`unsigned_division_by_zero_raises_the_managed_arithmetic_exception` in
[`tests.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/tests.rs).

**Related work:** [#700](https://github.com/CertifiedBadIdeas/Compukters/issues/700).

## Integer arithmetic

**Status:** Supported.

`Int` and `Long` support `+`, `-`, `*`, `/`, `%`, unary minus, `and`, `or`, `xor`, `inv`, `shl`, `shr`, and
`ushr` with VM wrapping and masked-shift semantics. Arithmetic and comparisons mix `Int` and `Long` using
Kotlin widening rules. Direct `compareTo` calls between `Int` and `Long` return `-1`, `0`, or `1` without
subtracting the operands. Unary plus, increment/decrement, and every Kotlin-defined narrow or unsigned
overload use the same canonical primitive model. Signed `Byte`/`Short` bit operations are available through
`kotlin.experimental` imports; unsigned shifts are logical.

**Evidence:**
[`KotlinProjectLowering`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2-engine/src/main/kotlin/ru/lazyhat/compukters/compiler/k2/engine/KotlinProjectLowering.kt)
and [`numeric.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/numeric.rs),
tests `integers_wrap_mask_shifts_and_handle_min_division` and `Long arithmetic conversions comparisons and
text lower for vm conformance`, paired with `testKotlinLongVmConformance`.

**Related work:** [#619](https://github.com/CertifiedBadIdeas/Compukters/issues/619)

## Floating-point arithmetic

**Status:** Supported.

Unboxed `Float` supports `+`, `-`, `*`, `/`, `%`, unary minus, equality, and ordered comparisons. Operations
can mix `Float` with `Int` or `Long`; integral operands widen to F32. Direct `compareTo` calls use total
ordering: `NaN` equals itself and sorts above all numbers, while `-0.0F` sorts below `0.0F`. `MIN_VALUE`,
`MAX_VALUE`, `POSITIVE_INFINITY`, `NEGATIVE_INFINITY`, and `NaN` are available, and text conversion preserves
JVM spellings including signed zero.

**Evidence:** `Float arithmetic conversions comparisons and text lower for vm conformance` and `Float variable
equality lowers from the K2 IEEE intrinsic`, paired with `testKotlinFloatVmConformance`.

**Related work:** [#620](https://github.com/CertifiedBadIdeas/Compukters/issues/620)

## Primitive numeric conversions

**Status:** Supported.

All signed and unsigned numeric families provide conversions to their applicable primitive destinations.
Narrow conversions retain the low 8/16 bits and signedness; widening preserves signed or unsigned magnitude.
Floating conversion to `Int`/`Long`/`UInt`/`ULong` saturates at that type's range and maps NaN to zero;
byte/short conversions then retain the low bits. `Char.code`, `Char.toInt`, and numeric `toChar` use UTF-16
code units.

**Evidence:** `testKotlinPrimitivesVmConformance`, `testKotlinLongVmConformance`,
`testKotlinFloatVmConformance`, `testKotlinDoubleVmConformance`, and native
`unsigned_arithmetic_ordering_and_conversion_preserve_full_bit_ranges`.
