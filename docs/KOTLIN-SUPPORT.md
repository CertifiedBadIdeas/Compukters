---
layout: default
title: Guest Kotlin support
---

# Guest Kotlin support

Compukters accepts Kotlin source through a pinned K2 frontend, then lowers it
to Compukter bytecode for the managed Rust VM. This is **Guest Kotlin**, not
Kotlin/JVM: K2 accepting source does not imply Java interoperability, JVM
library compatibility, or executable support in Compukters.

The compiler process uses Kotlin compiler libraries internally, but those host
dependencies are not visible to Guest source. Guest name resolution contains
only the native declarations published by the selected Compukters platform
modules. Their metadata, ordinary precompiled Kotlin bodies, and trusted
external bindings form one versioned platform contract.

This matrix describes the repository revision that contains it.

## Status legend

- [x] **Supported** — the narrowly stated behavior has execution-level
  conformance evidence, or a focused tooling test for an IDE-only claim.
- [ ] **Partial** — a useful subset works, but the stated boundary remains.
- [ ] **Unsupported** — the backend deliberately rejects the construct or has
  no implementation for it.
- **Not planned** — an intentional platform boundary, not queued work.

Every checked item names its evidence. Unchecked work links an exact tracking
issue when scheduled; otherwise it says `Tracking: not scheduled`. Compiler
acceptance alone is not execution evidence: a VM operation and a K2 construct
must meet through conformance coverage before the construct is marked
supported.

## Entry points and projects

- [x] **`main(args: Array<String>)` argument contract** — the argument-bearing
  entry point receives one owned array whose strings preserve their
  exact UTF-16 code units. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `string array entry lowers deterministically for vm argv conformance`,
  and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_string_array_entry_executes_exact_utf16_arguments`.

- [x] **Two legal `main` forms** — `fun main()` and
  `fun main(args: Array<String>)` lower with explicit entry tags. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `both legal main forms lower deterministically with an explicit entry contract`.

- [x] **Invalid entry points are rejected** — duplicate entries, missing
  entries, unsupported parameters, nullable argument arrays, and non-`Unit`
  results produce no artifact. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `entry policy rejects duplicate and invalid main functions` and
  `entry policy rejects a project without main`.

- [ ] **Multi-file projects — Partial** — cross-file top-level calls share one
  K2 session and lower deterministically, while the in-computer `kotlinc`
  command still accepts exactly one source file. Evidence:
  [`K2CompilerAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapterTest.kt),
  test `cross-file reference participates in one K2 session before bounded lowering`,
  and
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `multi-file terminal program lowers through trusted symbols` and
  `kotlinc command line rejects ambiguous or unsupported arguments`.
  Tracking: not scheduled

- [ ] **Project manifests and modules — Partial** — `compukter.toml` selects a
  portable native platform module graph by identity, while `compukter.lock`
  records exact resolved versions and hashes for the IDE, analyzer, and compiler. Compiler output is still one application
  artifact rather than an independently distributable Kotlin module ecosystem.
  Tracking: not scheduled

## Types and numeric semantics

