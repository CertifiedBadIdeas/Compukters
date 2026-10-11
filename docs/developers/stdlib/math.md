---
layout: default
title: Portable mathematics
section: developers
permalink: /STDLIB-SUPPORT/math/
---

# Portable mathematics

[← Guest standard library support]({{ '/STDLIB-SUPPORT/' | relative_url }})

**Status:** Supported in the current checkout. Import `kotlin.math.*`; the API belongs to `stdlib:core` 1.13.0.
The [Guest API reference]({{ '/guest-api/' | relative_url }}) owns exact signatures. These functions operate
on Guest scalars and execute in the VM, including calls from compiled library bodies and callable references.
They require no peripheral, JVM classpath or host capability. Programs retaining floating math instructions
require Runtime ABI 1.16; rebuild platform and dependent library inputs to use the new module identity.

## API surface

| Group | Float and Double APIs |
| --- | --- |
| Trigonometry | `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2` |
| Hyperbolic functions | `sinh`, `cosh`, `tanh`, `asinh`, `acosh`, `atanh` |
| Roots and lengths | `sqrt`, `cbrt`, `hypot` |
| Exponentials and logarithms | `exp`, `expm1`, `ln`, `ln1p`, `log10`, `log2`, `log(x, base)` |
| Rounding | `ceil`, `floor`, `truncate`, `round`, receiver `roundToInt()` and `roundToLong()` |
| Selection and sign | `abs`, `min`, `max`, `sign`, receiver `absoluteValue` and `sign` properties |
| Powers and IEEE arithmetic | Receiver `pow(Float/Double)` and `pow(Int)`, `IEEErem`, `withSign(Float/Double)` and `withSign(Int)` |
| Adjacent representable values | Receiver `ulp` property, `nextUp()`, `nextDown()` and `nextTowards(to)` |

`PI` and `E` are Double constants. Int and Long provide `abs`, `min`, `max`, `absoluteValue` and
an Int-valued `sign` property. Unsigned scalars have no additional `kotlin.math` overloads.
Primitive conversion and comparison operators are documented under
[numeric semantics]({{ '/KOTLIN-SUPPORT/primitives/' | relative_url }}).

## Numerical behavior

Floating results preserve IEEE signed zero and use the VM's canonical NaN representation. Invalid domains
produce NaN; poles and overflow produce signed infinity where specified. `min`/`max` propagate NaN and
choose negative/positive zero respectively. `sign` retains signed zero. `hypot` avoids the intermediate
squared-value overflow of `sqrt(x*x + y*y)` and returns infinity when either operand is infinite, including
when the other is NaN. `IEEErem` is IEEE remainder, distinct from the `%` operator.

`round` selects the nearest integral floating value, with ties to even. `roundToInt` and `roundToLong`
select the nearest integer with ties toward positive infinity, saturate outside the integer range and
throw catchable `IllegalArgumentException` for NaN. Integer `abs(MIN_VALUE)` retains `MIN_VALUE` because
its positive magnitude does not fit. Float powers with an Int exponent retain the full exponent; they do
not round it through Float. `log(x, base)` returns NaN for a non-positive base or a base of one.

Transcendental operations use pinned pure-Rust `libm` 0.2.16 rather than the host system math library.
The numeric contract follows Kotlin's portable edge behavior; it does not promise bit-for-bit agreement
with every Kotlin/JVM math result. Instructions are metered with fixed operation costs, do not allocate
and do not suspend. Ordinary wrappers and callable references retain the usual Guest execution costs.
The [ABI reference]({{ '/ARCHITECTURE/abi/#runtime-abi-116' | relative_url }}) owns selectors and costs.

## Vectors and rectangles

`Vector2i`, `Vector3i` (`Int`) and `Vector2f`, `Vector3f` (`Float`) are immutable multi-field value classes.
They belong to `stdlib:core`, need no display/peripheral, and retain nominal equality, hashing and managed-box
behavior when stored in collections. `Position2i`, `Size2i`, `Position2f`, `Size2f` and their 3D counterparts
are typealiases to these vectors: they document intent without adding validation or a distinct identity.

