---
layout: default
title: Portable mathematics
section: developers
permalink: /STDLIB-SUPPORT/math/
---

# Portable mathematics

[← Guest standard library support]({{ '/STDLIB-SUPPORT/' | relative_url }})

**Status:** Supported in the current checkout. Import `kotlin.math.*`; the API belongs to `stdlib:core` 1.16.0.
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

`Vector2i`, `Vector3i` (`Int`), `Vector2f`, `Vector3f`, `Vector4f` (`Float`) and
`Vector2d`, `Vector3d`, `Vector4d` (`Double`, stdlib:core 1.19.0) are immutable multi-field value classes.
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

Double vectors retain Double components through arithmetic, dot/cross products and interpolation.
`Vector2d`/`Vector3d` provide the same explicit integer rounding operations as their Float counterparts;
Int and Float vectors provide `toDouble()`, and Double vectors provide explicit `toFloat()` narrowing.
Double normalization scales finite components before computing length, so even vectors whose true
magnitude exceeds Double can produce a unit direction; zero stays zero and non-finite inputs follow IEEE
division behavior. `Vector4d` also provides `distanceTo`, `lerp`, `xyz`, `perspectiveDivide()` and
`isInsideClipVolume` with the same finite-w and clip-bound rules as Vector4f.

`Quaternionf` and `Quaterniond` are distinct immutable value classes with x/y/z/w Float and Double
components respectively. Their representation matches the corresponding Vector4f/Vector4d, but nominal
equality does not equate a quaternion with a vector. Explicit `toQuaternionf()` / `toQuaterniond()`
and `toVector4f()` / `toVector4d()` preserve component values without normalization
or rotation validation, including signed zero and non-finite values. This component type imposes no
unit-quaternion precondition on construction. `Quaternionf.toDouble()` widens components;
`Quaterniond.toFloat()` explicitly narrows them.

Rectangles expose `position`, `size`, `left`, `top`, `right`, `bottom`, `isEmpty`, containment via `in`,
`intersects`, `intersection` and bounding `union`. Bounds are half-open: left/top included, right/bottom
excluded. Empty rectangles contain no points, are contained by every rectangle and do not enlarge a union.
A disjoint intersection has zero size. Negative sizes are rejected; integer endpoints and union sizes must
fit Int. Float rectangles require finite positions, sizes and endpoints; an extent collapsed by Float
precision is empty. Vectors themselves permit negative components and non-finite floating components.

This additive surface changes the stdlib module identity. Platform bundle/metadata/container/native wire
formats are unchanged; rebuild dependent libraries with the matching platform. Existing Runtime value-class
and math instructions execute the implementation.

## Matrices and homogeneous coordinates

`Matrix3f`, `Matrix3d` and `Matrix4d` extend the same immutable row-major API. Three-dimensional matrices
have nine row/column-named components (`m00` through `m22`), indexes 0..2, matrix/matrix and matrix/Vector3
products, transposition, identity, uniform/vector scale and right-handed X/Y/Z rotations.
A 3x3 matrix is a linear transform; it does not add homogeneous point/direction coordinates.
Matrix4d mirrors Matrix4f with Double components, Vector3d/Vector4d and Double factory arguments.
Projection factories reject non-finite coefficients; scaled ratios avoid intermediate overflow in
orthographic spans and perspective depth coefficients at representable Double extremes.

The new shape kernels require Runtime ABI 1.19 and retain scalar Float/Double multiplication followed
by left-associated addition without FMA. Their exact scalar widths and component counts are verified;
transposition preserves bits, and destination aliases are safe. Factories remain ordinary Guest code.


`Matrix4f` and `Vector4f` are immutable Float value classes in `kotlin.math`, with nominal equality,
hashing and managed collection storage. Construct a matrix from sixteen components in row-major order;
`m00` through `m33` name row and column, and `matrix[row, column]` reads indexes 0..3.
An invalid index throws catchable `IndexOutOfBoundsException`. Use `Matrix4f.identity()` for identity.

Matrix/matrix and matrix/vector products, point/direction transforms and transposition execute as
allocation-free VM kernels (Runtime ABI 1.17) with the same immutable API. Dot products preserve scalar
Float rounding and left-to-right addition, without fused multiply-add. Transposition preserves component
bits. Factories remain compiled library code; their trigonometric functions use existing math intrinsics.
Rebuild dependent libraries and resolve project dependencies after upgrading the stdlib module.