- [x] **`Int`, `Long`, `Float`, `Boolean`, and `Char` scalar values** — these source types lower
  to distinct verified VM scalar types with Kotlin-compatible control and
  comparison behavior. Direct `Char.compareTo` returns the UTF-16 code-unit
  difference. Direct `Boolean.compareTo` returns `-1`, `0`, or `1` with
  `false < true`; Boolean ordering operators follow the same order. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `bounded when forms compile for admitted scalar types` and
  `primitive char array lowers deterministically for exact utf16 materialization`, plus
  `Long arithmetic conversions comparisons and text lower for vm conformance` and
  `Float arithmetic conversions comparisons and text lower for vm conformance`,
  and `Char and Boolean compareTo preserve scalar ordering for vm conformance`
  executed by `testKotlinScalarCompareVmConformance`; also
  [`tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/tests.rs), test
  `scalar_vectors_match_kotlin_jvm_semantics`.

- [ ] **`Any` value equality — Partial** — non-null values held as `Any` support `==`, `!=`, and `equals`.
  Distinct boxed `Int` values compare by i32 value; strings compare by UTF-16 content. Guest classes honor explicit
  `equals` overrides; data classes compare supported scalar and non-null `String` primary-constructor properties.
  Other classes use default identity equality. Unsupported data-class property shapes receive a compiler diagnostic.
  An `Int` operand boxes at this boundary. Nullable `Any?`, hashing, and string conversion remain unavailable. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `list Int covariance to Any preserves the list and boxes reads`, executed by `testKotlinListAnyVmConformance`.
  Tracking: [#581](https://github.com/CertifiedBadIdeas/Compukters/issues/581)

- [ ] **`Unit` and `Nothing` — Partial** — `Unit` function results and
  non-returning trusted intrinsics are admitted, but general `Nothing`
  expressions such as arbitrary throws are not lowered. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `ordinary zero argument Unit main lowers deterministically`
  and `typed process v2 facade lowers without public capability masks or suspend calls`.
  Tracking: not scheduled

- [ ] **`Byte`, `Short`, and `Double` — Unsupported** — these numeric types have no
  Guest source representation. `Float` is supported separately as an unboxed F32
  scalar. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `unsupported collection unsigned and Double source produces a stable diagnostic and no artifact`.
  Tracking: not scheduled

- [ ] **Unsigned types — Unsupported** — `UByte`, `UShort`, `UInt`, and
  `ULong` have no Guest representation or standard operations; a `UInt`
  program is rejected as unsupported IR. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `unsupported collection and unsigned source produces a stable diagnostic and no artifact`.
  Tracking: not scheduled

- [ ] **Integer arithmetic — Partial** — `Int` and `Long` support `+`, `-`, `*`,
  `/`, `%`, unary minus, `and`, `or`, `xor`, `inv`, `shl`, `shr`, and `ushr`
  with VM wrapping and masked-shift semantics. Arithmetic and comparisons mix
  `Int` and `Long` using Kotlin widening rules. Direct `compareTo` calls between
  `Int` and `Long` return `-1`, `0`, or `1` without subtracting the operands.
  Other integer widths are not lowered from source.
  Evidence:
  [`KotlinProjectLowering`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2-engine/src/main/kotlin/ru/lazyhat/compukters/compiler/k2/engine/KotlinProjectLowering.kt)
  and [`numeric.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/numeric.rs), tests
  `integers_wrap_mask_shifts_and_handle_min_division` and
  `Long arithmetic conversions comparisons and text lower for vm conformance`,
  paired with `testKotlinLongVmConformance`.
  Tracking: [#619](https://github.com/CertifiedBadIdeas/Compukters/issues/619)

- [x] **Floating-point arithmetic** — unboxed `Float` supports `+`, `-`, `*`, `/`,
  `%`, unary minus, equality, and ordered comparisons. Operations can mix `Float`
  with `Int` or `Long`; integral operands widen to F32. Direct `compareTo`
  calls use total ordering: `NaN` equals itself and sorts above all numbers,
  while `-0.0F` sorts below `0.0F`. `MIN_VALUE`, `MAX_VALUE`,
  `POSITIVE_INFINITY`, `NEGATIVE_INFINITY`, and `NaN` are available, and text
  conversion preserves JVM spellings including signed zero. Evidence:
  `Float arithmetic conversions comparisons and text lower for vm conformance` and
  `Float variable equality lowers from the K2 IEEE intrinsic`, paired with
  `testKotlinFloatVmConformance`.
  Tracking: [#620](https://github.com/CertifiedBadIdeas/Compukters/issues/620)

- [ ] **Conversions — Partial** — `Int.toChar()`, `Int.toLong()`, `Long.toInt()`,
  `Int.toFloat()`, `Long.toFloat()`, `Float.toInt()`, and `Float.toLong()` are
  lowered. Other numeric conversions remain outside the
  source subset. Tracking: [#619](https://github.com/CertifiedBadIdeas/Compukters/issues/619),
  [#620](https://github.com/CertifiedBadIdeas/Compukters/issues/620)

## Expressions and control flow

- [x] **Scalar `when` with individual branches** — `Int`, `Char`, `Boolean`,
  and `String` subjects, plus subjectless boolean conditions, lower to bounded
  deterministic branches; matched and fallback paths execute in the VM.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `bounded when lowers deterministically for vm execution` and
  `bounded when forms compile for admitted scalar types`, and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_bounded_when_selects_matched_and_fallback_branches`.

- [ ] **Pattern-rich `when` — Unsupported** — range membership, comma-joined
  branch conditions, and arbitrary `Any` type patterns do not publish an
  artifact. Type branches over the admitted sealed class subset are handled
  separately under the object model. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `unsupported when patterns produce no artifact`.
  Tracking: not scheduled

- [ ] **`if`, blocks, mutable locals, and `while` — Partial** — these forms
  compile and are used by the checked-in shell, including nested loops and
  reassignment. `break` and `continue` targeting the current innermost `while`
  lower directly; jumps to an outer loop are rejected. There is no
  source-level conformance suite covering every expression/result shape or
  ordinary `do-while`. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `shell language subset lowers control flow scalars strings and raw terminal calls`,
  `checked in shell compiles deterministically`, and
  `while loop jumps lower locally and reject outer targets`.
  Tracking: not scheduled

- [x] **Allocation-free `Int` `for` loops** — `start..endInclusive`,
  `start until endExclusive`, `start..<endExclusive`, `start downTo endInclusive`,
  and one positive `step` on those progressions evaluate and snapshot their bounds
  and step once, then execute as scalar frame slots with no range, progression,
  or iterator allocation. Invalid dynamic steps throw a Guest argument error.
  Empty, reversed, singleton, negative, `Int.MIN_VALUE`, and `Int.MAX_VALUE`
  boundaries preserve Kotlin behavior. `break` and `continue`
  targeting the current innermost `for` are supported, including nested loops.
  Every repeated path crosses an existing loop-header quota safepoint.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `inclusive Int for loops lower without range or iterator allocation`,
  `exclusive Int for loops lower without range or iterator allocation`,
  `Int for loop supplies its generated increment constant`, and
  `allocation free Int loops lower deterministically for vm execution` (including
  descending, stepped, edge, and invalid-step execution), plus
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_int_loops_execute_across_quota_slices_without_host_io`.

- [ ] **Other ranges, progressions, and iterable `for` loops — Partial** —
  read-only `List<T>` iteration is supported for the element types below. Stored or materialized progressions,
  chained `step` calls, general arrays, strings, other collections, custom iterators, ordinary
  source `do-while`, and labeled jumps to an outer loop publish no artifact.
  Non-loop `IntRange`, `IntProgression`, `downTo`, `step`, `until`, and `rangeUntil` calls are declaration-only and
  are not a general executable range API. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `unsupported loop forms publish no artifact`.
  Tracking: not scheduled

- [ ] **Destructuring and delegated expressions — Unsupported** — component
  calls, delegated storage, and their generated source shapes are not admitted
  as a supported contract. Tracking: not scheduled

## Functions and calls

- [ ] **Top-level and member calls — Partial** — direct top-level calls,
  immutable property getters, and supported member operations lower by exact
  symbol. Arbitrary library or virtual dispatch remains outside the subset.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `multi-file terminal program lowers through trusted symbols` and
  `same-named guest function remains an ordinary project call`, plus
  `same-named guest calls preserve their resolved targets for vm conformance`
  paired with `testKotlinNamedCallsVmConformance`.
  Tracking: not scheduled

- [x] **Transparent blocking across project calls** — an ordinary Guest
  function may call another ordinary function and resume across an
  asynchronous host capability without a coroutine calling convention. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `ordinary project call resumes transparently across host blocking`, and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_ordinary_project_call_resumes_across_async_capability`.

- [ ] **Default arguments — Partial** — platform APIs may publish constant
  `Int` or qualified enum-entry defaults, which direct platform calls lower
  without JVM mask dispatchers. Omitted `Array<String>` parameters in project
  functions are supported only for direct `emptyArray()` or direct `arrayOf`
  call defaults. Primary constructors evaluate supported Guest default
  expressions at ordinary call sites after explicit arguments; general project
  function defaults remain limited to the documented forms.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `sound beep lowers deterministically to a blocking Boolean capability operation`,
  `string arrays support copyOfRange and supported default arguments`, and
  `primary constructor defaults preserve argument order and earlier parameters`
  paired with `testKotlinConstructorDefaultsVmConformance`;
  [`ParameterInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ParameterInfoQueryTest.kt),
  test `parameter info exposes a platform Int default`.
  Tracking: not scheduled

- [ ] **Extension functions and overloads — Partial** — K2 resolves project
  extensions and overloads by symbol, and same-named project functions do not
  impersonate trusted intrinsics. Execution coverage is not comprehensive.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `same-named char array helper remains an ordinary project call` and
  `same-named guest function remains an ordinary project call`, plus
  `same-named guest calls preserve their resolved targets for vm conformance`
  paired with `testKotlinNamedCallsVmConformance`.
  Tracking: not scheduled

- [ ] **Named and vararg arguments — Partial** — ordinary K2 argument binding
  works only when the resulting direct call stays in the admitted signature
  subset; direct `arrayOf` and `listOf` varargs are specially lowered, while spread arrays
  are rejected. Tracking: not scheduled

- [ ] **Generic functions, classes, and interfaces — Partial** — top-level `fun <T>` calls
  with inferred or explicit concrete arguments and final invariant `class Cell<T>`
  style declarations are specialized at compile time. Primary-constructor
  fields and direct methods use concrete scalar or reference types; a non-null
  `Int` remains unboxed in both calls and fields. Source-only platform library
  modules containing generic functions or final generic classes are specialized
  in a consumer; generic class methods and constructor fields retain concrete
  `Int` and reference layouts. Function-valued parameters of source-only
  generic functions use the specialized element type, as exercised by the
  `Iterable<T>` predicate helpers.
  Concrete generic interfaces and covariant result interfaces support the read-only `List<T>` contract.
  Contravariance, reified parameters, generic
  value classes, generic methods declaring their own type parameters, nullable
  primitive arguments, and broad `Any` boxing bridges remain outside the subset; the `List<Int>` read bridge below
  admits one bounded `Int`-to-`Any` boundary. Expansion is
  bounded to 256 function and 256 class variants per compilation. Binary
  generic library templates and runtime instantiation are absent. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `generic identity specializes primitive and reference calls`,
  `generic cell specializes field layout and preserves aliases`,
  `generic interface dispatch retains concrete Int and reference types`,
  `list Int covariance to Any preserves the list and boxes reads`, and
  `unsupported generic forms report source diagnostics without artifacts`;
  [`K2CompilerAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapterTest.kt),
  test `source library generic functions and classes specialize in consumer`; VM
  conformance tasks `testKotlinGenericFunctionsVmConformance`,
  `testKotlinGenericCellVmConformance`, and
  `testKotlinGenericLibraryVmConformance`. Tracking: #652, #656

- [ ] **Lambdas, local functions, and function references — Partial** —
  Non-null function values using supported Guest parameter and result types
  are admitted in project function signatures and locals: values may be passed,
  returned, stored, and invoked. The compiler generates an interface for each
  distinct concrete signature without a fixed arity cut-off; artifact and VM
  limits still apply. A function value can also be passed as an argument to
  another function value. Nested lambdas may capture values through multiple
  lexical scopes and remain callable after the enclosing invocation returns.
  Unbound references to non-suspending Guest top-level functions can likewise
  be stored, passed, returned, and invoked, including with an inferred
  `KFunction` type. Only invocation is supported; reflection operations are not.
  Bound references to ordinary Guest instance methods also work: the receiver
  expression is evaluated once, retained by the function value, and dispatched
  virtually or through its interface when invoked. Unbound instance-method
  references (`Type::method`) accept the receiver as their first argument and
  use the same runtime dispatch.
  References to supported Guest primary constructors (`::Type`) can be stored,
  passed, returned, and invoked, including zero- and multi-argument constructors.
  An expected function type may omit trailing constructor parameters with
  supported defaults. Each invocation evaluates those defaults and constructs a
  fresh instance through the existing class layout.
  Each lambda evaluation creates an ordinary managed closure object; lambdas
  may capture immutable scalar and reference values, and reference captures
  preserve the original referent and aliasing. A captured local `var` uses one
  ordinary managed typed cell per dynamic variable instance, shared by the
  enclosing code and every sibling closure; primitive payloads remain unboxed.
  `Tasks.launch` accepts direct, stored, and returned `() -> Unit` values. A
  direct top-level `Tasks.launch(::worker)` remains a static spawn without a
  closure allocation. Types unsupported elsewhere in Guest Kotlin, local,
  property references, constructors outside the admitted Guest class subset,
  other adapted references, and variance
  conversions between different function signatures remain unsupported.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `supported function values lower to managed closures and shared capture cells`
  (including nested closures and shared mutable captures),
  `task launch accepts direct stored and returned Unit lambdas`,
  `unsupported closure shapes produce stable diagnostics`,
  `direct top level ordinary task lowers to spawn and join`,
  `task launch rejects unsupported local and bound references`,
  `function value variance conversion is rejected before artifact publication`,
  `supported constructor references lower to ordinary function values`,
  `default adapted constructor references lower to managed function values`, and
  `unsupported constructor reference is rejected before artifact publication`;
  root tasks `testKotlinFunctionValuesVmConformance` and
  `testKotlinAdaptedConstructorsVmConformance`.
  Tracking: [#627](https://github.com/CertifiedBadIdeas/Compukters/issues/627),
  [#631](https://github.com/CertifiedBadIdeas/Compukters/issues/631),
  [#628](https://github.com/CertifiedBadIdeas/Compukters/issues/628),
  [#632](https://github.com/CertifiedBadIdeas/Compukters/issues/632),
  [#633](https://github.com/CertifiedBadIdeas/Compukters/issues/633),
  [#634](https://github.com/CertifiedBadIdeas/Compukters/issues/634),
  [#635](https://github.com/CertifiedBadIdeas/Compukters/issues/635),
  [#636](https://github.com/CertifiedBadIdeas/Compukters/issues/636),
  [#644](https://github.com/CertifiedBadIdeas/Compukters/issues/644)

- [ ] **Recursion — Partial** — direct calls and bounded VM call depth can
  represent recursion, but no Kotlin-to-VM recursive source conformance test
  defines it as a supported language contract. Tracking: not scheduled

- [ ] **Top-level state — Partial** — immutable top-level properties support
  direct `Int`, `Long`, `Float`, `Boolean`, `Char`, and `String` literals plus direct
  `IntChannel(capacity)` construction. They lower to lazily initialized static
  VM storage. Top-level `var`, custom or delegated accessors, initializer
  dependencies, and arbitrary object construction remain unsupported. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `top level IntChannel lowers to VM owned bounded handoff` and
  `IntChannel construction rejects unsupported ownership and capacity`.
  Tracking: [#614](https://github.com/CertifiedBadIdeas/Compukters/issues/614)

## Classes and object model

- [ ] **Instance methods and dynamic dispatch — Partial** — supported Guest
  classes may declare ordinary non-suspending methods, override class methods,
  and implement abstract interface methods. Calls through class and interface
  references select the runtime implementation. Generic or suspending methods,
  member extensions remain unsupported.
  Interface methods may have supported non-suspending bodies; an inherited
  default uses the most specific interface declaration unless a class overrides
  it. An override may call a concrete interface body with `super<Interface>`,
  including one inherited through that interface; the call bypasses dynamic
  dispatch to the override. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `guest instance methods lower with deterministic owners flags and method ranges`
  and `guest instance methods reject unsupported callable shapes`, paired with
  the `testKotlinDispatchVmConformance` Kotlin-to-VM execution gate, and test
  `interface defaults lower to methods and computed accessors` paired with
  `testKotlinInterfaceDefaultsVmConformance`, and test
  `qualified interface super calls use direct default bodies` paired with
  `testKotlinInterfaceSuperVmConformance`.
  Tracking: [#626](https://github.com/CertifiedBadIdeas/Compukters/issues/626)
  [#641](https://github.com/CertifiedBadIdeas/Compukters/issues/641), and
  [#642](https://github.com/CertifiedBadIdeas/Compukters/issues/642).

- [ ] **Guest class initialization — Partial** — supported primary constructors
  initialize backed `val` and `var` properties, class-body properties, and `init`
  blocks in source order after superclass initialization on the same managed
  object. Plain constructor parameters may be used without becoming fields.
  Mutable properties with default accessors can be read and assigned through
  aliases and instance methods; each object retains its own field state.
  Primary-constructor defaults can refer to earlier parameters and use
  supported Guest expressions. Explicit arguments evaluate in call-site order,
  followed by omitted defaults in parameter order; the constructor receives
  the complete values before its property and `init` work. Constructor
  references retain their declared full arity unless an expected function type
  selects supported trailing defaults. Secondary constructors remain unsupported. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `guest object subset lowers sealed results data values enum identity and type branches`,
  paired with [`heap_tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/heap_tests.rs),
  tests `heap_instructions_round_trip_reference_fields` and
  `heap_instructions_use_inherited_fields_and_interface_closure`, plus
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `mutable constructor properties lower to instance field writes` and
  `immutable constructor property assignment remains rejected`, paired with
  root task `testKotlinMutableFieldsVmConformance`, and test
  `class body properties and init blocks lower in construction order` paired
  with `testKotlinClassInitializationVmConformance`, and test
  `primary constructor defaults preserve argument order and earlier parameters`
  paired with `testKotlinConstructorDefaultsVmConformance`, and test
  `default adapted constructor references lower to managed function values`
  paired with `testKotlinAdaptedConstructorsVmConformance`.
  Tracking: [#637](https://github.com/CertifiedBadIdeas/Compukters/issues/637),
  [#638](https://github.com/CertifiedBadIdeas/Compukters/issues/638),
  [#643](https://github.com/CertifiedBadIdeas/Compukters/issues/643), and
  [#644](https://github.com/CertifiedBadIdeas/Compukters/issues/644).

- [ ] **Class property accessors — Partial** — class `val` and `var` properties
  support computed getters and source-defined non-suspending getters/setters.
  Accessors use ordinary instance-method dispatch, including overrides called
  through a base-class reference; `field` reads and writes use the property's
  managed backing field when present. Non-overriding final default accessors
  retain direct field access. Abstract `val` and `var` declarations in classes
  and interfaces contribute accessor methods without allocating fields; calls
  through either base type select a concrete backed or computed implementation.
  Interface properties can also define computed getter and setter bodies;
  interface backing fields, top-level custom accessors, delegated properties,
  and unsupported Guest types remain outside this subset.
  Evidence: [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `computed and custom class accessors lower with backing fields and override dispatch`,
  paired with root task `testKotlinPropertyAccessorsVmConformance`, and test
  `abstract class and interface properties lower to dispatched accessors without fields`
  paired with `testKotlinAbstractPropertiesVmConformance`, and test
  `interface defaults lower to methods and computed accessors` paired with
  `testKotlinInterfaceDefaultsVmConformance`. Qualified `super<Interface>`
  getter and setter calls are covered by
  `qualified interface super calls use direct default bodies` and
  `testKotlinInterfaceSuperVmConformance`.
  Tracking: [#639](https://github.com/CertifiedBadIdeas/Compukters/issues/639),
  [#640](https://github.com/CertifiedBadIdeas/Compukters/issues/640), and
  [#641](https://github.com/CertifiedBadIdeas/Compukters/issues/641), and
  [#642](https://github.com/CertifiedBadIdeas/Compukters/issues/642).

- [ ] **Sealed interfaces, data classes, and stateless enums — Partial** — the
  admitted fixture lowers sealed result types, immutable data values, enum
  identity, exhaustive type branches, and smart-cast property reads, then
  executes those branches in the pinned VM. This does not imply support for
  all generated data or enum methods. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `guest object subset lowers sealed results data values enum identity and type branches`,
  paired with root task `testKotlinObjectModelVmConformance`.
  Tracking: not scheduled

- [ ] **Secondary constructors and stateful enums — Unsupported** — these shapes are rejected before artifact
  publication. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `guest object subset rejects generic secondary uninitialized stateful and explicit cast shapes`.
  Tracking: not scheduled

- [ ] **User `object` declarations — Unsupported** — the source class layout
  admits classes, interfaces, and enums, but not singleton object declarations.
  Trusted Guest API objects are compiler-provided facades, not evidence for
  user-defined objects. Tracking: not scheduled

- [ ] **Type tests and casts — Partial** — `is` checks and compiler-generated
  smart casts over admitted references lower to VM type checks and checked
  casts. Boxed `Int` values read as `Any` also support `is Int` and an explicit checked `as Int`; other explicit
  source casts remain rejected. Reference `===` compares identity after a checked common-reference conversion.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `guest object subset lowers sealed results data values enum identity and type branches`
  and `guest object subset rejects generic secondary uninitialized stateful and explicit cast shapes`,
  paired with [`heap_tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/heap_tests.rs),
  test `heap_instructions_checked_cast_handles_nullability_and_incompatibility`.
  Tracking: not scheduled

- [ ] **Primitive `value class` declarations — Partial** — a value class with
  exactly one `Int`, `Boolean`, or `Char` property erases to that scalar for
  constructors, properties, methods, operators, constants, and trusted ABI
  calls. `@JvmInline` is deliberately rejected because it belongs to the JVM
  platform, not Guest Kotlin. Nullable, generic, reference-backed, boxed, and
  multi-property forms are rejected. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `typed redstone side API lowers deterministically to scalar capability operations`,
  and
  [`CanonicalPlatformSourceTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/guest-platform/src/test/kotlin/ru/lazyhat/compukters/platform/source/CanonicalPlatformSourceTest.kt),
  which rejects JVM-only value-class syntax from native platform sources.
  Tracking: not scheduled

## Nullability and exceptions

- [x] **Nullable user references** — `String?` and supported Guest class references
  can be local values, top-level immutable properties, class fields, function
  parameters, and results. `null` can initialize these values or be passed and
  returned in a typed reference context. Nullable arrays, function values,
  platform capability values, and primitive values such as `Int?` remain outside
  this subset. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt), tests
  `nullable references lower null comparisons Elvis and reference safe calls` and
  `unsupported nullable forms do not publish artifacts`,
  paired with [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs), scenario `nullable-references`.
  Tracking: [#654](https://github.com/CertifiedBadIdeas/Compukters/issues/654)

- [ ] **Safe calls and Elvis — Partial** — nullable references can be compared
  with `null`, selected with `?:`, and accessed with `?.` when the result is a
  supported reference type. Both operators evaluate the left side once and
  skip the unused branch. Safe calls producing nullable primitive values such
  as `text?.length` and non-null assertions (`!!`) remain unsupported. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt), tests
  `nullable references lower null comparisons Elvis and reference safe calls`
  and `unsupported nullable forms do not publish artifacts`,
  paired with [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs), scenario `nullable-references`.
  Tracking: [#654](https://github.com/CertifiedBadIdeas/Compukters/issues/654)

- [ ] **`throw`, `try`, `catch`, and `finally` — Unsupported** — the artifact
  and VM have verified exception tables, but the K2 backend does not lower
  `IrThrow` or `IrTry` from Guest source. Tracking: not scheduled

- [ ] **Standard exception classes — Unsupported** — Kotlin/JVM exception
  classes are not a Guest standard-library surface. VM traps and bounded host
  failures remain typed runtime outcomes rather than catchable Kotlin
  exceptions. Tracking: not scheduled

- [x] **Compiler diagnostic source coordinates** — syntax and type diagnostics
  preserve virtual paths and UTF-16 offsets while bounding count and text.
  Evidence:
  [`K2CompilerAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapterTest.kt),
  tests `syntax and type diagnostics use virtual paths and UTF-16 offsets` and
  `diagnostic count text and physical paths are bounded`.

## Strings, arrays, and collections

- [x] **UTF-16 `CharArray` materialization** — `CharArray(size)`, indexed
  access, mutation, `size`, `concatToString(start, end)`, and
  `String(array, start, length)` preserve exact UTF-16 code units through
  Guest execution. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `primitive char array lowers deterministically for exact utf16 materialization`,
  and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_char_array_program_executes_exact_utf16_materialization`.

- [ ] **`String` operations — Partial** — literals, concatenation,
  interpolation lowered as concatenation, `length`, indexed `get`,
  `substring`, equality, direct `compareTo(String)`, the `<`, `<=`, `>`, and `>=`
  operators, and construction from `CharArray` map to verified VM operations.
  `compareTo` orders UTF-16 code units and returns the first differing code-unit
  difference, or the length difference for a prefix. The core library also
  provides `startsWith(prefix: String)`,
  `endsWith(suffix: String)`, `contains(other: String)`, and
  `indexOf(other: String, startIndex: Int = 0)`. Search uses UTF-16 code units;
  a negative start index begins at zero, and an empty search string returns the
  start index clamped to the string length. Other Kotlin text functions are not
  available. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `shell language subset lowers control flow scalars strings and raw terminal calls`
  and `String compareTo and ordering operators lower UTF-16 order for vm conformance`, with VM
  scenario `testKotlinStringCompareVmConformance`; also
  [`text_tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/text_tests.rs),
  tests `string_content_operations_use_kotlin_utf16_semantics`,
  `string_concat_selects_utf16_for_bmp_and_surrogate_code_units`, and
  `string_substring_preserves_full_identity_and_freshens_proper_ranges`, plus
  `MinimalScriptLoweringTest`, test
  `primitive char array lowers deterministically for exact utf16 materialization`,
  exercised by `testKotlinSubsetVmConformance` for the four library helpers.
  Tracking: not scheduled

- [ ] **Reference `Array<T>` operations — Partial** — entry `Array<String>`,
  `emptyArray<T>()`, direct `arrayOf` calls, `size`, and indexed get/set work for
  `String`, supported non-null Guest class references, and `Any`, including concrete
  uses inside specialized generic functions. `Array<Any>` boxes `Int` when constructed or written, preserves object
  identity, and returns the stored reference on reads. `copyOfRange` is available only
  for `Array<String>`. `Array<Int>`, nullable elements, other primitive-to-`Any` boxing, spread
  arguments, iterators, and higher-order operations are unavailable. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `string arrays can be constructed read and written` and
  `string arrays support copyOfRange and supported default arguments`, plus
  `reference arrays preserve Guest class elements and aliases` and
  `reference arrays reject unsupported element representations`, paired with
  `testKotlinReferenceArrayVmConformance` and
  [`gc_tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/gc_tests.rs),
  test `collector_scans_reference_arrays`. Tracking: [#656](https://github.com/CertifiedBadIdeas/Compukters/issues/656)

- [x] **Specialized `IntArray` storage** — `IntArray(size)`, `intArrayOf(...)`,
  empty arrays, `size`, indexed get/set, mutation, and direct `for` iteration lower to dense unboxed
  i32 storage. Factory arguments evaluate left-to-right exactly once; negative
  sizes, oversized allocations, and invalid indexes preserve VM trap or
  allocation-exhaustion behavior across quota slices. A direct `for` snapshots
  the source array once and reads its current elements by index, without an
  iterator allocation; empty arrays, reassignment, mutation, nested loops,
  `break`, and `continue` retain Kotlin behavior. Initializer lambdas,
  `Array<Int>`, stored iterators, `indices`, spread arguments, covariance,
  reflection, and collection helpers remain outside the admitted subset.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `specialized IntArray lowers to unboxed primitive array instructions`,
  `unsupported IntArray forms publish no artifact`, and
  `specialized IntArray lowers deterministically for vm conformance` (including
  direct iteration), plus
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_int_array_executes_specialized_storage_and_traps`.

- [ ] **Other primitive arrays — Unsupported** — primitive arrays other than
  `CharArray` and `IntArray` have no source-level Guest representation even
  though the VM can store every primitive array width. Tracking: not scheduled

- [x] **`Iterable<T>` search and predicate operations** — `contains` / `in`, `indexOf`, and `lastIndexOf` traverse
  any iterable, including a user-defined one. `any`, `all`, and `none` use `iterator()` and stop when the result is
  known. Empty iterables return `false`, `true`, and `true`, respectively. These operations work through
  `Iterable<Int>` and `Iterable<Any>` references. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `list Int covariance to Any preserves the list and boxes reads`, executed by `testKotlinListAnyVmConformance`.

- [ ] **Read-only `Collection<T>` and `List<T>` — Partial** — direct `listOf(...)`, `listOf<T>()`, and `emptyList<T>()`
  support non-null `Int`, `String`, and supported Guest class references. `size`, indexed `get`, and ordinary `for` iteration execute
  through concrete specialized implementations. `List<Int>` stores and returns unboxed i32 values; list aliases refer
  to the same object. Factory arguments run once in source order. Invalid indexes trap through the VM array bounds
  check, and iteration resumes across quota slices. `List<Int>` can widen to `List<Any>` without copying the list;
  reads and iteration through the universal view allocate `Int` boxes with checked `is Int` and `as Int` access. A
  supported reference list can also widen to `List<Any>` while preserving its element references. Direct
  `listOf<Any>(...)` stores boxed `Int` and supported references in one array; indexed reads reuse those references.
  `Collection<T>` owns `size`, `isEmpty`, and `contains`; custom collections can implement this contract.
  `isNotEmpty` is a `Collection<T>` extension. `List<T>` retains indexed access and its index-based `indexOf` and
  `lastIndexOf` extensions. Searches use the supported `==` semantics and return the first or last match, or `-1`.
  The `Iterable<T>` search and predicate extensions above also work on these lists.
  Extension functions are Guest `kotlin.collections` declarations and require imports. Nullable elements, unsupported
  primitive element types, spread arguments, mutable collections, `Set`, `Map`, sequences, and other collection
  algorithms are unavailable. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `read only lists retain typed Int String and guest references`,
  `list index outside bounds compiles to trapped array access`,
  `list iterator resumes across quota slices`,
  `list Int covariance to Any preserves the list and boxes reads`, and
  `unsupported list element and spread forms report diagnostics`; VM tasks
  `testKotlinListVmConformance`, `testKotlinListAnyVmConformance`, `testKotlinListAnyQuotaVmConformance`,
  `testKotlinListBoundsVmConformance`, and `testKotlinListQuotaVmConformance`. Tracking:
  [#656](https://github.com/CertifiedBadIdeas/Compukters/issues/656),
  [#581](https://github.com/CertifiedBadIdeas/Compukters/issues/581)

## Tasks and concurrency

- [x] **Transparent suspension across a host request** — an ordinary Guest call
  preserves its complete stack and resumes at its verified continuation after
  an asynchronous capability response. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `ordinary project call resumes transparently across host blocking`, and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_ordinary_project_call_resumes_across_async_capability`.

- [x] **VM-blocking calls from ordinary functions** — designated Guest API
  calls, task joins, and channel handoffs block only the current stackful VM
  task. Ordinary callers need no source modifier, including across nested
  project calls. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `ordinary main lowers trusted terminal wait as vm blocking` and
  `ordinary project call resumes transparently across host blocking`, paired
  with [`verify/tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/verify/tests.rs), tests
  `vm_blocking_capability_is_valid_in_a_non_suspending_function`,
  `task_yield_is_valid_in_a_non_suspending_function`, and
  `task_spawn_accepts_a_non_suspending_target`.

- [ ] **Kotlin `suspend` declarations — Unsupported** — Guest tasks use
  transparent stackful suspension instead of Kotlin's coroutine effect and
  calling convention. The compiler and IDE reject `suspend` source while the
  artifact reader, verifier, and VM retain legacy suspend-call support.
  Evidence: `MinimalScriptLoweringTest`, test
  `suspend declarations are rejected because Guest tasks suspend transparently`,
  and `DiagnosticQueryTest`, test
  `suspend declaration is outside the transparent Guest task model`.

- [ ] **Cooperative Guest tasks — Partial** — `Tasks.launch(block)` starts any
  supported non-null `() -> Unit` value as a bounded task, `Task.join()` waits for it,
  and `Tasks.sleepTicks(n)` suspends the current task until a deterministic
  server-tick boundary without consuming Guest instructions. Tasks share one VM and execute
  one at a time, but a task suspended on host I/O does not stop another runnable
  task. Scheduling and host-request ownership are deterministic. Public
  cancellation, explicit same-turn yield, wall-clock delay, scopes, and `kotlinx.coroutines` remain
  unsupported. Evidence: `MinimalScriptLoweringTest`, test
  `task tick sleep lowers to one asynchronous timer request`, the
  `testKotlinTimerVmConformance` task, and `ProgramRuntimeHostTest`, test
  `timer requests resume on deterministic server tick boundaries`. Tracking:
  [#624](https://github.com/CertifiedBadIdeas/Compukters/issues/624)

- [ ] **Bounded integer channels — Partial** — a top-level
  `IntChannel(capacity)` provides deterministic FIFO `send(Int)` and
  `receive(): Int` blocking handoff between cooperative tasks. Capacity must be a
  positive compile-time constant; channel storage and waiter state are admitted
  up front and communication stays inside the VM without a host request.
  Generic payloads, close, cancellation, selection, timeouts, and cross-process
  channels remain unsupported. Evidence:
  [`channel.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/channel.rs),
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  and the `testKotlinChannelVmConformance` task.
  Tracking: [#614](https://github.com/CertifiedBadIdeas/Compukters/issues/614)

- [ ] **Parallel Guest execution — Unsupported** — one process waits at a
  time executes Guest instructions. Cooperative tasks provide concurrency at
  suspension points, not parallel instruction execution. Tracking: not scheduled

## Built-in Guest platform

The platform internally uses the following modules to build and verify its Guest Kotlin surface. They form one atomic
built-in platform and are not selected individually in `compukter.toml`; there is no ambient Kotlin/JVM classpath.

| Module | Guest surface |
| --- | --- |
| `kotlin:builtins` | Core language types, arrays, function types, and structural declarations required by K2 |
| `stdlib:core` | Small native core helpers such as `require`, supported array construction, and bounded cooperative `Task` / `Tasks` declarations |
| `stdlib:ranges` | Declaration surface for `IntRange`, `until`, and `rangeUntil`; canonical unit-step `Int` loops lower without runtime range objects |
| `std:terminal` | `print`, `println`, `readln`, stderr, and raw terminal operations |
| `std:filesystem` | The bounded filesystem facade |
| `compukter:compiler` | Guest compilation operations |
| `compukter:process` | Child process execution and explicit exit |
| `compukter:redstone` | Side-oriented redstone reads, waits, and weak/direct output writes |
| `compukter:sound` | Bounded one-shot computer beeps with admission feedback |
| `compukter:display` | Typed adjacent and named text-display writes and clearing |

Ordinary functions in these modules are compiled ahead of Guest projects into
relocatable platform fragments. Only declarations explicitly marked as native
external bindings lower to host capability operations; a Guest declaration
cannot become one merely by copying its package, name, and signature.

The built-in modules are packaged with the tooling workers. Addons instead register an `AddonGuestApiBundle` on the
server. Its deterministic identity covers metadata, sources, capability schemas, and exact callable bindings. An
attached IDE receives the admitted data from the server. Completion can propose APIs from available inactive addons
and enable their addon IDs; diagnostics, parameter information, navigation, compilation, and cache invalidation
then use the same selected API identity without adding the addon JAR to either worker's JVM classpath. Evidence:
[`CompletionQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/CompletionQueryTest.kt)
and [`IdeCompletionPlannerTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/analysis/IdeCompletionPlannerTest.kt).

## Kotlin standard library

The detailed API inventory is in [Guest standard library support]({{ '/STDLIB-SUPPORT/' | relative_url }}).
The [Guest API reference]({{ '/guest-api/' | relative_url }}) lists public symbols, signatures, available KDoc, and
links to their source files.

- [ ] **Console functions — Partial** — `print` accepts `String`, `Int`, `Long`,
  `Float`, `Boolean`, and `Char`; `println` supports those types plus the no-argument
  form; `readln()` reads one canonical line. Other overloads and formatting
  are unavailable. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `ordinary Kotlin standard streams lower to stdio capability operations`,
  paired with [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), tests
  `stdio_read_line_echoes_then_writes_stdout_and_stderr_in_order` and
  `stdio_read_conflict_becomes_bounded_host_failure_without_consuming_input`.
  Tracking: not scheduled

- [ ] **Core scalar operations — Partial** — the admitted `Int`, `Long`, `Float`,
  `Boolean`, and `Char` operations listed above are provided by `kotlin:builtins` and
  canonical compiler primitives. The wider primitive API, parsing, general
  formatting, and math packages are absent. Tracking: not scheduled

- [ ] **Text and array helpers — Partial** — the `String`, `CharArray`,
  `IntArray`, and supported reference `Array<T>` operations listed above are published by the
  native built-ins and core modules. Regex, Unicode categories, encodings,
  other generic array helpers, and collection conversions are absent. Tracking: not scheduled

- [ ] **Standard collections and functional helpers — Unsupported** — the
  collection hierarchy and higher-order functions such as `map`, `filter`,
  and `fold` are not Guest runtime types. Tracking: not scheduled

- [ ] **Standard exceptions, reflection, and coroutine libraries — Unsupported** —
  these packages have no Guest implementation. Tracking: not scheduled

## Compukters Guest APIs

- [x] **Create kinetics on Minecraft 1.21.1** — when Create 6.0.10 through 6.0.x is loaded, the optional
  `create` addon exposes computer-local sides and persistent names reachable over passive peripheral cables through
  `Kinetics`. Programs can read exact `Float` speed, stress, and capacity values; wait for speed or load changes; and
  read or set a rotation controller's target speed. Handles remain bound to the exact acquired block entity and fail
  rather than rebinding after replacement. Evidence: `KineticsHostStateTest`, including `named acquisition routes
  every kinetic type and shares handles with side acquisition`; `ComputerPeripheralLookupTest`; and neutral addon IDE
  diagnostic/completion/parameter-information tests. See
  [Create addon](https://certifiedbadideas.github.io/Compukters/CREATE/) for setup and
  [Create kinetics](https://certifiedbadideas.github.io/Compukters/CREATE-KINETICS/) for the API.

- [x] **Create Stock Ticker on Minecraft 1.21.1** — the optional `create` addon exposes adjacent or named Stock
  Tickers through `Logistics`. Programs can capture bounded stock snapshots, find entries by exact item ID, inspect
  each variant's display name and count, and request bounded packaging to a validated address. A request reports
  Create's acceptance, not delivery. Snapshots must be closed after use and stale device handles fail. Evidence: `StockTickerHostStateTest`,
  the independent Create addon `check`, and its packaged Guest API bundle. See
  [Create logistics](https://certifiedbadideas.github.io/Compukters/CREATE-LOGISTICS/).

- [x] **Create steam boiler on Minecraft 1.21.1** — the optional `create` addon exposes adjacent or named active
  boilers through `Boilers`. Programs read water supply in mB/t, its 0–18 water level, active heat, effective boiler
  level, and passive-heating status. Handles stay bound to the selected Fluid Tank segment and controller. Evidence:
  `BoilerHostStateTest`, the independent Create addon `check`, and its packaged Guest API bundle. See
  [Create boilers](https://certifiedbadideas.github.io/Compukters/CREATE-BOILERS/).

- [x] **Addon Guest API bundles** — a loader integration can register one bounded, versioned Kotlin metadata/source
  bundle under its addon ID, with exact capability schemas and intrinsic bindings kept internal. The server is the
  authority for availability; compiler and IDE workers accept only the exact advertised bytes and content hash, reject
  malformed or shadowing bundles, and never execute addon JVM code. The first producer is the `create` integration,
  whose declarations and contract are owned by the Create addon and built against the canonical base platform.

- [x] **One-shot sound** — `Sound.beep(note, volume = 100)` emits the vanilla note-block pling from the
  computer and return whether the server admitted it. Notes are bounded to
  `0..24`, with `12` as neutral pitch; volume is bounded to `1..100` and
  defaults to `100`. A computer may emit once every four ticks, the server
  admits at most 64 computer sounds per tick, and rejected sounds are not
  queued. The VM validates the scalar request, while the actor carrier performs
  the Minecraft call on the server thread before resuming the Guest Boolean.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `sound beep lowers deterministically to a blocking Boolean capability operation`,
  [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), sound request tests, and
  [`ComputerSoundGameTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerSoundGameTest.kt).

- [x] **In-world text display** — `Display.open(name)` or `Display.<side>.open()` acquires an exact display block.
  Programs write and clear its independent 20x10 grid. One computer holds the active output lease; the screen clears
  when that computer stops or disconnects. Bounds and text are validated on the server. Evidence:
  `MinimalScriptLoweringTest`, `DisplayBufferTest`, `DisplayHostStateTest`, and the real `TextDisplayGameTestScenario`.
  See [Text display](https://certifiedbadideas.github.io/Compukters/DISPLAY/).

- [x] **Redstone GPIO** — `Redstone.<side>` exposes immediate `get()`,
  edge-triggered `await()`, exact `await(level)`, threshold
  `awaitAtLeast(level)`, and blocking
  `set(level, power = Redstone.Power.WEAK)`, with `Redstone.Power.DIRECT` for
  direct power. These operations
  lower through the trusted scalar capability while packed output batching
  remains private to the runtime. Rust waiter tests, core batch-commit tests, and the
  real NeoForge `compukters:computer_redstone` GameTest cover the complete path.
  Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `redstone program lowers deterministically for vm conformance`,
  [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), redstone tests, and
  [`ComputerRedstoneGameTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerRedstoneGameTest.kt).

- [x] **Terminal write, event wait, and key result** — `Terminal.write`,
  `Terminal.awaitEvent`, and `Terminal.eventKey` lower to exact terminal
  capability calls and execute across a host request. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `ordinary project call resumes transparently across host blocking`, and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_ordinary_project_call_resumes_across_async_capability`.

- [ ] **Remaining raw terminal operations — Partial** — clear, erase, text and
  action/modifier event fields, and event completion lower through trusted
  signatures and have device-level VM tests, but lack generated
  Kotlin-to-VM execution coverage. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `shell language subset lowers control flow scalars strings and raw terminal calls`,
  paired with
  [`terminal_device.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/tests/terminal_device.rs), tests
  `stable_key_and_atomic_text_events_merge_in_fifo_order` and
  `input_limits_reject_whole_events_without_partial_queue_mutation`.
  Tracking: not scheduled

- [ ] **Positional terminal drawing — Partial** — cursor position and
  visibility, palette colors, `writeAt`, and rectangular `fill` lower through
  exact trusted signatures and have VM device conformance, but no generated
  Kotlin program executes the complete facade end to end. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `positional terminal facade lowers through exact trusted signatures`,
  paired with
  [`terminal_device.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/tests/terminal_device.rs), tests
  `positional_patch_and_fill_do_not_move_the_stream_cursor` and
  `positional_terminal_write_clips_one_row_and_decodes_scalars`.
  Tracking: not scheduled

- [ ] **Filesystem facade — Partial** — `stat`, `list`, `readText`, and
  `writeText` have exact trusted signatures and bounded VM operations.
  Lowering coverage currently executes only at the compiler/VM sides
  separately. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `filesystem text facade lowers through exact trusted signatures`,
  and [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), tests
  `filesystem_text_response_is_bounded_before_guest_materialization` and
  `filesystem_text_write_replaces_existing_bytes_through_the_machine`.
  Tracking: not scheduled

- [ ] **Process facade — Partial** — `Process.run(path, args)` returns typed
  exited/failed results, and `Process.exit(code)` terminates explicitly. The
  source facade and VM process contract are covered separately rather than by
  one end-to-end generated program. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `typed process v2 facade lowers without public capability masks or suspend calls`,
  paired with [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), tests
  `process_v2_run_materializes_structured_arguments_for_the_child` and
  `process_v2_explicit_exit_preserves_all_codes_and_rejects_invalid_values`.
  Tracking: not scheduled

- [ ] **Compiler facade — Partial** — `Compiler.compile(source, output)` and
  `Compiler.diagnostics()` are published by `compukter:compiler`, and the
  checked-in `/rom/kotlinc` program compiles deterministically. Full
  Guest-to-host compilation behavior is tested at the VM transaction layer
  rather than as one generated Kotlin execution test. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `checked in kotlinc compiles deterministically`, paired with
  [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs), test
  `compiler_transaction_snapshots_and_atomically_installs_an_executable`.
  Tracking: not scheduled

- [x] **Trusted API identity** — a user declaration cannot impersonate a Guest
  intrinsic merely by copying its name and signature. The canonical registry
  keys every external binding by selected platform module, Kotlin callable ID,
  and exact canonical signature; lowering also verifies that the declaration
  came from native platform metadata or the exact platform source module.
  Evidence:
  [`TrustedIntrinsicContractTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2-engine/src/test/kotlin/ru/lazyhat/compukters/compiler/k2/engine/intrinsic/TrustedIntrinsicContractTest.kt)
  and
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `platform callable lookalike remains an ordinary project call`.

## IDE and tooling

- [x] **Incremental lexical highlighting** — edits propagate lexical state and
  remain identical to a full scan. Evidence:
  [`IncrementalKotlinHighlighterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/highlight/IncrementalKotlinHighlighterTest.kt),
  tests `edits propagate lexical state and remain identical to a full scan`
  and `seeded random edits always equal the full-scan oracle`.

- [x] **Smart Kotlin delimiter and block entry** — writable Kotlin sources
  insert and track balanced delimiters, wrap selections, remove untouched
  pairs with Backspace, and preserve structural indentation and line endings
  on Enter without applying the behavior to plain-text files. Evidence:
  [`KotlinSmartTypingTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/editor/KotlinSmartTypingTest.kt),
  tests `pairs wrap and tracked closers remain distinct from ordinary source`,
  `paired backspace and undo are atomic`, `pairing is suppressed inside strings and comments`,
  and `structural enter preserves CRLF and splits an automatic brace pair`,
  plus [`IdeClientControllerTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeClientControllerTest.kt),
  test `Kotlin smart typing flows through writable editor while plain text stays literal`.

- [x] **On-demand Kotlin formatting** — Ctrl+Alt+L and the toolbar Format
  action format writable `.kt` sources with ktlint standard rules, applying
  the result as one undoable edit with a mapped UTF-16 caret and leaving it
  dirty for a separate save. Stale results never replace newer typing;
  formatter failures warn without saving, while Ctrl+S, autosave, implicit
  saves, previews, and non-Kotlin files remain unaffected.
  Evidence:
  [`KotlinFormatterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-kotlin-formatter/src/test/kotlin/ru/lazyhat/compukters/ide/formatter/KotlinFormatterTest.kt),
  [`RelocatedKotlinFormatterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-kotlin-formatter/src/test/kotlin/ru/lazyhat/compukters/ide/formatter/RelocatedKotlinFormatterTest.kt),
  [`IsolatedKotlinFormatterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/formatter/IsolatedKotlinFormatterTest.kt),
  [`FormatQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/FormatQueryTest.kt),
  and
  [`IdeAnalysisFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeAnalysisFlowTest.kt),
  tests `explicit format changes Kotlin atomically and leaves saving separate`,
  `stale format result never overwrites or saves newer typing`,
  `format failure warns without saving the unformatted Kotlin source`, and
  `explicit save bypasses the asynchronous formatter`.

- [x] **Semantic highlighting and inferred-type presentation** — declarations,
  extension functions, inferred expressions, and smart casts receive K2-backed
  semantic tokens; mutable properties, locals, and their resolved references
  carry the Islands Dark underline effect. Evidence:
  [`SemanticTokenQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/SemanticTokenQueryTest.kt),
  tests `presentation classifies declarations and extension functions`,
  `presentation marks inferred and smart cast expressions`, and
  `presentation marks mutable declarations and references`, plus
  [`IdeRendererStateTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRendererStateTest.kt),
  test `expression metadata does not override lexical code colors`.

- [x] **K2 diagnostics** — incomplete syntax remains analyzable, multi-file
  diagnostics retain virtual paths, and UTF-16 ranges remain exact. Evidence:
  [`DiagnosticQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/DiagnosticQueryTest.kt),
  tests `type error after supplementary character keeps UTF-16 range`,
  `diagnostics from multiple files retain their virtual paths`, and
  `incomplete syntax produces a bounded diagnostic instead of failing analysis`.

- [x] **Semantic completion with overloads** — completion uses inferred
  receivers, applicable extensions, visibility, distinct overload entries,
  argument labels, deterministic ranking, and bounded result counts. Evidence:
  [`CompletionQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/CompletionQueryTest.kt),
  tests `qualified completion uses inferred receiver members and applicable extensions`,
  `completion preserves overloads and orders them deterministically`, and
  `completion gives standard library overloads distinct argument labels`, and
  `completion tolerates synthetic function interfaces from platform libraries`,
  plus
  [`CompletionIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/integration/CompletionIntegrationTest.kt),
  test `forked worker returns semantic completion`.

- [x] **Context-aware keyword completion** — declaration, modifier, statement,
  and expression keywords are ranked with semantic symbols for valid file,
  class-body, and executable-block contexts, while imports, package directives,
  qualified access, comments, and literal string content suppress them. Evidence:
  [`CompletionQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/CompletionQueryTest.kt),
  tests `completion proposes keywords for declarations and executable blocks`
  and `completion suppresses keywords outside unqualified Kotlin code`.

- [x] **Expression information and callable signatures** — hover-style
  queries render inferred local types, resolved signatures, and smart-cast
  types. Evidence:
  [`ExpressionInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ExpressionInfoQueryTest.kt),
  tests `expression query renders an inferred local type`,
  `expression query renders a resolved callable signature`, and
  `expression query reports a smart cast type`.

- [x] **Parameter information** — Ctrl+P opens a caret-anchored popup for the
  innermost call, lists bounded and deterministic K2-resolved overload
  signatures, and highlights the active positional, named, or vararg
  parameter. The popup follows edits and caret movement, rejects stale
  snapshot results, and closes on Escape, focus loss, file changes, or when
  the caret leaves a call. Evidence:
  [`ParameterInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ParameterInfoQueryTest.kt),
  [`AnalysisRequestCoordinatorTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-client/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/controller/AnalysisRequestCoordinatorTest.kt),
  [`IdeAnalysisFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeAnalysisFlowTest.kt),
  [`IdeInputAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeInputAdapterTest.kt),
  and
  [`IdeRendererStateTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRendererStateTest.kt).

- [x] **Navigation and project references** — declarations, selected platform
  APIs, builtins such as `intArrayOf`, and exact project references resolve to
  their attached sources without matching unrelated same-spelling symbols.
  Evidence:
  [`NavigationAndReferencesTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/integration/NavigationAndReferencesTest.kt),
  tests `forked worker navigates and finds exact project references` and
  `forked worker navigates to attached builtin source`, paired
  with [`DeclarationQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/DeclarationQueryTest.kt),
  test `navigation maps int array factory to its platform source`, and
  [`ReferenceQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ReferenceQueryTest.kt),
  test `references cross project files and exclude unrelated same spelling symbols`.

- [x] **Local project build and cache** — the client builds real project
  snapshots, reuses the global compiler cache, deduplicates active work, and
  keeps compiler I/O off the caller thread. Evidence:
  [`LocalIdeWorkflowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/integration/LocalIdeWorkflowTest.kt),
  test `real project resolves builds and reuses global compiler cache`, and
  [`ClientCompilationServiceTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/compiler/ClientCompilationServiceTest.kt),
  tests `deduplicates active build and admits one distinct queued build` and
  `cache hit avoids another worker request and all IO stays on service thread`.

- [x] **Target verification, deployment, and run** — verification is
  non-mutating, successful tickets can be reused by deployment, and Run saves,
  builds, deploys the manifest program, then submits its installed path.
  Evidence:
  [`IdeTargetCoordinatorTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/target/IdeTargetCoordinatorTest.kt),
  tests `verify is non mutating and its matching ticket is reused by deploy`
  and `run deploys then submits exactly the installed path`, plus
  [`IdeTargetFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeTargetFlowTest.kt),
  test `run saves builds deploys manifest program and submits canonical line`.

- [ ] **Debugger and runtime inspection — Unsupported** — there are no
  breakpoints, stepping, watches, stack inspection, or live variable views.
  Tracking: not scheduled

## Intentional non-goals

- **Not planned: Java interoperability and JVM bytecode/libraries.** Guest
  programs target Compukter bytecode, not a JVM.
- **Not planned: reflection and dynamic class loading.** Runtime types and code
  are admitted from verified artifacts before execution.
- **Not planned: arbitrary compiler plugins and annotation processors.** The
  trusted compiler pipeline and selected native platform modules define the
  source surface.
- **Not planned: ambient access to host JVM or operating-system resources.**
  Guest programs cross only explicit, bounded capability interfaces.

## Maintenance policy

- A commit that changes Guest Kotlin support updates the affected matrix entry
  and its evidence in the same commit.
- Every checked item keeps a stable repository link and names the exact test
  behavior that supports it.
- A scheduled gap links its exact implementation issue; broad umbrella issues
  do not replace feature-specific tracking.
- Removing support unchecks the item and states the new boundary in the same
  change.
- Intentional non-goals change only through an explicit architecture decision,
  not by converting them into unchecked tasks.
- This document carries no manually maintained release number or commit hash;
  it always describes the revision that contains it.