Use constructors for all dimensions, or `10 xy 20` / `1f xy 2f` for 2D vectors. `80 by 24` is a size-oriented
synonym, returning the same vector type. `position sized size` constructs `Rect2i` or `Rect2f`.

Vectors support `+`, `-`, unary `-`, scalar multiplication (on either side), scalar division, infix `dot`
and `cross`, `length`, and `distanceTo`. The 2D cross product is a scalar determinant; the 3D result is a
vector. Integer arithmetic wraps like the scalar operators; division truncates. Integer lengths/distances
return Double and avoid overflowing an intermediate integer subtraction. `toFloat()` converts integer
components to the corresponding floating vector.

Floating vectors provide `normalized()` and `lerp(other, amount)`. Normalization returns zero for a zero
vector. Length/normalization/distance use Double intermediates and `hypot` to avoid avoidable Float
square/subtraction overflow and underflow; final Float results may overflow where the true magnitude
exceeds Float. Interpolation permits extrapolation outside 0..1. Non-finite vector arithmetic follows IEEE
behavior. Convert pixel coordinates explicitly with `roundToInt()`, `floorToInt()` or `ceilToInt()`:
all reject NaN and saturate beyond Int bounds; nearest rounding selects ties toward positive infinity.

Rectangles expose `position`, `size`, `left`, `top`, `right`, `bottom`, `isEmpty`, containment via `in`,
`intersects`, `intersection` and bounding `union`. Bounds are half-open: left/top included, right/bottom
excluded. Empty rectangles contain no points, are contained by every rectangle and do not enlarge a union.
A disjoint intersection has zero size. Negative sizes are rejected; integer endpoints and union sizes must
fit Int. Float rectangles require finite positions, sizes and endpoints; an extent collapsed by Float
precision is empty. Vectors themselves permit negative components and non-finite floating components.

This additive surface changes the stdlib module identity. Platform bundle/metadata/container/native wire
formats are unchanged; rebuild dependent libraries with the matching platform. Existing Runtime value-class
and math instructions execute the implementation.

## Evidence and maintenance

- `testKotlinGeometryVmConformance` compiles and executes `compiler-k2/src/test/resources/kotlin/geometry/main.kt`,
  covering arithmetic/aliases, normalization at zero and Float extremes, rounding, half-open bounds, invalid
  extents and collection storage. IDE `DiagnosticQueryTest` checks aliases and member completion with source
  and metadata-only inputs. `PlatformSignatureAliasesTest` checks signature expansion and ambiguous/cyclic rejection.
- `testKotlinMathVmConformance` compiles and executes
  `modules/common/compiler-k2/src/test/resources/kotlin/math/main.kt` against the Rust VM. It exercises
  every unary and binary operation in both widths, derived library bodies, integer limits, rounding
  boundaries, NaN/infinity/signed zero, callable references and operand evaluation order.
- `MinimalScriptLoweringTest`, `portable math compiles both widths library bodies and callable references`,
  verifies the retained selector surface and Runtime ABI 1.16.
- `MathInstructionsTest` covers artifact round trips, metered costs, ABI gates, register widths and initialization;
  Rust decode/verification tests reject malformed frames and false ABI claims.
- `host/compukter-vm/src/execution/math.rs` owns numerical implementations and their precision/edge tests.
- IDE `CompletionQueryTest` and `DiagnosticQueryTest` cover typed math overloads, extensions and callable
  references with both attached sources and metadata-only platform inputs.

Source owners: `guest-platform/src/platform/libraries/stdlib-core/kotlin/math`,
`CanonicalTrustedIntrinsics`, `KotlinProjectLowering`, artifact `MathOperation`, and VM `execution/math.rs`.

Related work: [#708](https://github.com/CertifiedBadIdeas/Compukters/issues/708).