Matrices multiply column vectors. `a * b * point` applies `b` first, then `a`; matrix multiplication is
not commutative. `transposed()` exchanges rows and columns. Factories provide `translation(Vector3f)`,
`scale(Vector3f)` or uniform `scale(Float)`, and `rotationX/Y/Z(angleRadians)`. Rotations use the right-hand
rule and angles in radians. Matrix components and ordinary arithmetic permit IEEE non-finite values.

`matrix * Vector3f` and `transformPoint(Vector3f)` lift a point with `w = 1` and return `Vector4f`.
`transformDirection(Vector3f)` uses `w = 0`, so affine translation does not affect a direction.
`matrix * Vector4f` uses the supplied `w`. `Vector4f` provides arithmetic, scalar multiplication on either
side, division, infix `dot`, `length`, zero-preserving `normalized()` and `xyz` without division.

Projection factories look down negative Z, with normalized depth from -1 (near) to +1 (far):

- `perspective(verticalFieldOfViewRadians, aspectRatio, near, far)` requires finite inputs,
  `0 < verticalFieldOfViewRadians < PI`, `aspectRatio > 0` and `0 < near < far`.
- `orthographic(left, right, bottom, top, near, far)` requires finite ordered bounds:
  `left < right`, `bottom < top`, `near < far`. Negative near distances are allowed.

Factories use Double intermediates and reject invalid parameters or coefficients overflowing Float with
catchable `IllegalArgumentException`. They do not change display resolution or select a camera automatically.

The projected result retains `w`. `isInsideClipVolume` requires finite positive `w` and includes the bounds
`-w <= x,y,z <= w`; NaN, infinite coordinates and points behind the camera are outside.
`perspectiveDivide()` returns `Vector3f(x / w, y / w, z / w)`, rejects zero or non-finite `w`, and performs
no clipping. A negative finite `w` is permitted by division; test visibility separately. Floating roundoff
may move a computed boundary point slightly outside a clip plane. Rendering triangles or lines that cross
clip planes requires clipping those primitives, rather than simply discarding their outside vertices.

```kotlin
import kotlin.math.*

val projection = Matrix4f.perspective((PI / 3.0).toFloat(), 1f, 0.1f, 100f)
val model = Matrix4f.translation(Vector3f(0f, 0f, -5f)) * Matrix4f.rotationY(angle)
val clip = projection * model * Vector3f(1f, 1f, 1f)
if (clip.isInsideClipVolume) {
    val ndc = clip.perspectiveDivide()
    val pixel = Vector2f(
        (ndc.x + 1f) * 0.5f * (screen.size.x - 1).toFloat(),
        (1f - ndc.y) * 0.5f * (screen.size.y - 1).toFloat(),
    ).roundToInt()
    screen.pixel(pixel, 0xFFFFFF)
}
```

Here `angle` is in radians and `screen` is a configured `GraphicalDisplay`. Normalized Y points upward;
display Y points downward. Check positive display dimensions before mapping coordinates.

## Evidence and maintenance

- `testKotlinGeometryVmConformance` compiles and executes `compiler-k2/src/test/resources/kotlin/geometry/main.kt`,
  covering arithmetic/aliases, normalization at zero and Float extremes, rounding, half-open bounds, invalid
  extents, dense and noncommuting matrix products, axis rotations, homogeneous point/direction lifting,
  projection bounds, invalid parameters, explicit division, callable references and collection storage.
  IDE `DiagnosticQueryTest` checks aliases, matrices and member completion with source and metadata-only inputs. `PlatformSignatureAliasesTest` checks signature expansion and ambiguous/cyclic rejection.
- `testKotlinMatrixShapesVmConformance` compiles and executes `compiler-k2/src/test/resources/kotlin/matrix-shapes/main.kt`,
  covering every new selector, dense products against scalar loops, transpose, rotations, bounds,
  Double projections at extreme magnitudes, nominal boxes and callable references. Native matrix tests
  cover all valid aliases, fixed costs, no managed allocations, mixed-width/old-ABI rejection and Double
  rounding, signed zero, NaN and non-FMA behavior.
- `testKotlinDoubleGeometryVmConformance` compiles and executes `compiler-k2/src/test/resources/kotlin/double-geometry/main.kt`,
  covering Double precision, normalization at zero/subnormal/maximum magnitudes, rounding, non-finite inputs,
  vector conversions, boxed nominal identity and component-preserving Quaterniond conversions.
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

Related work: [#708](https://github.com/CertifiedBadIdeas/Compukters/issues/708),
[#740](https://github.com/CertifiedBadIdeas/Compukters/issues/740).
